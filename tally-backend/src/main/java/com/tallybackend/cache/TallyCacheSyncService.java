package com.tallybackend.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tallybackend.cache.TallyCacheConnectorClient.ConnectorFetchResult;
import com.tallybackend.service.ConnectorRegistration;
import com.tallybackend.service.ConnectorRegistryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class TallyCacheSyncService {

    private final TallyCacheProperties properties;
    private final TallyCacheConnectorClient connectorClient;
    private final TallyCacheNormalizer normalizer;
    private final TallyCacheSnapshotRepository snapshotRepository;
    private final TallyCacheRowRepository rowRepository;
    private final ConnectorRegistryService connectorRegistryService;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TallyCacheSyncService(TallyCacheProperties properties,
                                 TallyCacheConnectorClient connectorClient,
                                 TallyCacheNormalizer normalizer,
                                 TallyCacheSnapshotRepository snapshotRepository,
                                 TallyCacheRowRepository rowRepository,
                                 ConnectorRegistryService connectorRegistryService,
                                 ObjectMapper objectMapper) {
        this.properties = properties;
        this.connectorClient = connectorClient;
        this.normalizer = normalizer;
        this.snapshotRepository = snapshotRepository;
        this.rowRepository = rowRepository;
        this.connectorRegistryService = connectorRegistryService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${tally.cache.sync.interval-ms:60000}")
    public void runScheduledSync() {
        if (!properties.isEnabled()) {
            return;
        }
        runSyncNow();
    }

    public Map<String, Object> runSyncNow() {
        if (!running.compareAndSet(false, true)) {
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("status", "SKIPPED");
            response.put("reason", "sync_already_running");
            return response;
        }

        Instant startedAt = Instant.now();
        Long runId = null;
        String connectorId = null;
        List<String> errors = new ArrayList<String>();
        try {
            runId = snapshotRepository.createRun("RUNNING", null, properties.getCompany(), startedAt);

            ConnectorFetchResult companyFetchResult = connectorClient.fetch("/reports/companies", noParams(), null);
            List<Map<String, Object>> companyRows = normalizer.companies(companyFetchResult.getBody());
            connectorId = companyFetchResult.getTarget().getConnectorId();
            storeSnapshotRows(
                    runId,
                    TallyCacheDataset.COMPANIES,
                    snapshotKey("default", null),
                    connectorId,
                    null,
                    writeJson(noParams()),
                    null,
                    null,
                    companyRows
            );

            for (String company : companiesToSync(companyRows)) {
                syncCompanyDatasets(runId, company, errors);
            }

            snapshotRepository.completeRun(runId, errors.isEmpty() ? "SUCCESS" : "PARTIAL_SUCCESS", joinErrors(errors), Instant.now());
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("status", errors.isEmpty() ? "SUCCESS" : "PARTIAL_SUCCESS");
            response.put("run_id", runId);
            response.put("connector_id", connectorId);
            response.put("errors", errors);
            return response;
        } catch (Exception ex) {
            if (runId != null) {
                snapshotRepository.completeRun(runId, "FAILED", ex.getMessage(), Instant.now());
            }
            Map<String, Object> response = new LinkedHashMap<String, Object>();
            response.put("status", "FAILED");
            response.put("run_id", runId);
            response.put("reason", ex.getMessage());
            return response;
        } finally {
            running.set(false);
        }
    }

    private void syncCompanyDatasets(Long runId, String company, List<String> errors) {
        String companySnapshotKey = snapshotKey("default", company);
        safeSyncSimple(runId, TallyCacheDataset.GROUPS, companySnapshotKey, "/groups", noParams(), null, null, normalizer::groups, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.LEDGERS, companySnapshotKey, "/ledgers", noParams(), null, null, normalizer::ledgers, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.UOMS, companySnapshotKey, "/uoms", noParams(), null, null, normalizer::uoms, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.CURRENCIES, companySnapshotKey, "/currencies", noParams(), null, null, normalizer::currencies, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.STOCK_GROUPS, companySnapshotKey, "/stock-groups", noParams(), null, null, normalizer::stockGroups, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.STOCK_ITEMS, companySnapshotKey, "/stock-items", noParams(), null, null, normalizer::stockItems, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.COMPANY_CURRENCY, companySnapshotKey, "/settings/company-currency", noParams(), null, null, normalizer::companyCurrency, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.COMPANY_FEATURES, companySnapshotKey, "/settings/company-features", noParams(), null, null, normalizer::companyFeatures, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.GST_REGISTRATION, companySnapshotKey, "/settings/gst-registration", noParams(), null, null, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.NUMBERING_RULES, companySnapshotKey, "/settings/numbering-rules", noParams(), null, null, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.TAX_RATE_TABLES, companySnapshotKey, "/settings/tax-rate-tables", noParams(), null, null, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.PRICE_STRUCTURES, companySnapshotKey, "/settings/price-structures", noParams(), null, null, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.STOCK_CONTROLS, companySnapshotKey, "/settings/stock-controls", noParams(), null, null, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.SECURITY_ROLES, companySnapshotKey, "/settings/security-roles", noParams(), null, null, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.UQC_MAPPINGS, companySnapshotKey, "/settings/uqc-mappings", noParams(), null, null, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.EINVOICE_SETTINGS, companySnapshotKey, "/settings/einvoice", noParams(), null, null, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.EWAYBILL_SETTINGS, companySnapshotKey, "/settings/ewaybill", noParams(), null, null, normalizer::genericRows, company, errors);

        String fyStart = properties.currentFinancialYearStart();
        String today = properties.today();
        safeSyncDayBookAndLedgerVouchers(runId, fyStart, today, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.BALANCE_SHEET, snapshotKey(rangeKey(fyStart, today), company), "/reports/balance-sheet",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::balanceSheet, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.PROFIT_LOSS, snapshotKey(rangeKey(fyStart, today), company), "/reports/profit-loss",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::profitLoss, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.STOCK_SUMMARY, snapshotKey(rangeKey(fyStart, today), company), "/reports/stock-summary",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.OUTSTANDING_RECEIVABLES, snapshotKey(rangeKey(fyStart, today), company), "/reports/outstanding-receivables",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.OUTSTANDING_PAYABLES, snapshotKey(rangeKey(fyStart, today), company), "/reports/outstanding-payables",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.BATCH_AVAILABILITY, snapshotKey(rangeKey(fyStart, today), company), "/reports/batch-availability",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.REPORT_PRICE_LISTS, companySnapshotKey, "/reports/price-lists", noParams(), null, null, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.BANK_RECO_STATUS, snapshotKey(rangeKey(fyStart, today), company), "/reports/bank-reco-status",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.TRIAL_BALANCE, snapshotKey(rangeKey(fyStart, today), company), "/reports/trial-balance",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.CASH_BOOK, snapshotKey(rangeKey(fyStart, today), company), "/reports/cash-book",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.BANK_BOOK, snapshotKey(rangeKey(fyStart, today), company), "/reports/bank-book",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.CASH_FLOW, snapshotKey(rangeKey(fyStart, today), company), "/reports/cash-flow",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.FUNDS_FLOW, snapshotKey(rangeKey(fyStart, today), company), "/reports/funds-flow",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.SALES_REGISTER, snapshotKey(rangeKey(fyStart, today), company), "/reports/sales-register",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.SALES_TREND, snapshotKey(rangeKey(fyStart, today), company), "/reports/sales-trend",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.PURCHASE_REGISTER, snapshotKey(rangeKey(fyStart, today), company), "/reports/purchase-register",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.JOURNAL_REGISTER, snapshotKey(rangeKey(fyStart, today), company), "/reports/journal-register",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.RECEIPT_REGISTER, snapshotKey(rangeKey(fyStart, today), company), "/reports/receipt-register",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.PAYMENT_REGISTER, snapshotKey(rangeKey(fyStart, today), company), "/reports/payment-register",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.GSTR_1, snapshotKey(rangeKey(fyStart, today), company), "/reports/gstr-1",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.GSTR_2, snapshotKey(rangeKey(fyStart, today), company), "/reports/gstr-2",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.GSTR_3B, snapshotKey(rangeKey(fyStart, today), company), "/reports/gstr-3b",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.STOCK_AGEING_ANALYSIS, snapshotKey(rangeKey(fyStart, today), company), "/reports/stock-ageing-analysis",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.MOVEMENT_ANALYSIS, snapshotKey(rangeKey(fyStart, today), company), "/reports/movement-analysis",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.REORDER_STATUS, companySnapshotKey, "/reports/reorder-status", noParams(), null, null, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.FORM_26Q, snapshotKey(rangeKey(fyStart, today), company), "/reports/form-26q",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.FORM_24Q, snapshotKey(rangeKey(fyStart, today), company), "/reports/form-24q",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.FORM_27EQ, snapshotKey(rangeKey(fyStart, today), company), "/reports/form-27eq",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.TDS_OUTSTANDINGS, snapshotKey(rangeKey(fyStart, today), company), "/reports/tds-outstandings",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.COST_CENTRE_BREAKUP, snapshotKey(rangeKey(fyStart, today), company), "/reports/cost-centre-breakup",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
        safeSyncSimple(runId, TallyCacheDataset.RATIO_ANALYSIS, snapshotKey(rangeKey(fyStart, today), company), "/reports/ratio-analysis",
                params("from_date", fyStart, "to_date", today), fyStart, today, normalizer::genericRows, company, errors);
    }

    private String syncSimple(Long runId,
                              TallyCacheDataset dataset,
                              String snapshotKey,
                              String path,
                              Map<String, String> queryParams,
                              String rangeStart,
                              String rangeEnd,
                              DatasetNormalizer datasetNormalizer,
                              String company) throws Exception {
        ConnectorFetchResult fetchResult = connectorClient.fetch(path, queryParams, company);
        List<Map<String, Object>> rows = datasetNormalizer.normalize(fetchResult.getBody());
        rows = enrichRowsFromRelatedDatasets(dataset, rows, company);
        storeSnapshotRows(
                runId,
                dataset,
                snapshotKey,
                fetchResult.getTarget().getConnectorId(),
                company,
                writeJson(queryParams),
                rangeStart,
                rangeEnd,
                rows
        );
        return fetchResult.getTarget().getConnectorId();
    }

    private List<Map<String, Object>> enrichRowsFromRelatedDatasets(TallyCacheDataset dataset,
                                                                    List<Map<String, Object>> rows,
                                                                    String company) throws Exception {
        if (dataset != TallyCacheDataset.STOCK_GROUPS) {
            return rows;
        }
        if (rows != null && !rows.isEmpty()) {
            return rows;
        }

        ConnectorFetchResult stockItemsFetchResult = connectorClient.fetch("/stock-items", noParams(), company);
        List<Map<String, Object>> stockItems = normalizer.stockItems(stockItemsFetchResult.getBody());
        if (stockItems.isEmpty()) {
            return rows;
        }
        return deriveStockGroupsFromStockItems(stockItems);
    }

    private void syncDayBookAndLedgerVouchers(Long runId, String fromDate, String toDate, String company) throws Exception {
        Map<String, String> queryParams = params("from_date", fromDate, "to_date", toDate, "view", "raw");
        ConnectorFetchResult fetchResult = connectorClient.fetch("/reports/day-book", queryParams, company);
        JsonNode body = fetchResult.getBody();

        String snapshotKey = snapshotKey(rangeKey(fromDate, toDate), company);
        String connectorId = fetchResult.getTarget().getConnectorId();
        String requestParamsJson = writeJson(queryParams);
        storeSnapshotRows(runId, TallyCacheDataset.DAY_BOOK, snapshotKey, connectorId, company, requestParamsJson, fromDate, toDate, normalizer.dayBook(body));
        storeSnapshotRows(runId, TallyCacheDataset.LEDGER_VOUCHERS, snapshotKey, connectorId, company, requestParamsJson, fromDate, toDate, normalizer.ledgerVouchersFromDayBook(body));
    }

    private void safeSyncSimple(Long runId,
                                TallyCacheDataset dataset,
                                String snapshotKey,
                                String path,
                                Map<String, String> queryParams,
                                String rangeStart,
                                String rangeEnd,
                                DatasetNormalizer datasetNormalizer,
                                String company,
                                List<String> errors) {
        try {
            syncSimple(runId, dataset, snapshotKey, path, queryParams, rangeStart, rangeEnd, datasetNormalizer, company);
        } catch (Exception ex) {
            errors.add(syncError(dataset.getKey(), company, ex));
        }
    }

    private void safeSyncDayBookAndLedgerVouchers(Long runId,
                                                  String fromDate,
                                                  String toDate,
                                                  String company,
                                                  List<String> errors) {
        try {
            syncDayBookAndLedgerVouchers(runId, fromDate, toDate, company);
        } catch (Exception ex) {
            errors.add(syncError(TallyCacheDataset.DAY_BOOK.getKey(), company, ex));
            errors.add(syncError(TallyCacheDataset.LEDGER_VOUCHERS.getKey(), company, ex));
        }
    }

    private void storeSnapshotRows(Long runId,
                                   TallyCacheDataset dataset,
                                   String snapshotKey,
                                   String connectorId,
                                   String company,
                                   String requestParamsJson,
                                   String rangeStart,
                                   String rangeEnd,
                                   List<Map<String, Object>> rawRows) throws Exception {
        Instant now = Instant.now();
        List<Map<String, Object>> rows = deduplicateRows(rawRows);
        TallyDatasetSnapshot currentSnapshot = snapshotRepository.findCurrent(dataset, snapshotKey);
        String contentHash = contentHash(rows);

        // Treat empty refreshes as transient when we already have a non-empty current snapshot.
        if (currentSnapshot != null && rows.isEmpty() && currentSnapshot.getRowCount() > 0) {
            snapshotRepository.refreshCurrentSnapshot(
                    currentSnapshot.getId(),
                    runId,
                    connectorId,
                    normalizeCompany(company),
                    requestParamsJson,
                    currentSnapshot.getContentHash(),
                    rangeStart,
                    rangeEnd,
                    currentSnapshot.getRowCount(),
                    properties.getStaleAfterMs(),
                    now,
                    now
            );
            pruneDuplicateSnapshots(dataset, snapshotKey, currentSnapshot.getContentHash(), currentSnapshot.getId());
            pruneObsoleteSnapshots(dataset, snapshotKey, currentSnapshot.getId());
            return;
        }

        if (currentSnapshot != null && contentHash.equals(currentSnapshot.getContentHash())) {
            snapshotRepository.refreshCurrentSnapshot(
                    currentSnapshot.getId(),
                    runId,
                    connectorId,
                    normalizeCompany(company),
                    requestParamsJson,
                    contentHash,
                    rangeStart,
                    rangeEnd,
                    rows.size(),
                    properties.getStaleAfterMs(),
                    now,
                    now
            );
            pruneDuplicateSnapshots(dataset, snapshotKey, contentHash, currentSnapshot.getId());
            pruneObsoleteSnapshots(dataset, snapshotKey, currentSnapshot.getId());
            return;
        }

        Long snapshotId = snapshotRepository.createSnapshot(
                runId,
                dataset,
                snapshotKey,
                connectorId,
                normalizeCompany(company),
                requestParamsJson,
                contentHash,
                rangeStart,
                rangeEnd,
                properties.getStaleAfterMs(),
                now
        );
        try {
            rowRepository.replaceRows(dataset, snapshotId, rows);
            snapshotRepository.markSnapshotSuccess(snapshotId, dataset, snapshotKey, rows.size(), contentHash, now);
            pruneDuplicateSnapshots(dataset, snapshotKey, contentHash, snapshotId);
            pruneObsoleteSnapshots(dataset, snapshotKey, snapshotId);
        } catch (Exception ex) {
            snapshotRepository.markSnapshotFailed(snapshotId, ex.getMessage(), now);
            throw ex;
        }
    }

    private void pruneDuplicateSnapshots(TallyCacheDataset dataset, String snapshotKey, String contentHash, Long keepSnapshotId) {
        List<Long> duplicateSnapshotIds = snapshotRepository.listDuplicateSnapshotIds(dataset, snapshotKey, contentHash, keepSnapshotId);
        for (Long snapshotId : duplicateSnapshotIds) {
            rowRepository.deleteRows(dataset, snapshotId);
        }
        snapshotRepository.deleteSnapshots(duplicateSnapshotIds);
    }

    private void pruneObsoleteSnapshots(TallyCacheDataset dataset, String snapshotKey, Long keepSnapshotId) {
        List<Long> obsoleteSnapshotIds = snapshotRepository.listObsoleteSnapshotIds(dataset, snapshotKey, keepSnapshotId);
        for (Long snapshotId : obsoleteSnapshotIds) {
            rowRepository.deleteRows(dataset, snapshotId);
        }
        snapshotRepository.deleteSnapshots(obsoleteSnapshotIds);
    }

    private String joinErrors(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        return String.join(" | ", errors);
    }

    private String syncError(String datasetKey, String company, Exception ex) {
        StringBuilder message = new StringBuilder(datasetKey);
        if (company != null && !company.trim().isEmpty()) {
            message.append("@").append(company.trim());
        }
        message.append(": ").append(ex.getMessage());
        return message.toString();
    }

    private Map<String, String> noParams() {
        return new LinkedHashMap<String, String>();
    }

    private Map<String, String> params(String... items) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (int index = 0; index + 1 < items.length; index += 2) {
            values.put(items[index], items[index + 1]);
        }
        return values;
    }

    private String rangeKey(String fromDate, String toDate) {
        return "from=" + fromDate + "&to=" + toDate;
    }

    private Set<String> companiesToSync(List<Map<String, Object>> companyRows) {
        Set<String> companies = new HashSet<String>();
        String configuredCompany = normalizeCompany(properties.getCompany());
        if (configuredCompany != null) {
            companies.add(configuredCompany);
        }
        if (companyRows != null) {
            for (Map<String, Object> row : companyRows) {
                String company = normalizeCompany(row == null ? null : String.valueOf(row.get("name")));
                if (company != null) {
                    companies.add(company);
                }
            }
        }
        for (ConnectorRegistration registration : connectorRegistryService.list()) {
            String company = normalizeCompany(registration == null ? null : registration.getCompany());
            if (company != null) {
                companies.add(company);
            }
        }
        if (companies.isEmpty()) {
            companies.add(null);
        }
        return companies;
    }

    private String snapshotKey(String baseKey, String company) {
        String normalizedCompany = normalizeCompany(company);
        if (normalizedCompany == null) {
            return baseKey;
        }
        return baseKey + "|company=" + normalizedCompany.toLowerCase();
    }

    private String normalizeCompany(String company) {
        if (company == null || company.trim().isEmpty()) {
            return null;
        }
        return company.trim();
    }

    private String writeJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private List<Map<String, Object>> deduplicateRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<Map<String, Object>>();
        }
        List<Map<String, Object>> deduplicated = new ArrayList<Map<String, Object>>();
        Set<String> seen = new HashSet<String>();
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            String signature = rowSignature(row);
            if (!seen.add(signature)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<String, Object>(row);
            copy.put("row_index", deduplicated.size());
            deduplicated.add(copy);
        }
        return deduplicated;
    }

    private String rowSignature(Map<String, Object> row) {
        Map<String, Object> ordered = new java.util.TreeMap<String, Object>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if ("row_index".equals(entry.getKey())) {
                continue;
            }
            ordered.put(entry.getKey(), entry.getValue());
        }
        try {
            return objectMapper.writeValueAsString(ordered);
        } catch (Exception ignored) {
            return String.valueOf(ordered);
        }
    }

    private String contentHash(List<Map<String, Object>> rows) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(objectMapper.writeValueAsBytes(rows));
            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(String.valueOf(rows).hashCode());
        }
    }

    private interface DatasetNormalizer {
        List<Map<String, Object>> normalize(JsonNode body);
    }

    private List<Map<String, Object>> deriveStockGroupsFromStockItems(List<Map<String, Object>> stockItems) {
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        Set<String> seen = new HashSet<String>();
        int rowIndex = 0;
        for (Map<String, Object> stockItem : stockItems) {
            String parent = firstNonBlank(
                    stringValue(stockItem == null ? null : stockItem.get("parent"))
            );
            if (parent.isEmpty()) {
                parent = "Primary";
            }
            if (!seen.add(parent.toLowerCase())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", rowIndex++);
            row.put("name", parent);
            row.put("parent", "Primary".equalsIgnoreCase(parent) ? "" : "Primary");
            row.put("payload_json", "{}");
            groups.add(row);
        }
        if (groups.isEmpty()) {
            return groups;
        }
        boolean hasPrimary = false;
        for (Map<String, Object> row : groups) {
            if ("Primary".equalsIgnoreCase(stringValue(row.get("name")))) {
                hasPrimary = true;
                break;
            }
        }
        if (!hasPrimary) {
            Map<String, Object> primary = new LinkedHashMap<String, Object>();
            primary.put("row_index", rowIndex);
            primary.put("name", "Primary");
            primary.put("parent", "");
            primary.put("payload_json", "{}");
            groups.add(0, primary);
            for (int index = 0; index < groups.size(); index++) {
                groups.get(index).put("row_index", index);
            }
        }
        return groups;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
