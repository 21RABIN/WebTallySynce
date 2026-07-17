package com.tallybackend.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tallybackend.service.TallyVoucherWriteQueueEntry;
import com.tallybackend.service.TallyVoucherWriteQueueRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class TallyCacheReadService {

    private final TallyCacheProperties properties;
    private final TallyCacheSnapshotRepository snapshotRepository;
    private final TallyCacheRowRepository rowRepository;
    private final TallyCacheConnectorClient connectorClient;
    private final TallyCacheNormalizer normalizer;
    private final TallyCacheSyncService syncService;
    private final TallyVoucherWriteQueueRepository voucherWriteQueueRepository;
    private final ObjectMapper objectMapper;

    public TallyCacheReadService(TallyCacheProperties properties,
                                 TallyCacheSnapshotRepository snapshotRepository,
                                 TallyCacheRowRepository rowRepository,
                                 TallyCacheConnectorClient connectorClient,
                                 TallyCacheNormalizer normalizer,
                                 TallyCacheSyncService syncService,
                                 TallyVoucherWriteQueueRepository voucherWriteQueueRepository,
                                 ObjectMapper objectMapper) {
        this.properties = properties;
        this.snapshotRepository = snapshotRepository;
        this.rowRepository = rowRepository;
        this.connectorClient = connectorClient;
        this.normalizer = normalizer;
        this.syncService = syncService;
        this.voucherWriteQueueRepository = voucherWriteQueueRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> companies(String company) {
        return cachedOrLiveSimple(TallyCacheDataset.COMPANIES, snapshotKey("default", company), "/reports/companies", company);
    }

    public Map<String, Object> companies() {
        return companies(null);
    }

    public Map<String, Object> groups(String company) {
        return appendPendingRows(
                cachedOrLiveSimple(TallyCacheDataset.GROUPS, snapshotKey("default", company), "/groups", company),
                "/groups",
                "NAME",
                entry -> pendingRow(entry, new String[]{"NAME", "PARENT"})
        );
    }

    public Map<String, Object> groups() {
        return groups(null);
    }

    public Map<String, Object> ledgers(String company) {
        return appendPendingRows(
                cachedOrLiveSimple(TallyCacheDataset.LEDGERS, snapshotKey("default", company), "/ledgers", company),
                "/ledgers",
                "NAME",
                entry -> pendingRow(entry, new String[]{"NAME", "PARENT"})
        );
    }

    public Map<String, Object> ledgers() {
        return ledgers(null);
    }

    public Map<String, Object> uoms(String company) {
        return appendPendingRows(
                cachedOrLiveSimple(TallyCacheDataset.UOMS, snapshotKey("default", company), "/uoms", company),
                "/uoms",
                "NAME",
                entry -> pendingRow(entry, new String[]{"NAME", "ORIGINALNAME", "ISSIMPLEUNIT"})
        );
    }

    public Map<String, Object> uoms() {
        return uoms(null);
    }

    public Map<String, Object> currencies(String company) {
        return cachedOrLiveSimple(TallyCacheDataset.CURRENCIES, snapshotKey("default", company), "/currencies", company);
    }

    public Map<String, Object> currencies() {
        return currencies(null);
    }

    public Map<String, Object> stockGroups(String company) {
        Map<String, Object> response = cachedOrLiveSimple(
                TallyCacheDataset.STOCK_GROUPS,
                snapshotKey("default", company),
                "/stock-groups",
                company
        );
        response = ensureDerivedStockGroups(response, company);
        return appendPendingRows(
                response,
                "/stock-groups",
                "NAME",
                entry -> pendingRow(entry, new String[]{"NAME", "PARENT"})
        );
    }

    public Map<String, Object> stockGroups() {
        return stockGroups(null);
    }

    public Map<String, Object> stockItems(String company) {
        return appendPendingRows(
                cachedOrLiveSimple(TallyCacheDataset.STOCK_ITEMS, snapshotKey("default", company), "/stock-items", company),
                "/stock-items",
                "NAME",
                entry -> pendingRow(entry, new String[]{
                        "NAME", "PARENT", "BASEUNITS", "HSNCODE", "GSTAPPLICABLE", "QUANTITY",
                        "RATEPER", "CLOSINGVALUE", "CLOSINGBALANCE", "OPENINGVALUE", "OPENINGBALANCE", "OPENINGRATE"
                })
        );
    }

    public Map<String, Object> stockItems() {
        return stockItems(null);
    }

    public Map<String, Object> companyCurrency(String company) {
        return applyLatestOverlay(
                cachedOrLiveSimple(TallyCacheDataset.COMPANY_CURRENCY, snapshotKey("default", company), "/settings/company-currency", company),
                "/settings/company-currency",
                entry -> pendingRow(entry, new String[]{"NAME", "BOOKSFROM", "MAILINGNAME", "CURRENCYNAME", "DECIMALSYMBOL"})
        );
    }

    public Map<String, Object> companyCurrency() {
        return companyCurrency(null);
    }

    public Map<String, Object> companyFeatures(String company) {
        return applyLatestOverlay(
                cachedOrLiveSimple(TallyCacheDataset.COMPANY_FEATURES, snapshotKey("default", company), "/settings/company-features", company),
                "/settings/company-features",
                entry -> pendingRow(entry, new String[]{"NAME", "BOOKSFROM", "EMAIL", "PINCODE", "COUNTRYNAME", "STATENAME", "ISINVENTORYON", "ISGSTON"})
        );
    }

    public Map<String, Object> companyFeatures() {
        return companyFeatures(null);
    }

    public Map<String, Object> gstRegistration(String company) {
        return cachedGenericSimple(TallyCacheDataset.GST_REGISTRATION, "/settings/gst-registration", company);
    }

    public Map<String, Object> numberingRules(String company) {
        return cachedGenericSimple(TallyCacheDataset.NUMBERING_RULES, "/settings/numbering-rules", company);
    }

    public Map<String, Object> taxRateTables(String company) {
        return cachedGenericSimple(TallyCacheDataset.TAX_RATE_TABLES, "/settings/tax-rate-tables", company);
    }

    public Map<String, Object> priceStructures(String company) {
        return cachedGenericSimple(TallyCacheDataset.PRICE_STRUCTURES, "/settings/price-structures", company);
    }

    public Map<String, Object> stockControls(String company) {
        return cachedGenericSimple(TallyCacheDataset.STOCK_CONTROLS, "/settings/stock-controls", company);
    }

    public Map<String, Object> securityRoles(String company) {
        return cachedGenericSimple(TallyCacheDataset.SECURITY_ROLES, "/settings/security-roles", company);
    }

    public Map<String, Object> uqcMappings(String company) {
        return cachedGenericSimple(TallyCacheDataset.UQC_MAPPINGS, "/settings/uqc-mappings", company);
    }

    public Map<String, Object> einvoiceSettings(String company) {
        return cachedGenericSimple(TallyCacheDataset.EINVOICE_SETTINGS, "/settings/einvoice", company);
    }

    public Map<String, Object> ewaybillSettings(String company) {
        return cachedGenericSimple(TallyCacheDataset.EWAYBILL_SETTINGS, "/settings/ewaybill", company);
    }

    public Map<String, Object> dayBook(String fromDate, String toDate, String company) {
        String key = snapshotKey(rangeKey(fromDate, toDate), company);
        TallyDatasetSnapshot snapshot = ensureFreshRangeSnapshot(TallyCacheDataset.DAY_BOOK, key, fromDate, toDate, company);
        TallyDatasetSnapshot effectiveSnapshot = preferredVoucherSnapshot(TallyCacheDataset.DAY_BOOK, snapshot, key, company);
        List<Map<String, Object>> cachedRows = readRowsOrFallback(TallyCacheDataset.DAY_BOOK, effectiveSnapshot, null, fromDate, toDate, company);
        cachedRows = mergeSnapshotRows(cachedRows, TallyCacheDataset.DAY_BOOK, snapshot, null, fromDate, toDate);
        cachedRows = mergeRecentVoucherRows(cachedRows, fromDate, toDate, company);
        if (isUsable(effectiveSnapshot) && !cachedRows.isEmpty()) {
            return buildResponse(cachedRows, effectiveSnapshot, false, "cache", company);
        }
        if (!cachedRows.isEmpty()) {
            return buildResponse(cachedRows, effectiveSnapshot, false, "stale-cache", company);
        }
        throw cacheUnavailable("day book", company);
    }

    public Map<String, Object> dayBook(String fromDate, String toDate) {
        return dayBook(fromDate, toDate, null);
    }

    public Map<String, Object> ledgerVouchers(String fromDate, String toDate, String ledgerName, String company) {
        String key = snapshotKey(rangeKey(fromDate, toDate), company);
        TallyDatasetSnapshot snapshot = ensureFreshRangeSnapshot(TallyCacheDataset.LEDGER_VOUCHERS, key, fromDate, toDate, company);
        TallyDatasetSnapshot effectiveSnapshot = preferredVoucherSnapshot(TallyCacheDataset.LEDGER_VOUCHERS, snapshot, key, company);
        List<Map<String, Object>> cachedRows = readRowsOrFallback(TallyCacheDataset.LEDGER_VOUCHERS, effectiveSnapshot, ledgerName, fromDate, toDate, company);
        boolean cacheBackedByDayBook = false;
        if (cachedRows.isEmpty()) {
            List<Map<String, Object>> dayBookCachedRows = cachedLedgerVoucherRowsFromDayBook(key, fromDate, toDate, ledgerName, company);
            if (!dayBookCachedRows.isEmpty()) {
                cachedRows = dayBookCachedRows;
                cacheBackedByDayBook = true;
            }
        }
        if (isUsable(effectiveSnapshot) && !cachedRows.isEmpty()) {
            return buildResponse(cachedRows, effectiveSnapshot, false, "cache", company);
        }
        if (!cachedRows.isEmpty()) {
            return buildResponse(cachedRows, effectiveSnapshot, false, "stale-cache", company);
        }
        throw cacheUnavailable("ledger vouchers", company);
    }

    public Map<String, Object> ledgerVouchers(String fromDate, String toDate, String ledgerName) {
        return ledgerVouchers(fromDate, toDate, ledgerName, null);
    }

    public Map<String, Object> balanceSheet(String fromDate, String toDate, String company) {
        return cachedOrLiveStatement(TallyCacheDataset.BALANCE_SHEET, "/reports/balance-sheet", fromDate, toDate, company);
    }

    public Map<String, Object> balanceSheet(String fromDate, String toDate) {
        return balanceSheet(fromDate, toDate, null);
    }

    public Map<String, Object> profitLoss(String fromDate, String toDate, String company) {
        return cachedOrLiveStatement(TallyCacheDataset.PROFIT_LOSS, "/reports/profit-loss", fromDate, toDate, company);
    }

    public Map<String, Object> profitLoss(String fromDate, String toDate) {
        return profitLoss(fromDate, toDate, null);
    }

    public Map<String, Object> stockSummary(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.STOCK_SUMMARY, "/reports/stock-summary", fromDate, toDate, company);
    }

    public Map<String, Object> outstandingReceivables(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.OUTSTANDING_RECEIVABLES, "/reports/outstanding-receivables", fromDate, toDate, company);
    }

    public Map<String, Object> outstandingPayables(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.OUTSTANDING_PAYABLES, "/reports/outstanding-payables", fromDate, toDate, company);
    }

    public Map<String, Object> batchAvailability(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.BATCH_AVAILABILITY, "/reports/batch-availability", fromDate, toDate, company);
    }

    public Map<String, Object> reportPriceLists(String company) {
        return cachedGenericSimple(TallyCacheDataset.REPORT_PRICE_LISTS, "/reports/price-lists", company);
    }

    public Map<String, Object> bankRecoStatus(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.BANK_RECO_STATUS, "/reports/bank-reco-status", fromDate, toDate, company);
    }

    public Map<String, Object> trialBalance(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.TRIAL_BALANCE, "/reports/trial-balance", fromDate, toDate, company);
    }

    public Map<String, Object> cashBook(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.CASH_BOOK, "/reports/cash-book", fromDate, toDate, company);
    }

    public Map<String, Object> bankBook(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.BANK_BOOK, "/reports/bank-book", fromDate, toDate, company);
    }

    public Map<String, Object> cashFlow(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.CASH_FLOW, "/reports/cash-flow", fromDate, toDate, company);
    }

    public Map<String, Object> fundsFlow(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.FUNDS_FLOW, "/reports/funds-flow", fromDate, toDate, company);
    }

    public Map<String, Object> salesRegister(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.SALES_REGISTER, "/reports/sales-register", fromDate, toDate, company);
    }

    public Map<String, Object> salesTrend(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.SALES_TREND, "/reports/sales-trend", fromDate, toDate, company);
    }

    public Map<String, Object> purchaseRegister(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.PURCHASE_REGISTER, "/reports/purchase-register", fromDate, toDate, company);
    }

    public Map<String, Object> journalRegister(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.JOURNAL_REGISTER, "/reports/journal-register", fromDate, toDate, company);
    }

    public Map<String, Object> receiptRegister(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.RECEIPT_REGISTER, "/reports/receipt-register", fromDate, toDate, company);
    }

    public Map<String, Object> paymentRegister(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.PAYMENT_REGISTER, "/reports/payment-register", fromDate, toDate, company);
    }

    public Map<String, Object> gstr1(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.GSTR_1, "/reports/gstr-1", fromDate, toDate, company);
    }

    public Map<String, Object> gstr2(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.GSTR_2, "/reports/gstr-2", fromDate, toDate, company);
    }

    public Map<String, Object> gstr3b(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.GSTR_3B, "/reports/gstr-3b", fromDate, toDate, company);
    }

    public Map<String, Object> stockAgeingAnalysis(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.STOCK_AGEING_ANALYSIS, "/reports/stock-ageing-analysis", fromDate, toDate, company);
    }

    public Map<String, Object> movementAnalysis(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.MOVEMENT_ANALYSIS, "/reports/movement-analysis", fromDate, toDate, company);
    }

    public Map<String, Object> reorderStatus(String company) {
        return cachedGenericSimple(TallyCacheDataset.REORDER_STATUS, "/reports/reorder-status", company);
    }

    public Map<String, Object> form26q(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.FORM_26Q, "/reports/form-26q", fromDate, toDate, company);
    }

    public Map<String, Object> form24q(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.FORM_24Q, "/reports/form-24q", fromDate, toDate, company);
    }

    public Map<String, Object> form27eq(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.FORM_27EQ, "/reports/form-27eq", fromDate, toDate, company);
    }

    public Map<String, Object> tdsOutstandings(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.TDS_OUTSTANDINGS, "/reports/tds-outstandings", fromDate, toDate, company);
    }

    public Map<String, Object> costCentreBreakup(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.COST_CENTRE_BREAKUP, "/reports/cost-centre-breakup", fromDate, toDate, company);
    }

    public Map<String, Object> ratioAnalysis(String fromDate, String toDate, String company) {
        return cachedGenericStatement(TallyCacheDataset.RATIO_ANALYSIS, "/reports/ratio-analysis", fromDate, toDate, company);
    }

    public Map<String, Object> status() {
        return status(null);
    }

    public Map<String, Object> status(String company) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        List<TallyDatasetSnapshot> currentSnapshots = snapshotRepository.listCurrentSnapshots();
        response.put("sync_enabled", properties.isEnabled());
        response.put("sync_interval_ms", properties.getIntervalMs());
        response.put("stale_after_ms", properties.getStaleAfterMs());
        response.put("company", company == null || company.trim().isEmpty() ? properties.getCompany() : company.trim());
        response.put("current_snapshots", currentSnapshots);
        response.put("dataset_statuses", datasetStatuses(currentSnapshots, company));
        response.put("recent_runs", snapshotRepository.listRecentRuns(10));
        response.put("connector_health", connectorHealth(company));
        response.put("queue_summary", queueSummary(company));
        response.put("reconciliation_summary", reconciliationSummary(company));
        return response;
    }

    public Map<String, Object> triggerSync() {
        return syncService.runSyncNow();
    }

    private Map<String, Object> cachedOrLiveStatement(TallyCacheDataset dataset,
                                                      String path,
                                                      String fromDate,
                                                      String toDate,
                                                      String company) {
        String key = snapshotKey(rangeKey(fromDate, toDate), company);
        TallyDatasetSnapshot snapshot = ensureFreshRangeSnapshot(dataset, key, fromDate, toDate, company);
        List<Map<String, Object>> cachedRows = readRowsOrFallback(dataset, snapshot, null, fromDate, toDate, company);
        if (isUsable(snapshot) && !cachedRows.isEmpty()) {
            return buildResponse(cachedRows, snapshot, false, "cache", company);
        }
        if (!cachedRows.isEmpty()) {
            return buildResponse(cachedRows, snapshot, false, "stale-cache", company);
        }
        throw cacheUnavailable(path, company);
    }

    private Map<String, Object> cachedGenericSimple(TallyCacheDataset dataset, String path, String company) {
        return cachedOrLiveSimple(dataset, snapshotKey("default", company), path, company);
    }

    private Map<String, Object> cachedGenericStatement(TallyCacheDataset dataset,
                                                       String path,
                                                       String fromDate,
                                                       String toDate,
                                                       String company) {
        return cachedOrLiveStatement(dataset, path, fromDate, toDate, company);
    }

    private Map<String, Object> cachedOrLiveSimple(TallyCacheDataset dataset,
                                                   String snapshotKey,
                                                   String path,
                                                   String company) {
        TallyDatasetSnapshot snapshot = ensureFreshSimpleSnapshot(dataset, snapshotKey, company);
        List<Map<String, Object>> cachedRows = readRowsOrFallback(dataset, snapshot, null, null, null, company);
        if (isUsable(snapshot) && !cachedRows.isEmpty()) {
            return buildResponse(cachedRows, snapshot, false, "cache", company);
        }
        if (snapshot != null && !cachedRows.isEmpty()) {
            return buildResponse(cachedRows, snapshot, false, "stale-cache", company);
        }
        throw cacheUnavailable(path, company);
    }

    private TallyDatasetSnapshot findSimpleSnapshot(TallyCacheDataset dataset,
                                                    String requestedSnapshotKey,
                                                    String company) {
        TallyDatasetSnapshot snapshot = snapshotRepository.findCurrent(dataset, requestedSnapshotKey);
        if (snapshot != null) {
            return snapshot;
        }

        return null;
    }

    private TallyDatasetSnapshot ensureFreshSimpleSnapshot(TallyCacheDataset dataset,
                                                           String requestedSnapshotKey,
                                                           String company) {
        return findSimpleSnapshot(dataset, requestedSnapshotKey, company);
    }

    private TallyDatasetSnapshot ensureFreshRangeSnapshot(TallyCacheDataset dataset,
                                                          String requestedSnapshotKey,
                                                          String fromDate,
                                                          String toDate,
                                                          String company) {
        TallyDatasetSnapshot snapshot = snapshotRepository.findCurrent(dataset, requestedSnapshotKey);
        if (snapshot == null) {
            snapshot = isBlank(company)
                    ? snapshotRepository.findCurrentCoveringRange(dataset, fromDate, toDate)
                    : snapshotRepository.findCurrentCoveringRange(dataset, fromDate, toDate, normalizeCompany(company));
        }
        if (snapshot == null && !isBlank(company)) {
            snapshot = snapshotRepository.findLatestSuccessfulNonEmptyForDataset(dataset, normalizeCompany(company));
        }
        return snapshot;
    }

    private ResponseStatusException cacheUnavailable(String datasetName, String company) {
        String resolvedCompany = isBlank(company) ? properties.getCompany() : company.trim();
        StringBuilder message = new StringBuilder("No cached ").append(datasetName).append(" data is available in the database");
        if (!isBlank(resolvedCompany)) {
            message.append(" for company ").append(resolvedCompany);
        }
        message.append(". Bring TallyPrime online and run cache sync so the backend can store fresh data in the database.");
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message.toString());
    }

    private List<Map<String, Object>> readRowsOrFallback(TallyCacheDataset dataset,
                                                         TallyDatasetSnapshot snapshot,
                                                         String ledgerName,
                                                         String fromDate,
                                                         String toDate,
                                                         String company) {
        List<Map<String, Object>> rows = readRows(dataset, snapshot, ledgerName, fromDate, toDate);
        if (!rows.isEmpty()) {
            return rows;
        }

        TallyDatasetSnapshot fallbackSnapshot;
        if (!isBlank(company)) {
            if (snapshot != null && snapshot.getSnapshotKey() != null && !snapshot.getSnapshotKey().trim().isEmpty()) {
                fallbackSnapshot = snapshotRepository.findLatestSuccessfulNonEmpty(dataset, snapshot.getSnapshotKey());
            } else {
                fallbackSnapshot = snapshotRepository.findLatestSuccessfulNonEmptyCoveringRange(dataset, fromDate, toDate, normalizeCompany(company));
                if (fallbackSnapshot == null) {
                    fallbackSnapshot = snapshotRepository.findLatestSuccessfulNonEmptyForDataset(dataset, normalizeCompany(company));
                }
            }
        } else if (fromDate != null && toDate != null) {
            fallbackSnapshot = snapshotRepository.findLatestSuccessfulNonEmptyCoveringRange(dataset, fromDate, toDate);
            if (fallbackSnapshot == null) {
                fallbackSnapshot = snapshotRepository.findLatestSuccessfulNonEmptyForDataset(dataset);
            }
        } else if (snapshot != null) {
            fallbackSnapshot = snapshotRepository.findLatestSuccessfulNonEmpty(dataset, snapshot.getSnapshotKey());
            if (fallbackSnapshot == null) {
                fallbackSnapshot = snapshotRepository.findLatestSuccessfulNonEmptyForDataset(dataset);
            }
        } else {
            fallbackSnapshot = snapshotRepository.findLatestSuccessfulNonEmptyForDataset(dataset);
        }

        if (fallbackSnapshot == null || fallbackSnapshot.getId() == null || (snapshot != null && fallbackSnapshot.getId().equals(snapshot.getId()))) {
            return rows;
        }
        return readRows(dataset, fallbackSnapshot, ledgerName, fromDate, toDate);
    }

    private List<Map<String, Object>> readRows(TallyCacheDataset dataset,
                                               TallyDatasetSnapshot snapshot,
                                               String ledgerName,
                                               String fromDate,
                                               String toDate) {
        if (snapshot == null || snapshot.getId() == null) {
            return java.util.Collections.emptyList();
        }
        if (dataset == TallyCacheDataset.LEDGER_VOUCHERS) {
            return rowRepository.readLedgerVoucherRows(snapshot.getId(), fromDate, toDate, ledgerName);
        }
        return rowRepository.readRows(dataset, snapshot.getId());
    }

    private TallyDatasetSnapshot preferredVoucherSnapshot(TallyCacheDataset dataset,
                                                          TallyDatasetSnapshot snapshot,
                                                          String snapshotKey,
                                                          String company) {
        if (snapshot == null || snapshot.getId() == null) {
            return snapshot;
        }
        if (dataset != TallyCacheDataset.DAY_BOOK && dataset != TallyCacheDataset.LEDGER_VOUCHERS) {
            return snapshot;
        }
        TallyDatasetSnapshot previousSnapshot = snapshotRepository.findPreviousSuccessfulSnapshot(
                dataset,
                snapshotKey,
                snapshot.getId(),
                normalizeCompany(company)
        );
        if (sameOrBetterSnapshot(previousSnapshot, snapshot)
                && previousSnapshot.getFetchedAt() != null
                && snapshot.getFetchedAt() != null
                && !previousSnapshot.getFetchedAt().isBefore(snapshot.getFetchedAt().minusSeconds(7L * 24L * 60L * 60L))) {
            return previousSnapshot;
        }
        TallyDatasetSnapshot recentRangeSnapshot = snapshotRepository.findBestRecentSuccessfulRangeSnapshot(
                dataset,
                snapshot.getRangeStart(),
                snapshot.getRangeEnd(),
                snapshot.getId(),
                normalizeCompany(company)
        );
        if (!sameOrBetterSnapshot(recentRangeSnapshot, snapshot)) {
            return snapshot;
        }
        if (recentRangeSnapshot.getFetchedAt() == null || snapshot.getFetchedAt() == null) {
            return snapshot;
        }
        if (recentRangeSnapshot.getFetchedAt().isBefore(snapshot.getFetchedAt().minusSeconds(7L * 24L * 60L * 60L))) {
            return snapshot;
        }
        return recentRangeSnapshot;
    }

    private boolean sameOrBetterSnapshot(TallyDatasetSnapshot candidate, TallyDatasetSnapshot current) {
        if (candidate == null || candidate.getId() == null || current == null || current.getId() == null) {
            return false;
        }
        if (candidate.getRowCount() <= current.getRowCount()) {
            return false;
        }
        return Objects.equals(candidate.getRangeStart(), current.getRangeStart());
    }

    private List<Map<String, Object>> mergeSnapshotRows(List<Map<String, Object>> baseRows,
                                                        TallyCacheDataset dataset,
                                                        TallyDatasetSnapshot snapshot,
                                                        String ledgerName,
                                                        String fromDate,
                                                        String toDate) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (baseRows != null) {
            rows.addAll(baseRows);
        }
        if (snapshot == null || snapshot.getId() == null) {
            return rows;
        }

        Set<String> existingKeys = new HashSet<String>();
        for (Map<String, Object> row : rows) {
            existingKeys.add(voucherRowKey(row));
        }

        List<Map<String, Object>> latestRows = readRows(dataset, snapshot, ledgerName, fromDate, toDate);
        for (Map<String, Object> row : latestRows) {
            String key = voucherRowKey(row);
            if (existingKeys.contains(key)) {
                continue;
            }
            existingKeys.add(key);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> mergeRecentVoucherRows(List<Map<String, Object>> baseRows,
                                                             String fromDate,
                                                             String toDate,
                                                             String company) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (baseRows != null) {
            rows.addAll(baseRows);
        }

        Set<String> existingKeys = new HashSet<String>();
        for (Map<String, Object> row : rows) {
            existingKeys.add(voucherRowKey(row));
        }

        Instant visibleSince = Instant.now().minusMillis(Math.max(properties.getStaleAfterMs(), 24L * 60L * 60L * 1000L));
        List<String> connectorPaths = new ArrayList<String>();
        connectorPaths.add("/vouchers/sales");
        connectorPaths.add("/vouchers/purchase");
        connectorPaths.add("/vouchers/journal");
        connectorPaths.add("/vouchers/payment");
        connectorPaths.add("/vouchers/receipt");
        connectorPaths.add("/vouchers/contra");

        for (String connectorPath : connectorPaths) {
            List<TallyVoucherWriteQueueEntry> entries = voucherWriteQueueRepository.listVisibleByConnectorPath(
                    connectorPath,
                    100,
                    visibleSince
            );
            for (TallyVoucherWriteQueueEntry entry : pendingEntriesOnly(entries)) {
                Map<String, Object> voucherRow = voucherDayBookRow(entry, fromDate, toDate, company);
                if (voucherRow == null) {
                    continue;
                }
                String key = voucherRowKey(voucherRow);
                if (existingKeys.contains(key)) {
                    continue;
                }
                existingKeys.add(key);
                rows.add(voucherRow);
            }
        }
        return rows;
    }

    private String voucherRowKey(Map<String, Object> row) {
        if (row == null) {
            return "";
        }
        return dayBookRowKey(
                stringValue(row.get("date")),
                stringValue(row.get("voucherType")),
                stringValue(row.get("voucherNumber")),
                stringValue(row.get("partyLedger")),
                decimalValue(row.get("amount"))
        );
    }

    private Map<String, Object> voucherDayBookRow(TallyVoucherWriteQueueEntry entry,
                                                  String fromDate,
                                                  String toDate,
                                                  String company) {
        if (entry == null) {
            return null;
        }
        if (company != null && !company.trim().isEmpty()) {
            String entryCompany = stringValue(entry.getCompany());
            if (entryCompany.isEmpty()) {
                return null;
            }
            if (!entryCompany.equalsIgnoreCase(company.trim())) {
                return null;
            }
        }
        try {
            Object parsed = objectMapper.readValue(entry.getRequestBody(), Object.class);
            Map<String, Object> voucher = unwrapVoucherPayload(parsed);
            if (voucher == null) {
                return null;
            }

            String voucherDate = stringValue(firstNonNull(voucher.get("DATE"), voucher.get("EFFECTIVEDATE")));
            if (!matchesDateRange(voucherDate, fromDate, toDate)) {
                return null;
            }

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("date", voucherDate);
            row.put("voucherType", stringValue(firstNonNull(voucher.get("VOUCHERTYPENAME"), inferVoucherType(entry.getConnectorPath()))));
            String originalVoucherNumber = firstNonBlank(
                    stringValue(entry.getOriginalVoucherNumber()),
                    stringValue(voucher.get("ORIGINALVOUCHERNUMBER")),
                    stringValue(voucher.get("originalVoucherNumber")),
                    stringValue(voucher.get("original_voucher_number")),
                    nonOfflineValue(voucher.get("REFERENCE"))
            );
            String offlineVoucherNumber = firstNonBlank(
                    stringValue(entry.getOfflineVoucherNumber()),
                    stringValue(voucher.get("OFFLINEVOUCHERNUMBER")),
                    stringValue(voucher.get("offlineVoucherNumber")),
                    stringValue(voucher.get("offline_voucher_number"))
            );
            String voucherNumber = firstNonBlank(
                    originalVoucherNumber,
                    nonOfflineValue(voucher.get("VOUCHERNUMBER")),
                    nonOfflineValue(voucher.get("REFERENCE")),
                    stringValue(voucher.get("VOUCHERNUMBER")),
                    stringValue(voucher.get("REFERENCE")),
                    offlineVoucherNumber
            );
            row.put("voucherNumber", voucherNumber);
            row.put("originalVoucherNumber", originalVoucherNumber);
            row.put("original_voucher_number", originalVoucherNumber);
            row.put("offlineVoucherNumber", offlineVoucherNumber);
            row.put("offline_voucher_number", offlineVoucherNumber);
            row.put("partyLedger", firstNonBlank(
                    stringValue(voucher.get("PARTYLEDGERNAME")),
                    stringValue(firstLedgerName(voucher.get("LEDGERENTRIES.LIST"))),
                    stringValue(firstLedgerName(voucher.get("LEDGERENTRIES"))),
                    stringValue(firstLedgerName(voucher.get("ALLLEDGERENTRIES.LIST"))),
                    stringValue(firstLedgerName(voucher.get("ALLLEDGERENTRIES")))
            ));
            row.put("amount", absoluteAmountFromVoucher(voucher));
            row.put("narration", stringValue(voucher.get("NARRATION")));
            row.put("payloadJson", objectMapper.writeValueAsString(voucher));
            row.put("payload_json", objectMapper.writeValueAsString(voucher));
            row.put("pending_sync", entry.getStatus() == null || !"APPLIED".equalsIgnoreCase(entry.getStatus()));
            row.put("recent_write_through", "APPLIED".equalsIgnoreCase(entry.getStatus()));
            row.put("queue_id", entry.getId());
            row.put("queue_status", entry.getStatus());
            return row;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String nonOfflineValue(Object value) {
        String text = stringValue(value);
        if (text.isEmpty() || text.toUpperCase().startsWith("OFF-")) {
            return null;
        }
        return text;
    }

    private Map<String, Object> unwrapVoucherPayload(Object parsed) {
        if (parsed instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) parsed;
            Object voucherValue = map.get("VOUCHER");
            if (voucherValue instanceof Map) {
                return (Map<String, Object>) voucherValue;
            }
            if (voucherValue instanceof List && !((List<?>) voucherValue).isEmpty() && ((List<?>) voucherValue).get(0) instanceof Map) {
                return (Map<String, Object>) ((List<?>) voucherValue).get(0);
            }
            Object lowerVoucherValue = map.get("voucher");
            if (lowerVoucherValue instanceof Map) {
                return (Map<String, Object>) lowerVoucherValue;
            }
            if (lowerVoucherValue instanceof List && !((List<?>) lowerVoucherValue).isEmpty() && ((List<?>) lowerVoucherValue).get(0) instanceof Map) {
                return (Map<String, Object>) ((List<?>) lowerVoucherValue).get(0);
            }
            return map;
        }
        if (parsed instanceof List && !((List<?>) parsed).isEmpty() && ((List<?>) parsed).get(0) instanceof Map) {
            return (Map<String, Object>) ((List<?>) parsed).get(0);
        }
        return null;
    }

    private Object firstLedgerName(Object entriesValue) {
        if (entriesValue instanceof Map) {
            Object ledgerName = ((Map<?, ?>) entriesValue).get("LEDGERNAME");
            if (ledgerName != null && !String.valueOf(ledgerName).trim().isEmpty()) {
                return ledgerName;
            }
            return null;
        }
        if (!(entriesValue instanceof List)) {
            return null;
        }
        for (Object entry : (List<?>) entriesValue) {
            if (entry instanceof Map) {
                Object ledgerName = ((Map<?, ?>) entry).get("LEDGERNAME");
                if (ledgerName != null && !String.valueOf(ledgerName).trim().isEmpty()) {
                    return ledgerName;
                }
            }
        }
        return null;
    }

    private java.math.BigDecimal absoluteAmountFromVoucher(Map<String, Object> voucher) {
        Object entriesValue = firstNonNull(
                firstNonNull(voucher.get("ALLLEDGERENTRIES.LIST"), voucher.get("ALLLEDGERENTRIES")),
                firstNonNull(voucher.get("LEDGERENTRIES.LIST"), voucher.get("LEDGERENTRIES"))
        );
        if (entriesValue instanceof Map) {
            String amount = stringValue(((Map<?, ?>) entriesValue).get("AMOUNT"));
            if (!amount.isEmpty()) {
                return decimalValue(amount).abs();
            }
        }
        if (entriesValue instanceof List) {
            for (Object entry : (List<?>) entriesValue) {
                if (!(entry instanceof Map)) {
                    continue;
                }
                Map<?, ?> entryMap = (Map<?, ?>) entry;
                String amount = stringValue(entryMap.get("AMOUNT"));
                if (amount.isEmpty()) {
                    continue;
                }
                return decimalValue(amount).abs();
            }
        }
        String amount = stringValue(voucher.get("AMOUNT"));
        return amount.isEmpty() ? java.math.BigDecimal.ZERO : decimalValue(amount).abs();
    }

    private String inferVoucherType(String connectorPath) {
        if (connectorPath == null) {
            return "";
        }
        if (connectorPath.startsWith("/vouchers/")) {
            String suffix = connectorPath.substring("/vouchers/".length());
            if (suffix.isEmpty()) {
                return "";
            }
            return Character.toUpperCase(suffix.charAt(0)) + suffix.substring(1);
        }
        return connectorPath;
    }

    private String dayBookRowKey(String date,
                                 String voucherType,
                                 String voucherNumber,
                                 String partyLedger,
                                 java.math.BigDecimal amount) {
        return String.join("|",
                stringValue(date),
                stringValue(voucherType).toLowerCase(),
                stringValue(voucherNumber),
                stringValue(partyLedger).toLowerCase(),
                amount == null ? "0" : amount.stripTrailingZeros().toPlainString());
    }

    private List<Map<String, Object>> cachedLedgerVoucherRowsFromDayBook(String snapshotKey,
                                                                         String fromDate,
                                                                         String toDate,
                                                                         String ledgerName,
                                                                         String company) {
        TallyDatasetSnapshot dayBookSnapshot = snapshotRepository.findCurrent(TallyCacheDataset.DAY_BOOK, snapshotKey);
        if (dayBookSnapshot == null && isBlank(company)) {
            dayBookSnapshot = snapshotRepository.findCurrentCoveringRange(TallyCacheDataset.DAY_BOOK, fromDate, toDate);
        }
        List<Map<String, Object>> dayBookRows = readRowsOrFallback(TallyCacheDataset.DAY_BOOK, dayBookSnapshot, null, fromDate, toDate, company);
        return ledgerVoucherRowsFromDayBookRows(dayBookRows, ledgerName, fromDate, toDate);
    }

    private List<Map<String, Object>> ledgerVoucherRowsFromDayBookRows(List<Map<String, Object>> dayBookRows,
                                                                       String ledgerName,
                                                                       String fromDate,
                                                                       String toDate) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (dayBookRows == null || dayBookRows.isEmpty()) {
            return rows;
        }
        String normalizedLedgerName = normalizedName(ledgerName);
        for (Map<String, Object> dayBookRow : dayBookRows) {
            if (dayBookRow == null) {
                continue;
            }
            String voucherDate = stringValue(dayBookRow.get("date"));
            if (!matchesDateRange(voucherDate, fromDate, toDate)) {
                continue;
            }
            String partyLedger = stringValue(dayBookRow.get("partyLedger"));
            if (normalizedLedgerName != null && !normalizedLedgerName.equals(normalizedName(partyLedger))) {
                continue;
            }
            java.math.BigDecimal amount = decimalValue(dayBookRow.get("amount"));
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("date", voucherDate);
            row.put("ledger", partyLedger);
            row.put("voucherType", stringValue(dayBookRow.get("voucherType")));
            row.put("debitAmount", amount.signum() < 0 ? amount.abs() : java.math.BigDecimal.ZERO);
            row.put("creditAmount", amount.signum() > 0 ? amount.abs() : java.math.BigDecimal.ZERO);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> filterLedgerVoucherRows(List<Map<String, Object>> rows,
                                                              String ledgerName,
                                                              String fromDate,
                                                              String toDate) {
        List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
        String normalizedLedgerName = normalizedName(ledgerName);
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            String voucherDate = stringValue(firstNonNull(row.get("voucher_date"), row.get("date")));
            if (!matchesDateRange(voucherDate, fromDate, toDate)) {
                continue;
            }
            String rowLedgerName = stringValue(firstNonNull(row.get("ledger_name"), row.get("ledger")));
            if (normalizedLedgerName != null && !normalizedLedgerName.equals(normalizedName(rowLedgerName))) {
                continue;
            }
            Map<String, Object> normalizedRow = new LinkedHashMap<String, Object>();
            normalizedRow.put("date", voucherDate);
            normalizedRow.put("ledger", rowLedgerName);
            normalizedRow.put("voucherType", stringValue(firstNonNull(row.get("voucher_type"), row.get("voucherType"))));
            normalizedRow.put("debitAmount", decimalValue(firstNonNull(row.get("debit_amount"), row.get("debitAmount"))));
            normalizedRow.put("creditAmount", decimalValue(firstNonNull(row.get("credit_amount"), row.get("creditAmount"))));
            filtered.add(normalizedRow);
        }
        return filtered;
    }

    private boolean matchesDateRange(String voucherDate, String fromDate, String toDate) {
        if (voucherDate == null || voucherDate.trim().isEmpty()) {
            return false;
        }
        if (fromDate != null && !fromDate.trim().isEmpty() && voucherDate.compareTo(fromDate) < 0) {
            return false;
        }
        if (toDate != null && !toDate.trim().isEmpty() && voucherDate.compareTo(toDate) > 0) {
            return false;
        }
        return true;
    }

    private java.math.BigDecimal decimalValue(Object value) {
        if (value == null) {
            return java.math.BigDecimal.ZERO;
        }
        if (value instanceof java.math.BigDecimal) {
            return (java.math.BigDecimal) value;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return java.math.BigDecimal.ZERO;
        }
        try {
            return new java.math.BigDecimal(text);
        } catch (NumberFormatException ex) {
            return java.math.BigDecimal.ZERO;
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
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

    private Object firstNonNull(Object left, Object right) {
        return left != null ? left : right;
    }

    private boolean isUsable(TallyDatasetSnapshot snapshot) {
        if (snapshot == null || snapshot.getFetchedAt() == null) {
            return false;
        }
        return snapshot.getFetchedAt().plusMillis(snapshot.getStaleAfterMs()).isAfter(Instant.now());
    }

    private String rangeKey(String fromDate, String toDate) {
        return "from=" + fromDate + "&to=" + toDate;
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

    private Map<String, String> params(String... items) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (int index = 0; index + 1 < items.length; index += 2) {
            values.put(items[index], items[index + 1]);
        }
        return values;
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

    private Map<String, Object> buildResponse(List<Map<String, Object>> data,
                                              TallyDatasetSnapshot snapshot,
                                              boolean fallbackUsed,
                                              String source,
                                              String company) {
        Map<String, Object> meta = new LinkedHashMap<String, Object>();
        meta.put("last_synced_at", snapshot == null || snapshot.getFetchedAt() == null ? null : snapshot.getFetchedAt().toString());
        meta.put("snapshot_status", snapshot == null ? "LIVE_ONLY" : snapshot.getStatus());
        meta.put("connector_id", snapshot == null ? null : snapshot.getConnectorId());
        meta.put("company", company == null || company.trim().isEmpty()
                ? (snapshot == null ? properties.getCompany() : snapshot.getCompany())
                : company.trim());
        meta.put("fallback_used", fallbackUsed);
        meta.put("source", source);
        meta.put("range_start", snapshot == null ? null : snapshot.getRangeStart());
        meta.put("range_end", snapshot == null ? null : snapshot.getRangeEnd());

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("data", data);
        response.put("meta", meta);
        return response;
    }

    private Map<String, Object> ensureDerivedStockGroups(Map<String, Object> response, String company) {
        if (response == null) {
            return null;
        }
        Object dataValue = response.get("data");
        if (dataValue instanceof List && !((List<?>) dataValue).isEmpty()) {
            return response;
        }

        Map<String, Object> stockItemsResponse = stockItems(company);
        Object stockItemsData = stockItemsResponse == null ? null : stockItemsResponse.get("data");
        if (!(stockItemsData instanceof List) || ((List<?>) stockItemsData).isEmpty()) {
            return response;
        }

        List<Map<String, Object>> derivedGroups = deriveStockGroupsFromStockItems((List<Map<String, Object>>) stockItemsData);
        if (derivedGroups.isEmpty()) {
            return response;
        }

        response.put("data", derivedGroups);
        Object metaValue = response.get("meta");
        if (metaValue instanceof Map) {
            Map<String, Object> meta = (Map<String, Object>) metaValue;
            Object source = meta.get("source");
            meta.put("source", source == null ? "derived-stock-items" : String.valueOf(source) + "+derived-stock-items");
            meta.put("fallback_used", true);
        }
        return response;
    }

    private List<Map<String, Object>> deriveStockGroupsFromStockItems(List<Map<String, Object>> stockItems) {
        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        Set<String> seen = new HashSet<String>();
        int rowIndex = 0;

        for (Map<String, Object> stockItem : stockItems) {
            String parent = firstNonBlank(
                    stockItem == null ? null : stockItem.get("PARENT"),
                    stockItem == null ? null : stockItem.get("parent")
            );
            if (parent == null) {
                parent = "Primary";
            }
            String normalized = parent.trim().toLowerCase();
            if (!seen.add(normalized)) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", rowIndex++);
            row.put("NAME", parent);
            row.put("name", parent);
            row.put("PARENT", "Primary".equalsIgnoreCase(parent) ? "" : "Primary");
            row.put("parent", "Primary".equalsIgnoreCase(parent) ? "" : "Primary");
            row.put("RESERVEDNAME", "");
            row.put("reserved_name", "");
            row.put("derived_from_stock_items", true);
            groups.add(row);
        }

        if (groups.isEmpty()) {
            return groups;
        }

        boolean hasPrimary = false;
        for (Map<String, Object> row : groups) {
            String name = firstNonBlank(row.get("NAME"), row.get("name"));
            if ("Primary".equalsIgnoreCase(name)) {
                hasPrimary = true;
                break;
            }
        }
        if (!hasPrimary) {
            Map<String, Object> primary = new LinkedHashMap<String, Object>();
            primary.put("row_index", rowIndex);
            primary.put("NAME", "Primary");
            primary.put("name", "Primary");
            primary.put("PARENT", "");
            primary.put("parent", "");
            primary.put("RESERVEDNAME", "");
            primary.put("reserved_name", "");
            primary.put("derived_from_stock_items", true);
            groups.add(0, primary);
        }

        return groups;
    }

    private String snapshotKey(String baseKey, String company) {
        if (company == null || company.trim().isEmpty()) {
            return baseKey;
        }
        return baseKey + "|company=" + company.trim().toLowerCase();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }

    private Map<String, Object> connectorHealth(String company) {
        Map<String, Object> health = new LinkedHashMap<String, Object>();
        health.put("checked_at", Instant.now().toString());
        try {
            TallyCacheConnectorClient.ConnectorFetchResult result = connectorClient.fetch(
                    "/health",
                    new LinkedHashMap<String, String>(),
                    company
            );
            JsonNode body = result.getBody();
            String connectorStatus = body.path("status").asText("");
            boolean connectorOk = "ok".equalsIgnoreCase(connectorStatus);
            boolean reachable = body.path("reachable").asBoolean(false);
            boolean online = connectorOk || reachable;
            String message = connectorOk
                    ? "Tally connector is reachable."
                    : body.path("message").asText(body.path("detail").asText("Tally connector status unavailable."));

            try {
                TallyCacheConnectorClient.ConnectorFetchResult readinessResult = connectorClient.fetch(
                        "/health/readiness",
                        new LinkedHashMap<String, String>(),
                        company
                );
                JsonNode readinessBody = readinessResult.getBody();
                JsonNode tallyUpstream = readinessBody.path("upstreams").path("tally");
                if (tallyUpstream.path("reachable").asBoolean(false)
                        || "ok".equalsIgnoreCase(tallyUpstream.path("status").asText(""))) {
                    online = true;
                    if (!connectorOk) {
                        message = "Tally connector is reachable.";
                    }
                }
                health.put("readiness_status", readinessBody.path("status").asText(""));
                health.put("tally_upstream_status", tallyUpstream.path("status").asText(""));
                health.put("tally_upstream_reachable", tallyUpstream.path("reachable").asBoolean(false));
            } catch (Exception ignored) {
                // Keep the base /health result when readiness is unavailable or partially protected.
            }

            health.put("status", connectorStatus);
            health.put("reachable", reachable);
            health.put("online", online);
            health.put("connector_id", result.getTarget() == null ? null : result.getTarget().getConnectorId());
            health.put("base_url", result.getTarget() == null ? null : result.getTarget().getBaseUrl());
            health.put("message", message);
        } catch (Exception ex) {
            health.put("online", false);
            health.put("message", ex.getMessage());
        }
        return health;
    }

    private Map<String, Object> queueSummary(String company) {
        List<TallyVoucherWriteQueueEntry> entries = voucherWriteQueueRepository.listRecent(1000, normalizeCompany(company), null, null);
        int queued = 0;
        int retry = 0;
        int processing = 0;
        int failed = 0;
        int applied = 0;
        int pendingReview = 0;
        int conflict = 0;
        for (TallyVoucherWriteQueueEntry entry : entries) {
            String status = stringValue(entry.getStatus()).toUpperCase();
            if ("QUEUED".equals(status)) {
                queued++;
            } else if ("RETRY".equals(status)) {
                retry++;
            } else if ("PROCESSING".equals(status)) {
                processing++;
            } else if ("FAILED".equals(status)) {
                failed++;
            } else if ("APPLIED".equals(status)) {
                applied++;
            }
            String reviewState = stringValue(entry.getReviewState()).toUpperCase();
            String conflictState = stringValue(entry.getConflictState()).toUpperCase();
            if (!"SYNCED".equals(reviewState)) {
                pendingReview++;
            }
            if ("CONFLICT".equals(conflictState)) {
                conflict++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("queued", queued);
        summary.put("retry", retry);
        summary.put("processing", processing);
        summary.put("failed", failed);
        summary.put("applied", applied);
        summary.put("pending_review", pendingReview);
        summary.put("conflicts", conflict);
        summary.put("total", entries.size());
        return summary;
    }

    private List<Map<String, Object>> datasetStatuses(List<TallyDatasetSnapshot> snapshots, String company) {
        List<Map<String, Object>> statuses = new ArrayList<Map<String, Object>>();
        String normalizedCompany = normalizeCompany(company);
        for (TallyDatasetSnapshot snapshot : snapshots) {
            if (snapshot == null) {
                continue;
            }
            if (normalizedCompany != null) {
                String snapshotCompany = normalizeCompany(snapshot.getCompany());
                if (!normalizedCompany.equals(snapshotCompany)) {
                    continue;
                }
            }
            Map<String, Object> datasetStatus = new LinkedHashMap<String, Object>();
            datasetStatus.put("dataset_key", snapshot.getDatasetKey());
            datasetStatus.put("company", snapshot.getCompany());
            datasetStatus.put("snapshot_key", snapshot.getSnapshotKey());
            datasetStatus.put("snapshot_status", snapshot.getStatus());
            datasetStatus.put("row_count", snapshot.getRowCount());
            datasetStatus.put("last_synced_at", snapshot.getFetchedAt() == null ? null : snapshot.getFetchedAt().toString());
            datasetStatus.put("range_start", snapshot.getRangeStart());
            datasetStatus.put("range_end", snapshot.getRangeEnd());
            datasetStatus.put("stale", !isUsable(snapshot));
            datasetStatus.put("freshness_state", isUsable(snapshot) ? "cache" : "stale-cache");
            statuses.add(datasetStatus);
        }
        return statuses;
    }

    private Map<String, Object> reconciliationSummary(String company) {
        List<TallyVoucherWriteQueueEntry> entries = voucherWriteQueueRepository.listReviewableByCompany(normalizeCompany(company), 1000);
        int conflicts = 0;
        int alreadySyncedCandidates = 0;
        for (TallyVoucherWriteQueueEntry entry : entries) {
            if ("CONFLICT".equalsIgnoreCase(stringValue(entry.getConflictState()))) {
                conflicts++;
            }
            if ("APPROVED_MARK_SYNCED".equalsIgnoreCase(stringValue(entry.getReviewState()))) {
                alreadySyncedCandidates++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("reviewable_count", entries.size());
        summary.put("conflict_count", conflicts);
        summary.put("already_synced_candidates", alreadySyncedCandidates);
        return summary;
    }

    private Map<String, Object> appendPendingRows(Map<String, Object> response,
                                                  String connectorPath,
                                                  String uniqueKey,
                                                  PendingRowMapper mapper) {
        if (response == null) {
            return null;
        }
        Object dataValue = response.get("data");
        List<Map<String, Object>> baseRows = dataValue instanceof List
                ? new ArrayList<Map<String, Object>>((List<Map<String, Object>>) dataValue)
                : new ArrayList<Map<String, Object>>();

        List<TallyVoucherWriteQueueEntry> visibleEntries = voucherWriteQueueRepository.listVisibleByConnectorPath(
                connectorPath,
                500,
                Instant.now().minusMillis(properties.getIntervalMs() * 5)
        );
        List<TallyVoucherWriteQueueEntry> pendingEntries = pendingEntriesOnly(visibleEntries);
        if (pendingEntries.isEmpty()) {
            ensurePendingQueueMeta(response, 0);
            return response;
        }

        Set<String> existingNames = new HashSet<String>();
        for (Map<String, Object> row : baseRows) {
            String name = normalizedName(row == null ? null : row.get(uniqueKey));
            if (name != null) {
                existingNames.add(name);
            }
        }

        int appendedCount = 0;
        for (TallyVoucherWriteQueueEntry entry : pendingEntries) {
            Map<String, Object> pendingRow = mapper.map(entry);
            String name = normalizedName(pendingRow.get(uniqueKey));
            if (name == null || existingNames.contains(name)) {
                continue;
            }
            existingNames.add(name);
            baseRows.add(pendingRow);
            appendedCount++;
        }

        response.put("data", baseRows);
        ensurePendingQueueMeta(response, countPendingEntries(pendingEntries));
        return response;
    }

    private Map<String, Object> applyLatestOverlay(Map<String, Object> response,
                                                   String connectorPath,
                                                   PendingRowMapper mapper) {
        if (response == null) {
            return null;
        }
        List<TallyVoucherWriteQueueEntry> visibleEntries = voucherWriteQueueRepository.listVisibleByConnectorPath(
                connectorPath,
                20,
                Instant.now().minusMillis(properties.getIntervalMs() * 5)
        );
        List<TallyVoucherWriteQueueEntry> pendingEntries = pendingEntriesOnly(visibleEntries);
        if (pendingEntries.isEmpty()) {
            ensurePendingQueueMeta(response, 0);
            return response;
        }
        TallyVoucherWriteQueueEntry latestEntry = pendingEntries.get(pendingEntries.size() - 1);
        Map<String, Object> overlayRow = mapper.map(latestEntry);
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(overlayRow);
        Object dataValue = response.get("data");
        if (dataValue instanceof List) {
            for (Map<String, Object> existingRow : (List<Map<String, Object>>) dataValue) {
                if (existingRow == null) {
                    continue;
                }
                if (sameCompanyRecord(existingRow, overlayRow)) {
                    continue;
                }
                rows.add(existingRow);
            }
        }
        response.put("data", rows);
        ensurePendingQueueMeta(response, countPendingEntries(pendingEntries));
        return response;
    }

    private void ensurePendingQueueMeta(Map<String, Object> response, int appendedCount) {
        Object metaValue = response.get("meta");
        Map<String, Object> meta;
        if (metaValue instanceof Map) {
            meta = (Map<String, Object>) metaValue;
        } else {
            meta = new LinkedHashMap<String, Object>();
            response.put("meta", meta);
        }
        meta.put("pending_queue_count", appendedCount);
    }

    private int countPendingEntries(List<TallyVoucherWriteQueueEntry> entries) {
        int count = 0;
        for (TallyVoucherWriteQueueEntry entry : entries) {
            if (isActivePendingEntry(entry)) {
                count++;
            }
        }
        return count;
    }

    private List<TallyVoucherWriteQueueEntry> pendingEntriesOnly(List<TallyVoucherWriteQueueEntry> entries) {
        List<TallyVoucherWriteQueueEntry> pendingEntries = new ArrayList<TallyVoucherWriteQueueEntry>();
        if (entries == null || entries.isEmpty()) {
            return pendingEntries;
        }
        for (TallyVoucherWriteQueueEntry entry : entries) {
            if (isActivePendingEntry(entry)) {
                pendingEntries.add(entry);
            }
        }
        return pendingEntries;
    }

    private boolean isActivePendingEntry(TallyVoucherWriteQueueEntry entry) {
        if (entry == null) {
            return false;
        }
        String status = stringValue(entry.getStatus());
        if ("APPLIED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
            return false;
        }
        String reviewState = stringValue(entry.getReviewState());
        if ("SYNCED".equalsIgnoreCase(reviewState)
                || "SKIPPED".equalsIgnoreCase(reviewState)
                || "DISMISSED".equalsIgnoreCase(reviewState)) {
            return false;
        }
        return !"CONFLICT".equalsIgnoreCase(stringValue(entry.getConflictState()));
    }

    private Map<String, Object> pendingRow(TallyVoucherWriteQueueEntry entry, String[] keys) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        try {
            Map<String, Object> payload = objectMapper.readValue(entry.getRequestBody(), new TypeReference<Map<String, Object>>() { });
            for (String key : keys) {
                copyIfPresent(payload, row, key);
            }
        } catch (Exception ignored) {
            // Keep a minimal fallback row below.
        }
        boolean pendingSync = entry.getStatus() == null || !"APPLIED".equalsIgnoreCase(entry.getStatus());
        row.put("pending_sync", pendingSync);
        row.put("recent_write_through", !pendingSync);
        row.put("queue_id", entry.getId());
        row.put("queue_status", entry.getStatus());
        return row;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private String normalizedName(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text.toLowerCase();
    }

    private boolean sameCompanyRecord(Map<String, Object> left, Map<String, Object> right) {
        return normalizedName(left.get("NAME")) != null
                && normalizedName(left.get("NAME")).equals(normalizedName(right.get("NAME")));
    }

    private interface LiveNormalizer {
        List<Map<String, Object>> normalize(JsonNode body);
    }

    private interface PendingRowMapper {
        Map<String, Object> map(TallyVoucherWriteQueueEntry entry);
    }
}
