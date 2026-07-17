package com.tallybackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class TallyQueueEntityMetadataService {

    private final ObjectMapper objectMapper;

    public TallyQueueEntityMetadataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void enrichEntry(TallyVoucherWriteQueueEntry entry) {
        if (entry == null) {
            return;
        }
        entry.setEntityType(resolveEntityType(entry.getConnectorPath()));
        entry.setEntityName(resolveEntityName(entry));
        entry.setSyncDirection("DB_TO_TALLY");
        if (isBlank(entry.getConflictState())) {
            entry.setConflictState("NONE");
        }
        if (isBlank(entry.getReviewState())) {
            entry.setReviewState("PENDING_REVIEW");
        }
        entry.setPreviewFields(extractPreviewFields(entry));
    }

    public String resolveEntityType(String connectorPath) {
        String path = normalize(connectorPath);
        if (path.startsWith("/vouchers")) {
            return "voucher";
        }
        if ("/groups".equals(path)) {
            return "group";
        }
        if ("/ledgers".equals(path)) {
            return "ledger";
        }
        if ("/uoms".equals(path)) {
            return "uom";
        }
        if ("/stock-groups".equals(path)) {
            return "stock_group";
        }
        if ("/stock-items".equals(path)) {
            return "stock_item";
        }
        if ("/settings/company-currency".equals(path)) {
            return "company_currency";
        }
        if ("/settings/company-features".equals(path)) {
            return "company_features";
        }
        if ("/currencies".equals(path)) {
            return "currency";
        }
        if ("/ledger-groups".equals(path)) {
            return "ledger_group";
        }
        if ("/cost-categories".equals(path)) {
            return "cost_category";
        }
        if ("/cost-centres".equals(path)) {
            return "cost_centre";
        }
        if ("/projects".equals(path)) {
            return "project";
        }
        if ("/godowns".equals(path)) {
            return "godown";
        }
        if ("/stock-categories".equals(path)) {
            return "stock_category";
        }
        if ("/boms".equals(path)) {
            return "bom";
        }
        if ("/price-levels".equals(path)) {
            return "price_level";
        }
        if ("/price-lists".equals(path)) {
            return "price_list";
        }
        if ("/voucher-types".equals(path)) {
            return "voucher_type";
        }
        if ("/budgets".equals(path)) {
            return "budget";
        }
        return "unknown";
    }

    public String resolveEntityName(TallyVoucherWriteQueueEntry entry) {
        Map<String, Object> payload = parseBody(entry == null ? null : entry.getRequestBody());
        String entityType = resolveEntityType(entry == null ? null : entry.getConnectorPath());
        if ("voucher".equals(entityType)) {
            Map<String, Object> voucher = unwrapVoucher(payload);
            String voucherType = text(firstNonNull(voucher.get("VOUCHERTYPENAME"), inferVoucherType(entry == null ? null : entry.getConnectorPath())));
            String voucherNumber = firstNonBlank(
                    text(entry == null ? null : entry.getOriginalVoucherNumber()),
                    text(voucher.get("ORIGINALVOUCHERNUMBER")),
                    text(voucher.get("originalVoucherNumber")),
                    text(voucher.get("original_voucher_number")),
                    text(nonOfflineValue(voucher.get("REFERENCE"))),
                    text(voucher.get("VOUCHERNUMBER"))
            );
            String date = text(firstNonNull(voucher.get("DATE"), voucher.get("EFFECTIVEDATE")));
            return joinNonBlank(" ", voucherType, voucherNumber, date);
        }
        return firstNonBlank(
                text(payload.get("NAME")),
                text(payload.get("name")),
                text(payload.get("MAILINGNAME")),
                text(payload.get("mailingName"))
        );
    }

    public Map<String, Object> extractPreviewFields(TallyVoucherWriteQueueEntry entry) {
        Map<String, Object> payload = parseBody(entry == null ? null : entry.getRequestBody());
        String entityType = resolveEntityType(entry == null ? null : entry.getConnectorPath());
        Map<String, Object> preview = new LinkedHashMap<String, Object>();
        if ("voucher".equals(entityType)) {
            Map<String, Object> voucher = unwrapVoucher(payload);
            putIfPresent(preview, "VOUCHERTYPENAME", firstNonNull(voucher.get("VOUCHERTYPENAME"), inferVoucherType(entry == null ? null : entry.getConnectorPath())));
            putIfPresent(preview, "VOUCHERNUMBER", firstNonNull(
                    entry == null ? null : entry.getOriginalVoucherNumber(),
                    voucher.get("ORIGINALVOUCHERNUMBER"),
                    voucher.get("originalVoucherNumber"),
                    voucher.get("original_voucher_number"),
                    nonOfflineValue(voucher.get("REFERENCE")),
                    voucher.get("VOUCHERNUMBER")
            ));
            putIfPresent(preview, "OFFLINEVOUCHERNUMBER", firstNonNull(
                    entry == null ? null : entry.getOfflineVoucherNumber(),
                    voucher.get("OFFLINEVOUCHERNUMBER"),
                    voucher.get("offlineVoucherNumber"),
                    voucher.get("offline_voucher_number")
            ));
            putIfPresent(preview, "DATE", firstNonNull(voucher.get("DATE"), voucher.get("EFFECTIVEDATE")));
            putIfPresent(preview, "PARTYLEDGERNAME", voucher.get("PARTYLEDGERNAME"));
            putIfPresent(preview, "AMOUNT", previewVoucherAmount(voucher));
            putIfPresent(preview, "TAXABLEAMOUNT", previewVoucherTaxableAmount(voucher));
            putIfPresent(preview, "GSTAMOUNT", previewVoucherGstAmount(voucher));
            putIfPresent(preview, "EXTRALEDGERAMOUNT", previewVoucherExtraLedgerAmount(voucher));
            putIfPresent(preview, "VOUCHERTOTAL", previewVoucherTotal(voucher));
            putIfPresent(preview, "CHARGELEDGERS", previewVoucherChargeLedgers(voucher));
            return preview;
        }
        if ("stock_item".equals(entityType)) {
            copy(preview, payload, "NAME", "PARENT", "BASEUNITS", "HSNCODE", "GSTAPPLICABLE");
            return preview;
        }
        if ("uom".equals(entityType)) {
            copy(preview, payload, "NAME", "ORIGINALNAME");
            return preview;
        }
        if ("group".equals(entityType) || "ledger".equals(entityType) || "stock_group".equals(entityType)) {
            copy(preview, payload, "NAME", "PARENT");
            return preview;
        }
        if ("company_currency".equals(entityType)) {
            copy(preview, payload, "NAME", "BOOKSFROM", "MAILINGNAME", "CURRENCYNAME", "DECIMALSYMBOL");
            return preview;
        }
        if ("company_features".equals(entityType)) {
            copy(preview, payload, "NAME", "BOOKSFROM", "EMAIL", "PINCODE", "COUNTRYNAME", "STATENAME", "ISINVENTORYON", "ISGSTON");
            return preview;
        }
        if (payload.containsKey("NAME")) {
            preview.put("NAME", payload.get("NAME"));
        } else if (payload.containsKey("name")) {
            preview.put("NAME", payload.get("name"));
        }
        return preview;
    }

    public Map<String, Object> parsePayloadObject(TallyVoucherWriteQueueEntry entry) {
        return parseBody(entry == null ? null : entry.getRequestBody());
    }

    private Object previewVoucherAmount(Map<String, Object> voucher) {
        Object partyTotal = previewVoucherTotal(voucher);
        if (partyTotal != null) {
            return partyTotal;
        }
        if (voucher == null) {
            return null;
        }
        Object amount = voucher.get("AMOUNT");
        if (amount != null) {
            return amount;
        }
        BigDecimal total = BigDecimal.ZERO;
        List<Map<String, Object>> ledgerEntries = toMapList(firstNonNull(voucher.get("ALLLEDGERENTRIES.LIST"), voucher.get("ALLLEDGERENTRIES")));
        for (Map<String, Object> item : ledgerEntries) {
            BigDecimal parsed = decimal(item.get("AMOUNT"));
            if (parsed == null) {
                continue;
            }
            total = total.add(parsed.abs());
        }
        return total.compareTo(BigDecimal.ZERO) == 0 ? null : total;
    }

    private Object previewVoucherTaxableAmount(Map<String, Object> voucher) {
        if (voucher == null) {
            return null;
        }
        BigDecimal taxable = BigDecimal.ZERO;
        boolean hasInventoryAmounts = false;
        for (Map<String, Object> inventoryEntry : inventoryEntries(voucher)) {
            List<Map<String, Object>> allocations = toMapList(firstNonNull(
                    inventoryEntry.get("ACCOUNTINGALLOCATIONS.LIST"),
                    inventoryEntry.get("ACCOUNTINGALLOCATIONS")
            ));
            if (!allocations.isEmpty()) {
                for (Map<String, Object> allocation : allocations) {
                    BigDecimal parsed = decimal(allocation.get("AMOUNT"));
                    if (parsed == null) {
                        continue;
                    }
                    taxable = taxable.add(parsed.abs());
                    hasInventoryAmounts = true;
                }
                continue;
            }
            BigDecimal parsed = decimal(inventoryEntry.get("AMOUNT"));
            if (parsed == null) {
                continue;
            }
            taxable = taxable.add(parsed.abs());
            hasInventoryAmounts = true;
        }
        if (hasInventoryAmounts && taxable.compareTo(BigDecimal.ZERO) > 0) {
            return taxable;
        }
        return null;
    }

    private Object previewVoucherGstAmount(Map<String, Object> voucher) {
        return chargeTotals(voucher).get("gst");
    }

    private Object previewVoucherExtraLedgerAmount(Map<String, Object> voucher) {
        return chargeTotals(voucher).get("extra");
    }

    private Object previewVoucherTotal(Map<String, Object> voucher) {
        if (voucher == null) {
            return null;
        }
        String partyLedgerName = text(voucher.get("PARTYLEDGERNAME"));
        for (Map<String, Object> ledgerEntry : ledgerEntries(voucher)) {
            String ledgerName = firstNonBlank(text(ledgerEntry.get("LEDGERNAME")), text(ledgerEntry.get("ledgerName")));
            if (!partyLedgerName.isEmpty() && partyLedgerName.equalsIgnoreCase(text(ledgerName))) {
                BigDecimal amount = decimal(ledgerEntry.get("AMOUNT"));
                if (amount != null) {
                    return amount.abs();
                }
            }
            if (Boolean.TRUE.equals(asBoolean(ledgerEntry.get("ISPARTYLEDGER")))) {
                BigDecimal amount = decimal(ledgerEntry.get("AMOUNT"));
                if (amount != null) {
                    return amount.abs();
                }
            }
        }
        return null;
    }

    private Object previewVoucherChargeLedgers(Map<String, Object> voucher) {
        if (voucher == null) {
            return null;
        }
        List<String> labels = new ArrayList<String>();
        for (Map<String, Object> ledgerEntry : chargeLedgerEntries(voucher)) {
            String ledgerName = firstNonBlank(text(ledgerEntry.get("LEDGERNAME")), text(ledgerEntry.get("ledgerName")));
            BigDecimal amount = decimal(ledgerEntry.get("AMOUNT"));
            if (isBlank(ledgerName) || amount == null) {
                continue;
            }
            labels.add(ledgerName + " " + amount.abs().toPlainString());
        }
        return labels.isEmpty() ? null : String.join(", ", labels);
    }

    private Map<String, Object> unwrapVoucher(Map<String, Object> payload) {
        if (payload == null) {
            return new LinkedHashMap<String, Object>();
        }
        Object wrapped = payload.get("VOUCHER");
        if (wrapped instanceof Map) {
            return (Map<String, Object>) wrapped;
        }
        if (wrapped instanceof List && !((List<?>) wrapped).isEmpty() && ((List<?>) wrapped).get(0) instanceof Map) {
            return (Map<String, Object>) ((List<?>) wrapped).get(0);
        }
        Object lower = payload.get("voucher");
        if (lower instanceof Map) {
            return (Map<String, Object>) lower;
        }
        return payload;
    }

    private List<Map<String, Object>> toMapList(Object value) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (item instanceof Map) {
                    items.add((Map<String, Object>) item);
                }
            }
        } else if (value instanceof Map) {
            items.add((Map<String, Object>) value);
        }
        return items;
    }

    private List<Map<String, Object>> ledgerEntries(Map<String, Object> voucher) {
        if (voucher == null) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> items = toMapList(firstNonNull(voucher.get("LEDGERENTRIES.LIST"), voucher.get("LEDGERENTRIES")));
        if (!items.isEmpty()) {
            return items;
        }
        return toMapList(firstNonNull(voucher.get("ALLLEDGERENTRIES.LIST"), voucher.get("ALLLEDGERENTRIES")));
    }

    private List<Map<String, Object>> inventoryEntries(Map<String, Object> voucher) {
        if (voucher == null) {
            return Collections.emptyList();
        }
        return toMapList(firstNonNull(voucher.get("ALLINVENTORYENTRIES.LIST"), voucher.get("ALLINVENTORYENTRIES")));
    }

    private List<Map<String, Object>> chargeLedgerEntries(Map<String, Object> voucher) {
        if (voucher == null) {
            return Collections.emptyList();
        }
        String partyLedgerName = text(voucher.get("PARTYLEDGERNAME"));
        Set<String> salesLedgerNames = new LinkedHashSet<String>();
        for (Map<String, Object> inventoryEntry : inventoryEntries(voucher)) {
            for (Map<String, Object> allocation : toMapList(firstNonNull(
                    inventoryEntry.get("ACCOUNTINGALLOCATIONS.LIST"),
                    inventoryEntry.get("ACCOUNTINGALLOCATIONS")
            ))) {
                String ledgerName = firstNonBlank(text(allocation.get("LEDGERNAME")), text(allocation.get("ledgerName")));
                if (!isBlank(ledgerName)) {
                    salesLedgerNames.add(ledgerName.trim().toLowerCase(Locale.ROOT));
                }
            }
        }

        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> ledgerEntry : ledgerEntries(voucher)) {
            String ledgerName = firstNonBlank(text(ledgerEntry.get("LEDGERNAME")), text(ledgerEntry.get("ledgerName")));
            if (isBlank(ledgerName)) {
                continue;
            }
            String normalizedLedger = ledgerName.trim().toLowerCase(Locale.ROOT);
            if (!partyLedgerName.isEmpty() && partyLedgerName.equalsIgnoreCase(ledgerName)) {
                continue;
            }
            if (salesLedgerNames.contains(normalizedLedger)) {
                continue;
            }
            if (Boolean.TRUE.equals(asBoolean(ledgerEntry.get("ISPARTYLEDGER")))) {
                continue;
            }
            items.add(ledgerEntry);
        }
        return items;
    }

    private Map<String, BigDecimal> chargeTotals(Map<String, Object> voucher) {
        BigDecimal gst = BigDecimal.ZERO;
        BigDecimal extra = BigDecimal.ZERO;
        for (Map<String, Object> ledgerEntry : chargeLedgerEntries(voucher)) {
            BigDecimal amount = decimal(ledgerEntry.get("AMOUNT"));
            if (amount == null) {
                continue;
            }
            String ledgerName = firstNonBlank(text(ledgerEntry.get("LEDGERNAME")), text(ledgerEntry.get("ledgerName")));
            if (isGstLedgerName(ledgerName)) {
                gst = gst.add(amount.abs());
            } else {
                extra = extra.add(amount.abs());
            }
        }
        Map<String, BigDecimal> totals = new LinkedHashMap<String, BigDecimal>();
        totals.put("gst", gst.compareTo(BigDecimal.ZERO) == 0 ? null : gst);
        totals.put("extra", extra.compareTo(BigDecimal.ZERO) == 0 ? null : extra);
        return totals;
    }

    private boolean isGstLedgerName(String ledgerName) {
        String normalized = normalize(ledgerName);
        return normalized.contains("gst")
                || normalized.contains("igst")
                || normalized.contains("cgst")
                || normalized.contains("sgst")
                || normalized.contains("utgst")
                || normalized.contains("cess")
                || normalized.contains("tax");
    }

    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
    }

    private Map<String, Object> parseBody(String requestBody) {
        if (isBlank(requestBody)) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return objectMapper.readValue(requestBody, new TypeReference<Map<String, Object>>() { });
        } catch (Exception ignored) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private void copy(Map<String, Object> target, Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            if (payload.containsKey(key)) {
                target.put(key, payload.get(key));
            }
        }
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !text(value).isEmpty()) {
            target.put(key, value);
        }
    }

    private Object inferVoucherType(String connectorPath) {
        String path = normalize(connectorPath);
        if (!path.startsWith("/vouchers/")) {
            return null;
        }
        String[] parts = path.split("/");
        if (parts.length < 3) {
            return null;
        }
        return capitalizeWords(parts[2].replace('-', ' '));
    }

    private String capitalizeWords(String value) {
        if (isBlank(value)) {
            return "";
        }
        String[] parts = value.trim().split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return builder.toString();
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Object nonOfflineValue(Object value) {
        String text = text(value);
        if (isBlank(text) || text.toUpperCase(Locale.ROOT).startsWith("OFF-")) {
            return null;
        }
        return text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String joinNonBlank(String delimiter, String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (isBlank(value)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(delimiter);
            }
            builder.append(value.trim());
        }
        return builder.toString();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        return text.isEmpty() ? "" : text;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
