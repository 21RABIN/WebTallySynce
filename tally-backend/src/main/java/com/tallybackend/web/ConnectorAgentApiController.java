package com.tallybackend.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tallybackend.cache.TallyCacheDataset;
import com.tallybackend.cache.TallyCacheSnapshotRepository;
import com.tallybackend.service.ConnectorRegistration;
import com.tallybackend.service.ConnectorRegistryService;
import com.tallybackend.service.TallyVoucherWriteQueueEntry;
import com.tallybackend.service.TallyVoucherWriteQueueRepository;
import com.tallybackend.service.WritePayloadTransformer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
public class ConnectorAgentApiController {

    private final ConnectorRegistryService connectorRegistryService;
    private final TallyVoucherWriteQueueRepository queueRepository;
    private final WritePayloadTransformer writePayloadTransformer;
    private final TallyCacheSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final String serverAgentKey;
    private final long defaultLeaseSeconds;

    public ConnectorAgentApiController(ConnectorRegistryService connectorRegistryService,
                                       TallyVoucherWriteQueueRepository queueRepository,
                                       WritePayloadTransformer writePayloadTransformer,
                                       TallyCacheSnapshotRepository snapshotRepository,
                                       ObjectMapper objectMapper,
                                       @Value("${ingest.agent-key:local-dev-key}") String serverAgentKey,
                                       @Value("${connector.agent.job-lease-seconds:120}") long defaultLeaseSeconds) {
        this.connectorRegistryService = connectorRegistryService;
        this.queueRepository = queueRepository;
        this.writePayloadTransformer = writePayloadTransformer;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
        this.serverAgentKey = serverAgentKey;
        this.defaultLeaseSeconds = defaultLeaseSeconds;
    }

    @PostMapping("/api/connectors/register")
    public ResponseEntity<?> register(@RequestHeader(value = "X-AGENT-KEY", required = false) String agentKey,
                                      @RequestBody Map<String, Object> payload) {
        ResponseEntity<?> unauthorized = requireAgent(agentKey);
        if (unauthorized != null) {
            return unauthorized;
        }
        ConnectorRegistration registration = registrationFromPayload(payload, true);
        try {
            ConnectorRegistration saved = connectorRegistryService.register(registration);
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("message", "Connector registered successfully");
            response.put("connector", saved);
            response.put("server_time", Instant.now().toString());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error(ex.getMessage()));
        }
    }

    @PostMapping("/api/connectors/heartbeat")
    public ResponseEntity<?> heartbeat(@RequestHeader(value = "X-AGENT-KEY", required = false) String agentKey,
                                       @RequestBody Map<String, Object> payload) {
        ResponseEntity<?> unauthorized = requireAgent(agentKey);
        if (unauthorized != null) {
            return unauthorized;
        }
        ConnectorRegistration saved = connectorRegistryService.register(registrationFromPayload(payload, false));
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("status", "ok");
        response.put("connector", saved);
        response.put("server_time", Instant.now().toString());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/connector/jobs")
    public ResponseEntity<?> jobs(@RequestHeader(value = "X-AGENT-KEY", required = false) String agentKey,
                                  @RequestParam(value = "connector_id", required = false) String connectorId,
                                  @RequestParam(value = "company", required = false) String company,
                                  @RequestParam(value = "limit", required = false, defaultValue = "10") int limit,
                                  @RequestParam(value = "lease_seconds", required = false) Long leaseSeconds) {
        ResponseEntity<?> unauthorized = requireAgent(agentKey);
        if (unauthorized != null) {
            return unauthorized;
        }
        String normalizedConnectorId = trimToNull(connectorId);
        if (normalizedConnectorId == null) {
            return ResponseEntity.badRequest().body(error("connector_id is required"));
        }
        Instant now = Instant.now();
        Instant leaseUntil = now.plusSeconds(Math.max(30L, leaseSeconds == null ? defaultLeaseSeconds : leaseSeconds.longValue()));
        List<TallyVoucherWriteQueueEntry> candidates = queueRepository.findDueConnectorEntries(
                normalizedConnectorId,
                trimToNull(company),
                Math.min(Math.max(limit, 1), 50),
                now
        );
        List<Map<String, Object>> jobs = new ArrayList<Map<String, Object>>();
        for (TallyVoucherWriteQueueEntry entry : candidates) {
            if (queueRepository.leaseForConnector(entry.getId(), normalizedConnectorId, now, leaseUntil) <= 0) {
                continue;
            }
            TallyVoucherWriteQueueEntry leased = first(queueRepository.findByIds(Collections.singletonList(entry.getId())));
            if (leased != null) {
                jobs.add(jobPayload(leased, leaseUntil));
            }
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("connector_id", normalizedConnectorId);
        response.put("company", trimToNull(company));
        response.put("jobs", jobs);
        response.put("server_time", now.toString());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/connector/jobs/{queueId}/result")
    public ResponseEntity<?> jobResult(@RequestHeader(value = "X-AGENT-KEY", required = false) String agentKey,
                                       @PathVariable Long queueId,
                                       @RequestBody Map<String, Object> payload) {
        ResponseEntity<?> unauthorized = requireAgent(agentKey);
        if (unauthorized != null) {
            return unauthorized;
        }
        TallyVoucherWriteQueueEntry entry = first(queueRepository.findByIds(Collections.singletonList(queueId)));
        if (entry == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Queue job not found: " + queueId));
        }
        String connectorId = trimToNull(text(payload.get("connector_id")));
        if (connectorId == null) {
            return ResponseEntity.badRequest().body(error("connector_id is required"));
        }
        if (entry.getLeasedBy() != null && !connectorId.equalsIgnoreCase(entry.getLeasedBy())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error("Job is leased by another connector"));
        }
        String status = normalizeResultStatus(text(payload.get("status")));
        Instant now = Instant.now();
        int attempts = entry.getAttempts() + 1;
        String responseBody = jsonOrText(payload.get("response_body"));
        String errorMessage = text(payload.get("error"));
        if ("APPLIED".equals(status)) {
            queueRepository.markApplied(queueId, attempts, responseBody, now);
        } else if ("FAILED".equals(status)) {
            queueRepository.markFailed(queueId, attempts, firstNonBlank(errorMessage, "Connector reported failure."), responseBody, now);
        } else {
            long retryAfterMs = longValue(payload.get("retry_after_ms"), 60000L);
            queueRepository.markRetry(queueId, attempts, firstNonBlank(errorMessage, "Connector requested retry."), responseBody, now, now.plusMillis(Math.max(1000L, retryAfterMs)));
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("queue_id", queueId);
        response.put("status", status);
        response.put("server_time", now.toString());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/connector/snapshots")
    public ResponseEntity<?> uploadSnapshot(@RequestHeader(value = "X-AGENT-KEY", required = false) String agentKey,
                                            @RequestBody Map<String, Object> payload) {
        ResponseEntity<?> unauthorized = requireAgent(agentKey);
        if (unauthorized != null) {
            return unauthorized;
        }
        TallyCacheDataset dataset = TallyCacheDataset.fromKey(text(payload.get("dataset_key")));
        if (dataset == null) {
            return ResponseEntity.badRequest().body(error("dataset_key is invalid or missing"));
        }
        String company = trimToNull(text(payload.get("company")));
        String connectorId = trimToNull(text(payload.get("connector_id")));
        String snapshotKey = firstNonBlank(text(payload.get("snapshot_key")), dataset.getKey() + ":" + firstNonBlank(company, "default"));
        List<String> rows = rowsJson(payload.get("rows"), payload.get("data"));
        Instant now = Instant.now();
        String rowsJson = jsonOrText(rows);
        String contentHash = sha256(rowsJson);
        Long runId = snapshotRepository.createRun("SUCCESS", connectorId, company, now);
        Long snapshotId = snapshotRepository.createSnapshot(
                runId,
                dataset,
                snapshotKey,
                connectorId,
                company,
                jsonOrText(payload.get("request_params")),
                contentHash,
                trimToNull(text(payload.get("range_start"))),
                trimToNull(text(payload.get("range_end"))),
                longValue(payload.get("stale_after_ms"), 300000L),
                now
        );
        snapshotRepository.replaceGenericRows(snapshotId, dataset, snapshotKey, rows, contentHash, now);
        snapshotRepository.completeRun(runId, "SUCCESS", null, now);
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("status", "ok");
        response.put("snapshot_id", snapshotId);
        response.put("dataset_key", dataset.getKey());
        response.put("row_count", rows.size());
        response.put("content_hash", contentHash);
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> jobPayload(TallyVoucherWriteQueueEntry entry, Instant leaseUntil) {
        Map<String, Object> job = new LinkedHashMap<String, Object>();
        HttpMethod method = HttpMethod.resolve(entry.getHttpMethod());
        String transformedBody = writePayloadTransformer.transform(entry.getConnectorPath(), method, entry.getRequestBody());
        job.put("queue_id", entry.getId());
        job.put("connector_path", entry.getConnectorPath());
        job.put("http_method", entry.getHttpMethod());
        job.put("query_string", entry.getQueryString());
        job.put("request_body", transformedBody);
        job.put("content_type", firstNonBlank(entry.getContentType(), MediaType.APPLICATION_JSON_VALUE));
        job.put("company", entry.getCompany());
        job.put("entity_type", entry.getEntityType());
        job.put("entity_name", entry.getEntityName());
        job.put("action_name", entry.getActionName());
        job.put("attempts", entry.getAttempts());
        job.put("max_attempts", entry.getMaxAttempts());
        job.put("lease_expires_at", leaseUntil.toString());
        return job;
    }

    private ConnectorRegistration registrationFromPayload(Map<String, Object> payload, boolean firstRegistration) {
        ConnectorRegistration registration = new ConnectorRegistration();
        String connectorId = trimToNull(text(payload.get("connector_id")));
        if (connectorId == null) {
            connectorId = trimToNull(text(payload.get("connectorId")));
        }
        registration.setConnectorId(connectorId);
        registration.setBaseUrl(firstNonBlank(text(payload.get("base_url")), text(payload.get("baseUrl")), "agent://" + firstNonBlank(connectorId, "connector")));
        registration.setAgentKey(null);
        registration.setCompany(trimToNull(text(payload.get("company"))));
        registration.setBranch(trimToNull(text(payload.get("branch"))));
        registration.setDescription(firstNonBlank(
                text(payload.get("description")),
                "Outbound connector agent" + (firstRegistration ? " registration" : " heartbeat")
        ));
        registration.setActive(true);
        return registration;
    }

    private ResponseEntity<?> requireAgent(String agentKey) {
        String configured = trimToNull(serverAgentKey);
        String supplied = trimToNull(agentKey);
        if (configured == null || supplied == null || !configured.equals(supplied)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("invalid connector agent key"));
        }
        return null;
    }

    private String normalizeResultStatus(String value) {
        String status = trimToNull(value);
        if (status == null) {
            return "RETRY";
        }
        status = status.toUpperCase(Locale.ROOT);
        if ("SUCCESS".equals(status) || "OK".equals(status)) {
            return "APPLIED";
        }
        if ("APPLIED".equals(status) || "FAILED".equals(status) || "RETRY".equals(status)) {
            return status;
        }
        return "RETRY";
    }

    private List<String> rowsJson(Object rows, Object fallbackData) {
        Object source = rows == null ? fallbackData : rows;
        if (source == null) {
            return Collections.emptyList();
        }
        List<String> output = new ArrayList<String>();
        JsonNode node = objectMapper.valueToTree(source);
        if (node.isArray()) {
            for (JsonNode item : node) {
                output.add(jsonOrText(item));
            }
        } else if (node.isObject()) {
            JsonNode voucher = node.get("VOUCHER");
            if (voucher != null && voucher.isArray()) {
                for (JsonNode item : voucher) {
                    output.add(jsonOrText(item));
                }
            } else {
                output.add(jsonOrText(node));
            }
        } else {
            output.add(jsonOrText(node));
        }
        return output;
    }

    private TallyVoucherWriteQueueEntry first(List<TallyVoucherWriteQueueEntry> entries) {
        return entries == null || entries.isEmpty() ? null : entries.get(0);
    }

    private Map<String, String> error(String message) {
        Map<String, String> response = new LinkedHashMap<String, String>();
        response.put("error", message);
        return response;
    }

    private String jsonOrText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private long longValue(Object value, long defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return value == null ? defaultValue : Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
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
}
