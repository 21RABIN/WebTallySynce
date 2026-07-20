package com.tallybackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tallybackend.cache.TallyCacheDataset;
import com.tallybackend.cache.TallyCacheProperties;
import com.tallybackend.cache.TallyCacheReadService;
import com.tallybackend.cache.TallyCacheRowRepository;
import com.tallybackend.cache.TallyCacheSnapshotRepository;
import com.tallybackend.cache.TallyDatasetSnapshot;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TallyReconciliationService {

    private static final int PREVIEW_LIMIT = 500;
    private static final Set<String> RECENT_TALLY_DATASET_KEYS = new LinkedHashSet<String>(Arrays.asList(
            "group", "ledger", "uom", "stock_group", "stock_item", "company_currency", "company_features"
    ));

    private final TallyVoucherWriteQueueRepository queueRepository;
    private final TallyVoucherWriteQueueService queueService;
    private final TallyQueueEntityMetadataService metadataService;
    private final TallyCacheReadService cacheReadService;
    private final TallyCacheSnapshotRepository snapshotRepository;
    private final TallyCacheRowRepository rowRepository;
    private final TallyCacheProperties cacheProperties;
    private final ObjectMapper objectMapper;
    private final Map<String, CompanyReconciliationState> stateByCompany = new ConcurrentHashMap<String, CompanyReconciliationState>();

    public TallyReconciliationService(TallyVoucherWriteQueueRepository queueRepository,
                                      TallyVoucherWriteQueueService queueService,
                                      TallyQueueEntityMetadataService metadataService,
                                      TallyCacheReadService cacheReadService,
                                      TallyCacheSnapshotRepository snapshotRepository,
                                      TallyCacheRowRepository rowRepository,
                                      TallyCacheProperties cacheProperties,
                                      ObjectMapper objectMapper) {
        this.queueRepository = queueRepository;
        this.queueService = queueService;
        this.metadataService = metadataService;
        this.cacheReadService = cacheReadService;
        this.snapshotRepository = snapshotRepository;
        this.rowRepository = rowRepository;
        this.cacheProperties = cacheProperties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> status(String company) {
        String normalizedCompany = normalizeCompany(company);
        boolean online = isOnline(normalizedCompany);
        CompanyReconciliationState state = stateFor(normalizedCompany);
        boolean transitionedOnline = false;
        if (!online) {
            state.online = false;
            state.dismissedSignature = null;
        } else if (!state.online) {
            state.online = true;
            transitionedOnline = true;
            cacheReadService.triggerSync();
        }

        Map<String, Object> preview = preview(normalizedCompany, false);
        String signature = text(preview.get("signature"));
        boolean hasReviewItems = number(preview.get("pending_review_count")) > 0;
        boolean reviewRequired = online && hasReviewItems && !signature.equals(state.dismissedSignature);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("company", normalizedCompany);
        response.put("online", online);
        response.put("transitioned_online", transitionedOnline);
        response.put("review_required", reviewRequired);
        response.put("pending_review_count", preview.get("pending_review_count"));
        response.put("conflict_count", preview.get("conflict_count"));
        response.put("signature", signature);
        return response;
    }

    public Map<String, Object> preview(String company) {
        return preview(company, true);
    }

    private Map<String, Object> preview(String company, boolean persistMetadata) {
        String normalizedCompany = normalizeCompany(company);
        List<TallyVoucherWriteQueueEntry> entries = queueRepository.listReviewableByCompany(normalizedCompany, PREVIEW_LIMIT);
        List<Map<String, Object>> pendingDbToTally = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> alreadyPresentInTally = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> possibleConflicts = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> failedReplayItems = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> recentTallyChanges = recentTallyChanges(normalizedCompany);
        Map<String, Integer> countsByEntityType = new LinkedHashMap<String, Integer>();

        for (TallyVoucherWriteQueueEntry entry : entries) {
            metadataService.enrichEntry(entry);
            Map<String, Object> tallyMatch = findTallyMatch(entry, normalizedCompany);
            MatchOutcome outcome = determineMatchOutcome(entry, tallyMatch);
            if (persistMetadata) {
                queueRepository.updateMetadata(
                        entry.getId(),
                        entry.getEntityType(),
                        entry.getEntityName(),
                        entry.getSyncDirection(),
                        outcome.conflictState,
                        outcome.conflictPayload,
                        normalizeReviewState(entry, outcome),
                        entry.getReviewedBy(),
                        entry.getReviewedAt()
                );
            }

            Map<String, Object> item = previewItem(entry, tallyMatch, outcome);
            incrementCount(countsByEntityType, entry.getEntityType());
            if ("already_present_in_tally".equals(outcome.bucket)) {
                alreadyPresentInTally.add(item);
            } else if ("failed_replay".equals(outcome.bucket)) {
                failedReplayItems.add(item);
            } else if ("possible_conflicts".equals(outcome.bucket)) {
                possibleConflicts.add(item);
            } else if ("pending_db_to_tally".equals(outcome.bucket)) {
                pendingDbToTally.add(item);
            }
        }

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("company", normalizedCompany);
        response.put("pending_db_to_tally", pendingDbToTally);
        response.put("already_present_in_tally", alreadyPresentInTally);
        response.put("possible_conflicts", possibleConflicts);
        response.put("failed_replay_items", failedReplayItems);
        response.put("recent_tally_changes_visible_after_reconnect", recentTallyChanges);
        response.put("counts_by_entity_type", countsByEntityType);
        response.put("pending_review_count", pendingDbToTally.size() + possibleConflicts.size() + alreadyPresentInTally.size() + failedReplayItems.size());
        response.put("conflict_count", possibleConflicts.size());
        response.put("already_present_count", alreadyPresentInTally.size());
        response.put("failed_replay_count", failedReplayItems.size());
        response.put("recent_tally_changes_count", recentTallyChanges.size());
        response.put("signature", signatureFor(response));
        return response;
    }

    public Map<String, Object> resolve(String company, List<Map<String, Object>> resolutions, String reviewedBy) {
        String normalizedCompany = normalizeCompany(company);
        int updated = 0;
        Instant reviewedAt = Instant.now();
        if (resolutions != null) {
            for (Map<String, Object> resolution : resolutions) {
                Long queueId = longValue(resolution.get("queue_id"));
                String action = text(resolution.get("action"));
                if (queueId == null || action.isEmpty()) {
                    continue;
                }
                TallyVoucherWriteQueueEntry entry = first(queueRepository.findByIds(Collections.singletonList(queueId)));
                if (entry == null || !sameCompany(entry.getCompany(), normalizedCompany)) {
                    continue;
                }
                if ("USE_DB_AND_SYNC_TO_TALLY".equals(action)) {
                    queueRepository.updateReconciliationState(queueId, "NONE", entry.getConflictPayload(), "APPROVED_SYNC", reviewedBy, reviewedAt);
                    updated++;
                } else if ("MARK_AS_ALREADY_SYNCED".equals(action)) {
                    queueRepository.updateReconciliationState(queueId, "NONE", entry.getConflictPayload(), "APPROVED_MARK_SYNCED", reviewedBy, reviewedAt);
                    updated++;
                } else if ("SKIP_FOR_NOW".equals(action)) {
                    queueRepository.updateReconciliationState(queueId, "CONFLICT", entry.getConflictPayload(), "SKIPPED", reviewedBy, reviewedAt);
                    updated++;
                }
            }
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("updated", updated);
        response.put("company", normalizedCompany);
        return response;
    }

    public Map<String, Object> sync(String company, List<Map<String, Object>> items, String reviewedBy) {
        String normalizedCompany = normalizeCompany(company);
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        int synced = 0;
        int markedSynced = 0;
        int skipped = 0;
        Instant now = Instant.now();

        if (items != null) {
            for (Map<String, Object> item : items) {
                Long queueId = longValue(item.get("queue_id"));
                String action = text(item.get("action"));
                if (queueId == null || action.isEmpty()) {
                    continue;
                }
                TallyVoucherWriteQueueEntry entry = first(queueRepository.findByIds(Collections.singletonList(queueId)));
                if (entry == null || !sameCompany(entry.getCompany(), normalizedCompany)) {
                    continue;
                }
                metadataService.enrichEntry(entry);
                if ("USE_DB_AND_SYNC_TO_TALLY".equals(action)) {
                    queueRepository.updateReconciliationState(queueId, "NONE", entry.getConflictPayload(), "APPROVED_SYNC", reviewedBy, now);
                    TallyVoucherWriteQueueEntry refreshed;
                    if (queueService.isDirectReplayEnabled()) {
                        queueService.replayNow(entry);
                        refreshed = first(queueRepository.findByIds(Collections.singletonList(queueId)));
                        if (isApplied(refreshed)) {
                            synced++;
                        }
                        results.add(syncResult(refreshed == null ? entry : refreshed));
                    } else {
                        refreshed = first(queueRepository.findByIds(Collections.singletonList(queueId)));
                        results.add(syncResult(refreshed == null ? entry : refreshed, "QUEUED_FOR_CONNECTOR_AGENT"));
                    }
                } else if ("MARK_AS_ALREADY_SYNCED".equals(action)) {
                    queueRepository.updateReconciliationState(queueId, "NONE", entry.getConflictPayload(), "SYNCED", reviewedBy, now);
                    queueRepository.markApplied(queueId, Math.max(1, entry.getAttempts()), "{\"reconciliation\":\"marked_as_already_synced\"}", now);
                    markedSynced++;
                    TallyVoucherWriteQueueEntry refreshed = first(queueRepository.findByIds(Collections.singletonList(queueId)));
                    results.add(syncResult(refreshed == null ? entry : refreshed, "MARKED_AS_ALREADY_SYNCED"));
                } else if ("SKIP_FOR_NOW".equals(action)) {
                    queueRepository.updateReconciliationState(queueId, "CONFLICT", entry.getConflictPayload(), "SKIPPED", reviewedBy, now);
                    skipped++;
                    TallyVoucherWriteQueueEntry refreshed = first(queueRepository.findByIds(Collections.singletonList(queueId)));
                    results.add(syncResult(refreshed == null ? entry : refreshed, "SKIPPED"));
                }
            }
        }

        cacheReadService.triggerSync();
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("company", normalizedCompany);
        response.put("synced", synced);
        response.put("marked_as_already_synced", markedSynced);
        response.put("skipped", skipped);
        response.put("results", results);
        response.put("preview", preview(normalizedCompany, true));
        return response;
    }

    public Map<String, Object> dismiss(String company) {
        String normalizedCompany = normalizeCompany(company);
        Map<String, Object> preview = preview(normalizedCompany, false);
        CompanyReconciliationState state = stateFor(normalizedCompany);
        state.dismissedSignature = text(preview.get("signature"));
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("company", normalizedCompany);
        response.put("dismissed", true);
        response.put("signature", state.dismissedSignature);
        return response;
    }

    private Map<String, Object> syncResult(TallyVoucherWriteQueueEntry entry) {
        return syncResult(entry, text(entry == null ? null : entry.getStatus()));
    }

    private Map<String, Object> syncResult(TallyVoucherWriteQueueEntry entry, String resultStatus) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("queue_id", entry == null ? null : entry.getId());
        item.put("entity_type", entry == null ? null : entry.getEntityType());
        item.put("entity_name", entry == null ? null : entry.getEntityName());
        item.put("status", resultStatus);
        item.put("queue_status", entry == null ? null : entry.getStatus());
        item.put("review_state", entry == null ? null : entry.getReviewState());
        item.put("last_error", entry == null ? null : entry.getLastError());
        return item;
    }

    private String normalizeReviewState(TallyVoucherWriteQueueEntry entry, MatchOutcome outcome) {
        String current = text(entry.getReviewState());
        if (current.isEmpty() || "PENDING_REVIEW".equals(current)) {
            return "possible_conflicts".equals(outcome.bucket) ? "PENDING_REVIEW" : current.isEmpty() ? "PENDING_REVIEW" : current;
        }
        return current;
    }

    private Map<String, Object> previewItem(TallyVoucherWriteQueueEntry entry, Map<String, Object> tallyMatch, MatchOutcome outcome) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("queue_id", entry.getId());
        item.put("entity_type", entry.getEntityType());
        item.put("entity_name", entry.getEntityName());
        item.put("company", entry.getCompany());
        item.put("status", entry.getStatus());
        item.put("sync_direction", entry.getSyncDirection());
        item.put("conflict_state", outcome.conflictState);
        item.put("review_state", entry.getReviewState());
        item.put("queue_status", entry.getStatus());
        item.put("attempts", entry.getAttempts());
        item.put("max_attempts", entry.getMaxAttempts());
        item.put("last_error", entry.getLastError());
        item.put("response_body", readJsonOrText(entry.getResponseBody()));
        item.put("created_at", entry.getCreatedAt());
        item.put("updated_at", entry.getUpdatedAt());
        item.put("last_attempt_at", entry.getLastAttemptAt());
        item.put("next_attempt_at", entry.getNextAttemptAt());
        item.put("completed_at", entry.getCompletedAt());
        item.put("waiting_for_approval", waitingForApproval(entry));
        item.put("blocked_by_replay_failure", "FAILED".equalsIgnoreCase(text(entry.getStatus())));
        item.put("preview_fields", entry.getPreviewFields());
        item.put("tally_preview_fields", tallyMatch == null ? Collections.emptyMap() : tallyMatch);
        item.put("recommended_action", outcome.recommendedAction);
        item.put("default_selected_action", outcome.recommendedAction);
        item.put("bucket", outcome.bucket);
        return item;
    }

    private MatchOutcome determineMatchOutcome(TallyVoucherWriteQueueEntry entry, Map<String, Object> tallyMatch) {
        MatchOutcome outcome = new MatchOutcome();
        if ("APPLIED".equalsIgnoreCase(text(entry.getStatus()))) {
            outcome.bucket = "already_present_in_tally";
            outcome.conflictState = "NONE";
            outcome.recommendedAction = "MARK_AS_ALREADY_SYNCED";
            outcome.conflictPayload = null;
            return outcome;
        }
        boolean failedReplay = "FAILED".equalsIgnoreCase(text(entry.getStatus()));
        if (tallyMatch == null || tallyMatch.isEmpty()) {
            outcome.bucket = failedReplay ? "failed_replay" : "pending_db_to_tally";
            outcome.conflictState = "NONE";
            outcome.recommendedAction = "USE_DB_AND_SYNC_TO_TALLY";
            outcome.conflictPayload = null;
            return outcome;
        }
        if (previewEquals(entry.getPreviewFields(), tallyMatch)) {
            outcome.bucket = "already_present_in_tally";
            outcome.conflictState = "POSSIBLE_DUPLICATE";
            outcome.recommendedAction = "MARK_AS_ALREADY_SYNCED";
            outcome.conflictPayload = writeJson(tallyMatch);
            return outcome;
        }
        if (failedReplay) {
            outcome.bucket = "failed_replay";
            outcome.conflictState = "CONFLICT";
            outcome.recommendedAction = "USE_DB_AND_SYNC_TO_TALLY";
            outcome.conflictPayload = writeJson(tallyMatch);
            return outcome;
        }
        outcome.bucket = "possible_conflicts";
        outcome.conflictState = "CONFLICT";
        outcome.recommendedAction = "SKIP_FOR_NOW";
        outcome.conflictPayload = writeJson(tallyMatch);
        return outcome;
    }

    private Map<String, Object> findTallyMatch(TallyVoucherWriteQueueEntry entry, String company) {
        if ("voucher".equals(entry.getEntityType())) {
            return findVoucherMatch(entry, company);
        }
        List<Map<String, Object>> rows = rowsForEntityType(entry.getEntityType(), company);
        String name = normalizeName(firstNonBlank(entry.getEntityName(), text(entry.getPreviewFields().get("NAME"))));
        if (name.isEmpty()) {
            return Collections.emptyMap();
        }
        for (Map<String, Object> row : rows) {
            if (name.equals(normalizeName(firstNonBlank(text(row.get("NAME")), text(row.get("name")))))) {
                return row;
            }
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> findVoucherMatch(TallyVoucherWriteQueueEntry entry, String company) {
        Map<String, Object> preview = entry.getPreviewFields();
        String fromDate = cacheProperties.currentFinancialYearStart();
        String toDate = cacheProperties.today();
        Map<String, Object> response = cacheReadService.dayBook(fromDate, toDate, company);
        Object data = response.get("data");
        if (!(data instanceof List)) {
            return Collections.emptyMap();
        }
        String voucherType = normalizeName(text(preview.get("VOUCHERTYPENAME")));
        String voucherNumber = normalizeName(text(preview.get("VOUCHERNUMBER")));
        String date = normalizeName(text(preview.get("DATE")));
        for (Object item : (List<?>) data) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) item;
            if (isSyntheticQueueRow(row, entry)) {
                continue;
            }
            if (voucherType.equals(normalizeName(text(row.get("voucherType"))))
                    && voucherNumber.equals(normalizeName(text(row.get("voucherNumber"))))
                    && date.equals(normalizeName(text(row.get("date"))))) {
                Map<String, Object> match = new LinkedHashMap<String, Object>();
                match.put("VOUCHERTYPENAME", row.get("voucherType"));
                match.put("VOUCHERNUMBER", row.get("voucherNumber"));
                match.put("DATE", row.get("date"));
                match.put("PARTYLEDGERNAME", row.get("partyLedger"));
                match.put("AMOUNT", row.get("amount"));
                return match;
            }
        }
        return Collections.emptyMap();
    }

    private List<Map<String, Object>> rowsForEntityType(String entityType, String company) {
        Map<String, Object> response;
        if ("group".equals(entityType)) {
            response = cacheReadService.groups(company);
        } else if ("ledger".equals(entityType)) {
            response = cacheReadService.ledgers(company);
        } else if ("uom".equals(entityType)) {
            response = cacheReadService.uoms(company);
        } else if ("stock_group".equals(entityType)) {
            response = cacheReadService.stockGroups(company);
        } else if ("stock_item".equals(entityType)) {
            response = cacheReadService.stockItems(company);
        } else if ("company_currency".equals(entityType)) {
            response = cacheReadService.companyCurrency(company);
        } else if ("company_features".equals(entityType)) {
            response = cacheReadService.companyFeatures(company);
        } else {
            return Collections.emptyList();
        }
        Object data = response.get("data");
        if (!(data instanceof List)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : (List<Map<String, Object>>) data) {
            if (row == null || isSyntheticQueueRow(row, null)) {
                continue;
            }
            filtered.add(row);
        }
        return filtered;
    }

    private List<Map<String, Object>> recentTallyChanges(String company) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (String entityType : RECENT_TALLY_DATASET_KEYS) {
            TallyCacheDataset dataset = datasetForEntityType(entityType);
            if (dataset == null) {
                continue;
            }
            TallyDatasetSnapshot current = snapshotRepository.findCurrent(dataset, snapshotKey(company));
            if (current == null) {
                continue;
            }
            TallyDatasetSnapshot previous = snapshotRepository.findPreviousSuccessfulSnapshot(dataset, snapshotKey(company), current.getId(), company);
            if (previous == null) {
                continue;
            }
            List<Map<String, Object>> currentRows = rowRepository.readRows(dataset, current.getId());
            List<Map<String, Object>> previousRows = rowRepository.readRows(dataset, previous.getId());
            Set<String> previousNames = new LinkedHashSet<String>();
            for (Map<String, Object> row : previousRows) {
                previousNames.add(normalizeName(firstNonBlank(text(row.get("NAME")), text(row.get("name")))));
            }
            for (Map<String, Object> row : currentRows) {
                String name = normalizeName(firstNonBlank(text(row.get("NAME")), text(row.get("name"))));
                if (name.isEmpty() || previousNames.contains(name)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("entity_type", entityType);
                item.put("entity_name", firstNonBlank(text(row.get("NAME")), text(row.get("name"))));
                item.put("preview_fields", row);
                items.add(item);
            }
        }
        return items;
    }

    private String snapshotKey(String company) {
        return company == null || company.trim().isEmpty() ? "default" : "default|company=" + company.trim().toLowerCase(Locale.ROOT);
    }

    private TallyCacheDataset datasetForEntityType(String entityType) {
        if ("group".equals(entityType)) {
            return TallyCacheDataset.GROUPS;
        }
        if ("ledger".equals(entityType)) {
            return TallyCacheDataset.LEDGERS;
        }
        if ("uom".equals(entityType)) {
            return TallyCacheDataset.UOMS;
        }
        if ("stock_group".equals(entityType)) {
            return TallyCacheDataset.STOCK_GROUPS;
        }
        if ("stock_item".equals(entityType)) {
            return TallyCacheDataset.STOCK_ITEMS;
        }
        if ("company_currency".equals(entityType)) {
            return TallyCacheDataset.COMPANY_CURRENCY;
        }
        if ("company_features".equals(entityType)) {
            return TallyCacheDataset.COMPANY_FEATURES;
        }
        return null;
    }

    private boolean previewEquals(Map<String, Object> left, Map<String, Object> right) {
        if (left == null || right == null) {
            return false;
        }
        for (Map.Entry<String, Object> entry : left.entrySet()) {
            String key = entry.getKey();
            String leftValue = normalizeName(text(entry.getValue()));
            Object rightRaw = firstNonNull(right.get(key), right.get(camelKey(key)));
            if (rightRaw == null) {
                continue;
            }
            String rightValue = normalizeName(text(rightRaw));
            if (!leftValue.equals(rightValue)) {
                return false;
            }
        }
        return true;
    }

    private Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private boolean waitingForApproval(TallyVoucherWriteQueueEntry entry) {
        String reviewState = text(entry == null ? null : entry.getReviewState());
        return "PENDING_REVIEW".equalsIgnoreCase(reviewState)
                || "APPROVED_SYNC".equalsIgnoreCase(reviewState);
    }

    private boolean isApplied(TallyVoucherWriteQueueEntry entry) {
        return entry != null && "APPLIED".equalsIgnoreCase(text(entry.getStatus()));
    }

    private Object readJsonOrText(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception ignored) {
            return value;
        }
    }

    private boolean isSyntheticQueueRow(Map<String, Object> row, TallyVoucherWriteQueueEntry entry) {
        if (row == null) {
            return false;
        }
        Long rowQueueId = longValue(row.get("queue_id"));
        if (entry != null && rowQueueId != null && rowQueueId.equals(entry.getId())) {
            return true;
        }
        if (Boolean.TRUE.equals(row.get("pending_sync"))) {
            return true;
        }
        String queueStatus = text(row.get("queue_status"));
        return !queueStatus.isEmpty() && !"APPLIED".equalsIgnoreCase(queueStatus);
    }

    private String camelKey(String key) {
        if ("VOUCHERTYPENAME".equals(key)) {
            return "voucherType";
        }
        if ("VOUCHERNUMBER".equals(key)) {
            return "voucherNumber";
        }
        if ("DATE".equals(key)) {
            return "date";
        }
        if ("PARTYLEDGERNAME".equals(key)) {
            return "partyLedger";
        }
        if ("AMOUNT".equals(key)) {
            return "amount";
        }
        return key;
    }

    private boolean isOnline(String company) {
        Map<String, Object> status = cacheReadService.status(company);
        Object connectorHealth = status.get("connector_health");
        if (!(connectorHealth instanceof Map)) {
            return false;
        }
        Map<String, Object> health = (Map<String, Object>) connectorHealth;
        if (Boolean.TRUE.equals(health.get("online"))) {
            return true;
        }
        return "ok".equalsIgnoreCase(text(health.get("status")))
                || "true".equalsIgnoreCase(text(health.get("reachable")));
    }

    private CompanyReconciliationState stateFor(String company) {
        String key = normalizeCompany(company);
        CompanyReconciliationState state = stateByCompany.get(key);
        if (state == null) {
            state = new CompanyReconciliationState();
            stateByCompany.put(key, state);
        }
        return state;
    }

    private void incrementCount(Map<String, Integer> countsByEntityType, String entityType) {
        String key = entityType == null ? "unknown" : entityType;
        Integer current = countsByEntityType.get(key);
        countsByEntityType.put(key, current == null ? 1 : current.intValue() + 1);
    }

    private String signatureFor(Map<String, Object> preview) {
        try {
            return Integer.toHexString(objectMapper.writeValueAsString(preview).hashCode());
        } catch (Exception ignored) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private String writeJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean sameCompany(String left, String right) {
        return normalizeCompany(left).equals(normalizeCompany(right));
    }

    private String normalizeCompany(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private int number(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(text(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private TallyVoucherWriteQueueEntry first(List<TallyVoucherWriteQueueEntry> entries) {
        return entries == null || entries.isEmpty() ? null : entries.get(0);
    }

    private static class CompanyReconciliationState {
        private boolean online;
        private String dismissedSignature;
    }

    private static class MatchOutcome {
        private String bucket;
        private String conflictState;
        private String conflictPayload;
        private String recommendedAction;
    }
}
