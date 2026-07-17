package com.tallybackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tallybackend.cache.TallyCacheSyncService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class TallyVoucherWriteQueueService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final WritePayloadTransformer writePayloadTransformer;
    private final TallyVoucherWriteQueueRepository repository;
    private final TallyQueueEntityMetadataService metadataService;
    private final ObjectProvider<TallyCacheSyncService> cacheSyncServiceProvider;
    private final boolean enabled;
    private final boolean autoProcessApproved;
    private final long retryIntervalMs;
    private final long historyRetentionMinutes;
    private final int batchSize;
    private final int maxAttempts;
    private final AtomicBoolean processing = new AtomicBoolean(false);

    @Autowired
    public TallyVoucherWriteQueueService(RestTemplate restTemplate,
                                         ObjectMapper objectMapper,
                                         WritePayloadTransformer writePayloadTransformer,
                                         TallyVoucherWriteQueueRepository repository,
                                         TallyQueueEntityMetadataService metadataService,
                                         ObjectProvider<TallyCacheSyncService> cacheSyncServiceProvider,
                                         @Value("${tally.write.queue.enabled:true}") boolean enabled,
                                         @Value("${tally.write.queue.auto-process-approved:true}") boolean autoProcessApproved,
                                         @Value("${tally.write.queue.retry-interval-ms:60000}") long retryIntervalMs,
                                         @Value("${tally.write.queue.history-retention-minutes:180}") long historyRetentionMinutes,
                                         @Value("${tally.write.queue.batch-size:10}") int batchSize,
                                         @Value("${tally.write.queue.max-attempts:-1}") int maxAttempts) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.writePayloadTransformer = writePayloadTransformer;
        this.repository = repository;
        this.metadataService = metadataService;
        this.cacheSyncServiceProvider = cacheSyncServiceProvider;
        this.enabled = enabled;
        this.autoProcessApproved = autoProcessApproved;
        this.retryIntervalMs = retryIntervalMs;
        this.historyRetentionMinutes = historyRetentionMinutes;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    public TallyVoucherWriteQueueService(RestTemplate restTemplate,
                                         ObjectMapper objectMapper,
                                         WritePayloadTransformer writePayloadTransformer,
                                         TallyVoucherWriteQueueRepository repository,
                                         ObjectProvider<TallyCacheSyncService> cacheSyncServiceProvider,
                                         boolean enabled,
                                         long retryIntervalMs,
                                         int batchSize,
                                         int maxAttempts) {
        this(
                restTemplate,
                objectMapper,
                writePayloadTransformer,
                repository,
                new TallyQueueEntityMetadataService(objectMapper),
                cacheSyncServiceProvider,
                enabled,
                false,
                retryIntervalMs,
                180,
                batchSize,
                maxAttempts
        );
    }

    public boolean supportsQueue(HttpMethod method, String connectorPath, HttpServletRequest request) {
        if (!enabled || !HttpMethod.POST.equals(method) || connectorPath == null) {
            return false;
        }
        String action = trimToNull(request.getParameter("action"));
        if (!(action == null || "create".equalsIgnoreCase(action))) {
            return false;
        }

        String normalizedPath = connectorPath.toLowerCase();
        if (normalizedPath.startsWith("/vouchers")) {
            return !normalizedPath.contains("/einvoice")
                    && !normalizedPath.contains("/ewaybill")
                    && !normalizedPath.contains("/status")
                    && !normalizedPath.contains("/ready")
                    && !normalizedPath.contains("/import-xml");
        }

        return normalizedPath.equals("/groups")
                || normalizedPath.equals("/ledgers")
                || normalizedPath.equals("/uoms")
                || normalizedPath.equals("/stock-groups")
                || normalizedPath.equals("/stock-items")
                || normalizedPath.equals("/settings/company-currency")
                || normalizedPath.equals("/settings/company-features")
                || normalizedPath.equals("/currencies")
                || normalizedPath.equals("/ledger-groups")
                || normalizedPath.equals("/cost-categories")
                || normalizedPath.equals("/cost-centres")
                || normalizedPath.equals("/projects")
                || normalizedPath.equals("/godowns")
                || normalizedPath.equals("/stock-categories")
                || normalizedPath.equals("/boms")
                || normalizedPath.equals("/price-levels")
                || normalizedPath.equals("/price-lists")
                || normalizedPath.equals("/voucher-types")
                || normalizedPath.equals("/budgets");
    }

    public void assertVoucherNumberAvailable(HttpMethod method,
                                             String connectorPath,
                                             HttpServletRequest request,
                                             String body) {
        if (!HttpMethod.POST.equals(method)) {
            return;
        }
        VoucherDuplicateCheck duplicateCheck = buildVoucherDuplicateCheck(
                connectorPath,
                firstNonBlank(
                        trimToNull(request.getHeader("X-Company")),
                        trimToNull(request.getParameter("company")),
                        deriveCompanyFromBody(body)
                ),
                body
        );
        if (duplicateCheck == null) {
            return;
        }
        TallyVoucherWriteQueueEntry existing = repository.findVoucherNumberDuplicate(
                duplicateCheck.company,
                duplicateCheck.connectorPath,
                duplicateCheck.voucherNumber
        );
        if (existing != null) {
            throw duplicateVoucherNumberConflict(duplicateCheck, existing);
        }
    }

    public boolean shouldQueueForUpstreamFailure(HttpStatus status, String responseBody) {
        if (status == null) {
            return false;
        }
        if (status.is2xxSuccessful()) {
            return false;
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE || status == HttpStatus.GATEWAY_TIMEOUT) {
            return true;
        }
        if (status == HttpStatus.BAD_GATEWAY) {
            return isTallyUnavailableResponse(responseBody);
        }
        return false;
    }

    public ResponseEntity<String> queueFromRequest(HttpMethod method,
                                                   String connectorPath,
                                                   HttpServletRequest request,
                                                   String body,
                                                   ResolvedConnectorTarget target,
                                                   String reason,
                                                   HttpStatus upstreamStatus,
                                                   String upstreamBody) {
        Instant now = Instant.now();
        OfflineVoucherRewriteResult rewriteResult = rewriteOfflineVoucherRequest(connectorPath, trimToNull(request.getContentType()), body);
        String queuedRequestBody = rewriteResult.requestBody;
        TallyVoucherWriteQueueEntry entry = new TallyVoucherWriteQueueEntry();
        entry.setConnectorPath(connectorPath);
        entry.setHttpMethod(method.name());
        entry.setActionName(trimToNull(request.getParameter("action")));
        entry.setQueryString(buildForwardedQueryString(request));
        entry.setRequestBody(queuedRequestBody);
        entry.setContentType(trimToNull(request.getContentType()));
        entry.setConnectorId(target == null ? null : target.getConnectorId());
        entry.setConnectorBaseUrl(target == null ? null : target.getBaseUrl());
        entry.setAgentKey(target == null ? null : target.getAgentKey());
        entry.setCompany(firstNonBlank(
                trimToNull(request.getHeader("X-Company")),
                trimToNull(request.getParameter("company")),
                deriveCompanyFromBody(queuedRequestBody)
        ));
        entry.setRequestedBy(request.getAttribute("auth.subject") == null ? null : String.valueOf(request.getAttribute("auth.subject")));
        entry.setStatus("QUEUED");
        entry.setAttempts(0);
        entry.setMaxAttempts(maxAttempts);
        entry.setLastError(reason);
        entry.setResponseBody(upstreamBody);
        entry.setConflictState("NONE");
        entry.setReviewState("PENDING_REVIEW");
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        entry.setNextAttemptAt(now.plusMillis(retryIntervalMs));
        entry.setOriginalVoucherNumber(rewriteResult.originalVoucherNumber);
        entry.setOfflineVoucherNumber(rewriteResult.offlineVoucherNumber);
        metadataService.enrichEntry(entry);

        Long queueId = insertEntryWithDuplicateGuard(entry);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("queued", true);
        payload.put("queue_id", queueId);
        payload.put("queue_status", "QUEUED");
        payload.put("message", "Tally is offline right now. The request was saved in MySQL and will wait for your sync review popup.");
        payload.put("fallback_applied", true);
        payload.put("retry_interval_ms", retryIntervalMs);
        payload.put("connector_id", target == null ? null : target.getConnectorId());
        payload.put("connector_base_url", target == null ? null : target.getBaseUrl());
        payload.put("company", entry.getCompany());
        payload.put("reason", reason);
        if (rewriteResult.offlineVoucherNumber != null) {
            payload.put("offline_voucher_number", rewriteResult.offlineVoucherNumber);
        }
        if (rewriteResult.originalVoucherNumber != null) {
            payload.put("original_voucher_number", rewriteResult.originalVoucherNumber);
        }
        if (upstreamStatus != null) {
            payload.put("upstream_status", upstreamStatus.value());
        }
        if (upstreamBody != null && !upstreamBody.trim().isEmpty()) {
            payload.put("upstream_response", readJsonOrText(upstreamBody));
        }

        return jsonResponse(HttpStatus.ACCEPTED, payload);
    }

    public ResponseEntity<String> queueManual(HttpMethod method,
                                              String connectorPath,
                                              String actionName,
                                              String company,
                                              String requestedBy,
                                              String contentType,
                                              String body,
                                              ResolvedConnectorTarget target,
                                              String reason,
                                              HttpStatus upstreamStatus,
                                              String upstreamBody) {
        Instant now = Instant.now();
        OfflineVoucherRewriteResult rewriteResult = rewriteOfflineVoucherRequest(connectorPath, contentType, body);
        String queuedRequestBody = rewriteResult.requestBody;
        TallyVoucherWriteQueueEntry entry = new TallyVoucherWriteQueueEntry();
        entry.setConnectorPath(connectorPath);
        entry.setHttpMethod(method.name());
        entry.setActionName(trimToNull(actionName));
        entry.setQueryString(buildForwardedQueryString(actionName));
        entry.setRequestBody(queuedRequestBody);
        entry.setContentType(trimToNull(contentType));
        entry.setConnectorId(target == null ? null : target.getConnectorId());
        entry.setConnectorBaseUrl(target == null ? null : target.getBaseUrl());
        entry.setAgentKey(target == null ? null : target.getAgentKey());
        entry.setCompany(trimToNull(company));
        entry.setRequestedBy(trimToNull(requestedBy));
        entry.setStatus("QUEUED");
        entry.setAttempts(0);
        entry.setMaxAttempts(maxAttempts);
        entry.setLastError(reason);
        entry.setResponseBody(upstreamBody);
        entry.setConflictState("NONE");
        entry.setReviewState("PENDING_REVIEW");
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        entry.setNextAttemptAt(now.plusMillis(retryIntervalMs));
        entry.setOriginalVoucherNumber(rewriteResult.originalVoucherNumber);
        entry.setOfflineVoucherNumber(rewriteResult.offlineVoucherNumber);
        metadataService.enrichEntry(entry);

        Long queueId = insertEntryWithDuplicateGuard(entry);

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("queued", true);
        payload.put("queue_id", queueId);
        payload.put("queue_status", "QUEUED");
        payload.put("message", "Voucher saved offline successfully. It is stored in MySQL and will wait for your sync review popup.");
        payload.put("fallback_applied", true);
        payload.put("retry_interval_ms", retryIntervalMs);
        payload.put("connector_id", target == null ? null : target.getConnectorId());
        payload.put("connector_base_url", target == null ? null : target.getBaseUrl());
        payload.put("company", entry.getCompany());
        payload.put("reason", reason);
        if (rewriteResult.offlineVoucherNumber != null) {
            payload.put("offline_voucher_number", rewriteResult.offlineVoucherNumber);
        }
        if (rewriteResult.originalVoucherNumber != null) {
            payload.put("original_voucher_number", rewriteResult.originalVoucherNumber);
        }
        if (upstreamStatus != null) {
            payload.put("upstream_status", upstreamStatus.value());
        }
        if (upstreamBody != null && !upstreamBody.trim().isEmpty()) {
            payload.put("upstream_response", readJsonOrText(upstreamBody));
        }

        return jsonResponse(HttpStatus.ACCEPTED, payload);
    }

    public void recordAppliedRequest(HttpMethod method,
                                     String connectorPath,
                                     HttpServletRequest request,
                                     String body,
                                     ResolvedConnectorTarget target,
                                     String upstreamBody) {
        if (!supportsQueue(method, connectorPath, request)) {
            return;
        }
        Instant now = Instant.now();
        TallyVoucherWriteQueueEntry entry = new TallyVoucherWriteQueueEntry();
        entry.setConnectorPath(connectorPath);
        entry.setHttpMethod(method.name());
        entry.setActionName(trimToNull(request.getParameter("action")));
        entry.setQueryString(buildForwardedQueryString(request));
        entry.setRequestBody(body);
        entry.setContentType(trimToNull(request.getContentType()));
        entry.setConnectorId(target == null ? null : target.getConnectorId());
        entry.setConnectorBaseUrl(target == null ? null : target.getBaseUrl());
        entry.setAgentKey(target == null ? null : target.getAgentKey());
        entry.setCompany(firstNonBlank(
                trimToNull(request.getHeader("X-Company")),
                trimToNull(request.getParameter("company")),
                deriveCompanyFromBody(body)
        ));
        entry.setRequestedBy(request.getAttribute("auth.subject") == null ? null : String.valueOf(request.getAttribute("auth.subject")));
        entry.setStatus("APPLIED");
        entry.setAttempts(1);
        entry.setMaxAttempts(maxAttempts);
        entry.setResponseBody(upstreamBody);
        entry.setConflictState("NONE");
        entry.setReviewState("SYNCED");
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        entry.setLastAttemptAt(now);
        entry.setCompletedAt(now);
        metadataService.enrichEntry(entry);
        insertEntryWithDuplicateGuard(entry);
    }

    public Map<String, Object> status(int limit, String company, String connectorPath, String status) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("enabled", enabled);
        payload.put("auto_process_approved", autoProcessApproved);
        payload.put("retry_interval_ms", retryIntervalMs);
        payload.put("batch_size", batchSize);
        payload.put("max_attempts", maxAttempts);
        payload.put("entries", repository.listRecent(limit, company, connectorPath, normalizeStatus(status)));
        return payload;
    }

    public Map<String, Object> cleanup(String company,
                                       String connectorPath,
                                       String connectorBaseUrl,
                                       String statuses,
                                       Integer olderThanMinutes) {
        if ("HISTORY".equalsIgnoreCase(trimToNull(statuses))) {
            return cleanupTerminalHistory(company, olderThanMinutes == null ? historyRetentionMinutes : olderThanMinutes.longValue());
        }
        List<String> normalizedStatuses = parseCleanupStatuses(statuses);
        Instant updatedBefore = null;
        if (olderThanMinutes != null && olderThanMinutes.intValue() > 0) {
            updatedBefore = Instant.now().minusSeconds(olderThanMinutes.longValue() * 60L);
        }

        int deleted = repository.deleteMatching(
                normalizedStatuses,
                trimToNull(company),
                trimToNull(connectorPath),
                trimToNull(connectorBaseUrl),
                updatedBefore
        );

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("deleted", deleted);
        payload.put("statuses", normalizedStatuses);
        payload.put("company", trimToNull(company));
        payload.put("connector_path", trimToNull(connectorPath));
        payload.put("connector_base_url", trimToNull(connectorBaseUrl));
        payload.put("older_than_minutes", olderThanMinutes);
        return payload;
    }

    public Map<String, Object> cleanupTerminalHistory(String company, long olderThanMinutes) {
        long effectiveMinutes = olderThanMinutes > 0 ? olderThanMinutes : historyRetentionMinutes;
        Instant updatedBefore = Instant.now().minusSeconds(effectiveMinutes * 60L);
        int deleted = repository.deleteTerminalHistory(trimToNull(company), updatedBefore);
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("deleted", deleted);
        payload.put("statuses", Collections.singletonList("HISTORY"));
        payload.put("company", trimToNull(company));
        payload.put("older_than_minutes", effectiveMinutes);
        return payload;
    }

    public Map<String, Object> processNow() {
        int processed = processPendingQueue();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("processed", processed);
        payload.put("enabled", enabled);
        return payload;
    }

    @Scheduled(fixedDelayString = "${tally.write.queue.retry-interval-ms:60000}")
    public void scheduledProcess() {
        if (!autoProcessApproved) {
            return;
        }
        processPendingQueue();
    }

    @Scheduled(fixedDelayString = "${tally.write.queue.history-cleanup-interval-ms:900000}")
    public void scheduledHistoryCleanup() {
        if (!enabled) {
            return;
        }
        cleanupTerminalHistory(null, historyRetentionMinutes);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void processPendingQueueOnStartup() {
        if (enabled) {
            cleanupTerminalHistory(null, historyRetentionMinutes);
        }
        if (!autoProcessApproved) {
            return;
        }
        processPendingQueue();
    }

    public int processPendingQueue() {
        if (!enabled || !processing.compareAndSet(false, true)) {
            return 0;
        }
        try {
            Instant now = Instant.now();
            repository.resetStaleProcessing(now.minusMillis(Math.max(retryIntervalMs, 1000L)), now);
            List<TallyVoucherWriteQueueEntry> dueEntries = repository.findDueEntries(batchSize, now);
            int processed = 0;
            boolean appliedAny = false;
            for (TallyVoucherWriteQueueEntry entry : dueEntries) {
                if (repository.markProcessing(entry.getId(), now) <= 0) {
                    continue;
                }
                processed++;
                appliedAny = replayEntry(entry) || appliedAny;
            }
            if (appliedAny) {
                refreshCacheAfterAppliedQueue();
            }
            return processed;
        } finally {
            processing.set(false);
        }
    }

    private boolean replayEntry(TallyVoucherWriteQueueEntry entry) {
        Instant now = Instant.now();
        int nextAttemptCount = entry.getAttempts() + 1;
        try {
            if ("CONFLICT".equalsIgnoreCase(trimToNull(entry.getConflictState()))
                    && !"APPROVED_SYNC".equalsIgnoreCase(trimToNull(entry.getReviewState()))) {
                repository.updateReconciliationState(
                        entry.getId(),
                        "CONFLICT",
                        entry.getConflictPayload(),
                        firstNonBlank(entry.getReviewState(), "PENDING_REVIEW"),
                        entry.getReviewedBy(),
                        entry.getReviewedAt()
                );
                return false;
            }
            HttpMethod method = HttpMethod.resolve(entry.getHttpMethod());
            if (method == null) {
                repository.markFailed(entry.getId(), nextAttemptCount, "Unsupported HTTP method in queue.", entry.getResponseBody(), now);
                return false;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            if (entry.getAgentKey() != null && !entry.getAgentKey().trim().isEmpty()) {
                headers.set("X-AGENT-KEY", entry.getAgentKey().trim());
            }
            if (entry.getCompany() != null && !entry.getCompany().trim().isEmpty()) {
                headers.set("X-Company", entry.getCompany().trim());
            }
            if (entry.getContentType() != null && !entry.getContentType().trim().isEmpty()) {
                headers.setContentType(MediaType.parseMediaType(entry.getContentType()));
            } else {
                headers.setContentType(MediaType.APPLICATION_JSON);
            }

            String transformedBody = writePayloadTransformer.transform(entry.getConnectorPath(), method, entry.getRequestBody());
            HttpEntity<String> entity = transformedBody == null ? new HttpEntity<String>(headers) : new HttpEntity<String>(transformedBody, headers);
            ResponseEntity<String> upstream = restTemplate.exchange(buildReplayUrl(entry), method, entity, String.class);
            String responseBody = upstream.getBody();

            if (upstream.getStatusCode().is2xxSuccessful()) {
                repository.markApplied(entry.getId(), nextAttemptCount, responseBody, now);
                return true;
            }

            if (!hasAttemptsRemaining(entry, nextAttemptCount) || !shouldRetry(upstream.getStatusCode(), responseBody)) {
                repository.markFailed(entry.getId(), nextAttemptCount, summarizeFailure(upstream.getStatusCode(), responseBody), responseBody, now);
                return false;
            }

            repository.markRetry(
                    entry.getId(),
                    nextAttemptCount,
                    summarizeFailure(upstream.getStatusCode(), responseBody),
                    responseBody,
                    now,
                    now.plusMillis(retryIntervalMs)
            );
            return false;
        } catch (ResourceAccessException ex) {
            if (!hasAttemptsRemaining(entry, nextAttemptCount)) {
                repository.markFailed(entry.getId(), nextAttemptCount, ex.getMessage(), null, now);
                return false;
            }
            repository.markRetry(entry.getId(), nextAttemptCount, ex.getMessage(), null, now, now.plusMillis(retryIntervalMs));
            return false;
        } catch (Exception ex) {
            repository.markFailed(entry.getId(), nextAttemptCount, ex.getMessage(), null, now);
            return false;
        }
    }

    private void refreshCacheAfterAppliedQueue() {
        TallyCacheSyncService cacheSyncService = cacheSyncServiceProvider.getIfAvailable();
        if (cacheSyncService != null) {
            cacheSyncService.runSyncNow();
        }
    }

    private boolean hasAttemptsRemaining(TallyVoucherWriteQueueEntry entry, int nextAttemptCount) {
        int entryMaxAttempts = entry == null ? maxAttempts : entry.getMaxAttempts();
        if (entryMaxAttempts <= 0) {
            return true;
        }
        return nextAttemptCount < entryMaxAttempts;
    }

    private boolean shouldRetry(HttpStatus status, String responseBody) {
        if (status == null) {
            return false;
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE || status == HttpStatus.GATEWAY_TIMEOUT) {
            return true;
        }
        if (status == HttpStatus.BAD_GATEWAY) {
            return isTallyUnavailableResponse(responseBody);
        }
        return isTallyUnavailableResponse(responseBody);
    }

    private boolean isTallyUnavailableResponse(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return false;
        }
        String trimmed = responseBody.trim();
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            if (node.has("error_type") && "tally_connection_unavailable".equalsIgnoreCase(node.path("error_type").asText())) {
                return true;
            }
            JsonNode detailNode = node.path("detail");
            if (detailNode.isTextual() && "tally_connection_unavailable".equalsIgnoreCase(detailNode.asText())) {
                return true;
            }
            if (detailNode.isObject()) {
                if ("tally_connection_unavailable".equalsIgnoreCase(detailNode.path("error_type").asText())) {
                    return true;
                }
                String detailReason = detailNode.path("reason").asText("");
                if (detailReason.toLowerCase().contains("tallyprime")
                        || detailReason.toLowerCase().contains("127.0.0.1:9000")
                        || detailReason.toLowerCase().contains("open the company in tallyprime")) {
                    return true;
                }
            }
            String reason = node.path("reason").asText("");
            return reason.toLowerCase().contains("tallyprime")
                    || reason.toLowerCase().contains("127.0.0.1:9000")
                    || reason.toLowerCase().contains("open the company in tallyprime");
        } catch (Exception ignored) {
            String lower = trimmed.toLowerCase();
            return lower.contains("tally_connection_unavailable")
                    || lower.contains("tallyprime is not accepting xml requests")
                    || lower.contains("open the company in tallyprime");
        }
    }

    private Object readJsonOrText(String rawBody) {
        try {
            return objectMapper.readTree(rawBody);
        } catch (Exception ignored) {
            return rawBody;
        }
    }

    private ResponseEntity<String> jsonResponse(HttpStatus status, Map<String, Object> payload) {
        try {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"queued\":true}");
        }
    }

    private String buildReplayUrl(TallyVoucherWriteQueueEntry entry) {
        StringBuilder url = new StringBuilder();
        url.append(entry.getConnectorBaseUrl());
        if (!entry.getConnectorPath().startsWith("/")) {
            url.append('/');
        }
        url.append(entry.getConnectorPath());
        if (entry.getQueryString() != null && !entry.getQueryString().trim().isEmpty()) {
            url.append('?').append(entry.getQueryString());
        }
        return url.toString();
    }

    private String buildForwardedQueryString(HttpServletRequest request) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            String key = entry.getKey();
            if ("connector_id".equalsIgnoreCase(key) || "company".equalsIgnoreCase(key)) {
                continue;
            }
            String[] values = entry.getValue();
            if (values == null || values.length == 0) {
                appendQueryParam(builder, key, "");
                continue;
            }
            for (String value : values) {
                appendQueryParam(builder, key, value);
            }
        }
        return builder.toString();
    }

    private String buildForwardedQueryString(String actionName) {
        String trimmedAction = trimToNull(actionName);
        if (trimmedAction == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendQueryParam(builder, "action", trimmedAction);
        return builder.toString();
    }

    private void appendQueryParam(StringBuilder builder, String key, String value) {
        if (builder.length() > 0) {
            builder.append('&');
        }
        builder.append(urlEncode(key)).append('=').append(urlEncode(value == null ? "" : value));
    }

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException("UTF-8 encoding is not available.", ex);
        }
    }

    private String summarizeFailure(HttpStatus status, String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return status == null ? "Unknown connector failure." : "Connector returned " + status.value();
        }
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            String reason = node.path("reason").asText("");
            if (!reason.trim().isEmpty()) {
                return reason;
            }
            String detail = node.path("detail").asText("");
            if (!detail.trim().isEmpty()) {
                return detail;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return responseBody;
    }

    private String deriveCompanyFromBody(String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode vouchers = node.path("VOUCHER");
            if (vouchers.isArray() && vouchers.size() > 0) {
                String company = trimToNull(vouchers.get(0).path("COMPANY").asText(null));
                if (company != null) {
                    return company;
                }
            }
            JsonNode voucher = node.path("voucher");
            String company = trimToNull(voucher.path("COMPANY").asText(null));
            if (company != null) {
                return company;
            }
        } catch (Exception ignored) {
            // Best effort only.
        }
        return null;
    }

    private OfflineVoucherRewriteResult rewriteOfflineVoucherRequest(String connectorPath, String contentType, String body) {
        String normalizedPath = trimToNull(connectorPath);
        String normalizedContentType = trimToNull(contentType);
        if (normalizedPath == null
                || !normalizedPath.toLowerCase().startsWith("/vouchers")
                || normalizedContentType == null
                || !normalizedContentType.toLowerCase().contains("json")
                || body == null
                || body.trim().isEmpty()) {
            return new OfflineVoucherRewriteResult(body, null, null);
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode voucher = firstVoucherNode(root);
            if (voucher == null || !voucher.isObject()) {
                return new OfflineVoucherRewriteResult(body, null, null);
            }

            String currentVoucherNumber = trimToNull(voucher.path("VOUCHERNUMBER").asText(null));
            String originalVoucherNumber = resolveOriginalVoucherNumber(voucher, currentVoucherNumber);
            if (currentVoucherNumber != null && currentVoucherNumber.toUpperCase().startsWith("OFF-")) {
                return new OfflineVoucherRewriteResult(body, currentVoucherNumber, originalVoucherNumber);
            }

            String voucherType = trimToNull(voucher.path("VOUCHERTYPENAME").asText(null));
            if (voucherType == null) {
                voucherType = inferVoucherTypeFromPath(normalizedPath);
            }
            String voucherDate = trimToNull(firstNonBlank(
                    trimToNull(voucher.path("DATE").asText(null)),
                    trimToNull(voucher.path("EFFECTIVEDATE").asText(null))
            ));
            String generatedVoucherNumber = buildOfflineVoucherNumber(voucherType, voucherDate);
            return new OfflineVoucherRewriteResult(
                    body,
                    generatedVoucherNumber,
                    originalVoucherNumber
            );
        } catch (Exception ignored) {
            return new OfflineVoucherRewriteResult(body, null, null);
        }
    }

    private String resolveOriginalVoucherNumber(JsonNode voucher, String currentVoucherNumber) {
        if (voucher == null || voucher.isNull()) {
            return currentVoucherNumber;
        }
        String originalVoucherNumber = firstNonBlank(
                trimToNull(voucher.path("ORIGINALVOUCHERNUMBER").asText(null)),
                trimToNull(voucher.path("originalVoucherNumber").asText(null)),
                trimToNull(voucher.path("original_voucher_number").asText(null))
        );
        if (originalVoucherNumber != null) {
            return originalVoucherNumber;
        }
        String currentReference = trimToNull(voucher.path("REFERENCE").asText(null));
        if (currentVoucherNumber != null && currentVoucherNumber.toUpperCase().startsWith("OFF-")) {
            return firstNonBlank(nonOfflineValue(currentReference), nonOfflineValue(currentVoucherNumber));
        }
        return firstNonBlank(nonOfflineValue(currentVoucherNumber), nonOfflineValue(currentReference), currentVoucherNumber, currentReference);
    }

    private String nonOfflineValue(String value) {
        String text = trimToNull(value);
        if (text == null || text.toUpperCase().startsWith("OFF-")) {
            return null;
        }
        return text;
    }

    private JsonNode firstVoucherNode(JsonNode root) {
        if (root == null || root.isNull()) {
            return null;
        }
        JsonNode vouchers = root.get("VOUCHER");
        if (vouchers != null) {
            if (vouchers.isArray() && vouchers.size() > 0) {
                return vouchers.get(0);
            }
            if (vouchers.isObject()) {
                return vouchers;
            }
        }
        JsonNode voucher = root.get("voucher");
        if (voucher != null && voucher.isObject()) {
            return voucher;
        }
        return null;
    }

    private String inferVoucherTypeFromPath(String connectorPath) {
        if (connectorPath == null) {
            return "Voucher";
        }
        String path = connectorPath.startsWith("/") ? connectorPath.substring(1) : connectorPath;
        String[] parts = path.split("/");
        if (parts.length < 2) {
            return "Voucher";
        }
        String rawType = parts[1].replace('-', ' ').trim();
        if (rawType.isEmpty()) {
            return "Voucher";
        }
        return rawType;
    }

    private String buildOfflineVoucherNumber(String voucherType, String voucherDate) {
        String typeCode = "VCH";
        String normalizedType = trimToNull(voucherType);
        if (normalizedType != null) {
            String compact = normalizedType.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
            if (!compact.isEmpty()) {
                typeCode = compact.substring(0, Math.min(3, compact.length()));
            }
        }
        String dateCode = trimToNull(voucherDate);
        if (dateCode == null || !dateCode.matches("\\d{8}")) {
            dateCode = java.time.format.DateTimeFormatter.BASIC_ISO_DATE.format(java.time.LocalDate.now());
        }
        long suffix = System.currentTimeMillis() % 1000000L;
        return String.format("OFF-%s-%s-%06d", typeCode, dateCode, suffix);
    }

    private Long insertEntryWithDuplicateGuard(TallyVoucherWriteQueueEntry entry) {
        try {
            return repository.insert(entry);
        } catch (DuplicateKeyException ex) {
            VoucherDuplicateCheck duplicateCheck = buildVoucherDuplicateCheck(
                    entry.getConnectorPath(),
                    entry.getCompany(),
                    entry.getRequestBody()
            );
            if (duplicateCheck != null) {
                TallyVoucherWriteQueueEntry existing = repository.findVoucherNumberDuplicate(
                        duplicateCheck.company,
                        duplicateCheck.connectorPath,
                        duplicateCheck.voucherNumber
                );
                if (existing != null) {
                    throw duplicateVoucherNumberConflict(duplicateCheck, existing);
                }
            }
            throw ex;
        }
    }

    private DuplicateVoucherNumberException duplicateVoucherNumberConflict(VoucherDuplicateCheck duplicateCheck,
                                                                           TallyVoucherWriteQueueEntry existing) {
        return new DuplicateVoucherNumberException(
                duplicateCheck.company,
                duplicateCheck.connectorPath,
                duplicateCheck.voucherNumber,
                existing.getId(),
                existing.getStatus(),
                existing.getReviewState()
        );
    }

    private VoucherDuplicateCheck buildVoucherDuplicateCheck(String connectorPath, String company, String body) {
        String normalizedPath = trimToNull(connectorPath);
        if (normalizedPath == null || !normalizedPath.toLowerCase().startsWith("/vouchers")) {
            return null;
        }
        String voucherNumber = extractBusinessVoucherNumber(body);
        if (voucherNumber == null) {
            return null;
        }
        return new VoucherDuplicateCheck(normalizedPath, trimToNull(company), voucherNumber);
    }

    private String extractBusinessVoucherNumber(String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode voucher = firstVoucherNode(root);
            if (voucher == null || !voucher.isObject()) {
                return null;
            }
            return resolveOriginalVoucherNumber(
                    voucher,
                    trimToNull(voucher.path("VOUCHERNUMBER").asText(null))
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private static final class VoucherDuplicateCheck {
        private final String connectorPath;
        private final String company;
        private final String voucherNumber;

        private VoucherDuplicateCheck(String connectorPath, String company, String voucherNumber) {
            this.connectorPath = connectorPath;
            this.company = company;
            this.voucherNumber = voucherNumber;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String text = trimToNull(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    public boolean replayNow(TallyVoucherWriteQueueEntry entry) {
        return replayEntry(entry);
    }

    private List<String> parseCleanupStatuses(String statuses) {
        Set<String> normalized = new LinkedHashSet<String>();
        if (trimToNull(statuses) == null) {
            normalized.add("FAILED");
        } else {
            for (String rawStatus : statuses.split(",")) {
                String status = normalizeStatus(rawStatus);
                if (status == null) {
                    continue;
                }
                if (!"FAILED".equals(status) && !"RETRY".equals(status)) {
                    throw new IllegalArgumentException("Cleanup only supports FAILED and RETRY statuses.");
                }
                normalized.add(status);
            }
        }
        if (normalized.isEmpty()) {
            normalized.add("FAILED");
        }
        return new ArrayList<String>(normalized);
    }

    private String normalizeStatus(String status) {
        String text = trimToNull(status);
        return text == null ? null : text.toUpperCase();
    }

    private static class OfflineVoucherRewriteResult {
        private final String requestBody;
        private final String offlineVoucherNumber;
        private final String originalVoucherNumber;

        private OfflineVoucherRewriteResult(String requestBody, String offlineVoucherNumber, String originalVoucherNumber) {
            this.requestBody = requestBody;
            this.offlineVoucherNumber = offlineVoucherNumber;
            this.originalVoucherNumber = originalVoucherNumber;
        }
    }
}
