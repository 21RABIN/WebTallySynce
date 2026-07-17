package com.tallybackend.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TallyCacheNormalizer {

    private final ObjectMapper objectMapper;

    public TallyCacheNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> companies(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (JsonNode item : arrayOf(root.get("COMPANY"))) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", index++);
            row.put("name", text(item.get("NAME")));
            row.put("reserved_name", text(item.get("RESERVEDNAME")));
            row.put("payload_json", toJson(item));
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> groups(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (JsonNode item : arrayOf(root.get("GROUP"))) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", index++);
            row.put("name", firstText(item, "@NAME", "NAME"));
            row.put("parent", text(item.get("PARENT")));
            row.put("reserved_name", firstText(item, "@RESERVEDNAME", "RESERVEDNAME"));
            row.put("payload_json", toJson(item));
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> ledgers(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (JsonNode item : arrayOf(root.get("LEDGER"))) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", index++);
            row.put("name", text(item.get("NAME")));
            row.put("parent", text(item.get("PARENT")));
            row.put("reserved_name", text(item.get("RESERVEDNAME")));
            row.put("payload_json", toJson(item));
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> uoms(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (JsonNode item : arrayOf(root.get("UNIT"))) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", index++);
            row.put("name", firstText(item, "@NAME", "NAME"));
            row.put("original_name", text(item.get("ORIGINALNAME")));
            row.put("reserved_name", firstText(item, "@RESERVEDNAME", "RESERVEDNAME"));
            row.put("decimal_places", text(item.get("DECIMALPLACES")));
            row.put("is_simple_unit", text(item.get("ISSIMPLEUNIT")));
            row.put("payload_json", toJson(item));
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> currencies(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (root != null && root.has("items")) {
            int itemIndex = 0;
            for (JsonNode item : arrayOf(root.get("items"))) {
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("row_index", itemIndex++);
                row.put("name", text(item.get("name")));
                row.put("original_name", text(item.get("originalName")));
                row.put("symbol", text(item.get("symbol")));
                row.put("decimal_symbol", text(item.get("decimalSymbol")));
                row.put("decimal_places", text(item.get("decimalPlaces")));
                row.put("warning_note", text(item.at("/warning/note")));
                row.put("payload_json", toJson(item));
                rows.add(row);
            }
            return rows;
        }
        int index = 0;
        for (JsonNode item : arrayOf(root.get("CURRENCY"))) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", index++);
            row.put("name", firstText(item, "@NAME", "NAME"));
            row.put("original_name", text(item.get("ORIGINALNAME")));
            row.put("symbol", text(item.get("SYMBOL")));
            row.put("decimal_symbol", text(item.get("DECIMALSYMBOL")));
            row.put("decimal_places", text(item.get("DECIMALPLACES")));
            row.put("warning_note", text(item.at("/WARNING/NOTE")));
            row.put("payload_json", toJson(item));
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> stockGroups(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int index = 0;
        JsonNode stockGroupsNode = firstNode(
                root == null ? null : root.get("STOCKGROUP"),
                root == null ? null : root.get("STOCKGROUP.LIST"),
                root == null ? null : root.get("STOCKGROUPS"),
                root == null ? null : root.get("items")
        );
        for (JsonNode item : arrayOf(stockGroupsNode)) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", index++);
            row.put("name", firstText(item, "NAME", "@NAME", "name"));
            row.put("parent", firstText(item, "PARENT", "parent"));
            row.put("payload_json", toJson(item));
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> stockItems(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int index = 0;
        JsonNode itemsNode = root == null ? null : root.get("items");
        JsonNode stockItemsNode = firstNode(
                root == null ? null : root.get("STOCKITEM"),
                root == null ? null : root.get("STOCKITEM.LIST"),
                root == null ? null : root.get("STOCKITEMS"),
                itemsNode == null ? null : itemsNode.get("STOCKITEM"),
                itemsNode == null ? null : itemsNode.get("STOCKITEM.LIST"),
                itemsNode == null ? null : itemsNode.get("STOCKITEMS")
        );
        for (JsonNode item : arrayOf(stockItemsNode)) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", index++);
            row.put("name", firstText(item, "NAME", "@NAME", "name"));
            row.put("parent", firstText(item, "PARENT", "parent"));
            row.put("base_units", firstText(item, "BASEUNITS", "base_units"));
            row.put("hsn_code", firstText(item, "HSNCODE", "hsn_code"));
            row.put("gst_applicable", firstText(item, "GSTAPPLICABLE", "gst_applicable"));
            row.put("quantity", decimal(firstText(item, "CLOSINGBALANCE", "CLOSINGQTY", "OPENINGBALANCE", "QUANTITY", "quantity")));
            row.put("rate", decimal(firstText(item, "OPENINGRATE", "RATEPER", "rate")));
            row.put("item_value", decimal(firstText(item, "CLOSINGVALUE", "OPENINGVALUE", "item_value", "value")));
            row.put("payload_json", toJson(item));
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> companyCurrency(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (JsonNode item : arrayOf(root.get("COMPANY"))) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", index++);
            row.put("name", firstText(item, "@NAME", "NAME"));
            row.put("books_from", text(item.get("BOOKSFROM")));
            row.put("mailing_name", text(item.get("MAILINGNAME")));
            row.put("currency_name", text(item.get("CURRENCYNAME")));
            row.put("decimal_symbol", text(item.get("DECIMALSYMBOL")));
            row.put("payload_json", toJson(item));
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> companyFeatures(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int index = 0;
        for (JsonNode item : arrayOf(root.get("COMPANY"))) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", index++);
            row.put("name", firstText(item, "@NAME", "NAME"));
            row.put("books_from", text(item.get("BOOKSFROM")));
            row.put("email", text(item.get("EMAIL")));
            row.put("pincode", text(item.get("PINCODE")));
            row.put("country_name", text(item.get("COUNTRYNAME")));
            row.put("state_name", text(item.get("STATENAME")));
            row.put("is_inventory_on", text(item.get("ISINVENTORYON")));
            row.put("is_gst_on", text(item.get("ISGSTON")));
            row.put("payload_json", toJson(item));
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> dayBook(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int rowIndex = 0;
        for (JsonNode voucher : vouchers(root)) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", rowIndex++);
            row.put("voucher_date", text(first(voucher, "DATE")));
            row.put("voucher_type", firstText(voucher, "VOUCHERTYPENAME", "VOUCHERTYPE", "VCHTYPE"));
            row.put("voucher_number", firstText(voucher, "VOUCHERNUMBER", "REFERENCE"));
            row.put("party_ledger", firstText(voucher, "PARTYLEDGERNAME", "PARTYNAME", "LEDGERNAME"));
            row.put("amount", decimal(extractVoucherAmount(voucher)));
            row.put("narration", text(voucher.get("NARRATION")));
            row.put("payload_json", toJson(voucher));
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> ledgerVouchersFromDayBook(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        int rowIndex = 0;
        for (JsonNode voucher : vouchers(root)) {
            String voucherDate = text(first(voucher, "DATE"));
            String voucherType = firstText(voucher, "VOUCHERTYPENAME", "VCHTYPE", "VOUCHERTYPE");
            for (JsonNode entry : arrayOf(first(voucher, "LEDGERENTRIES", "ALLLEDGERENTRIES"))) {
                String ledgerName = text(entry.get("LEDGERNAME"));
                if (ledgerName.isEmpty()) {
                    continue;
                }
                BigDecimal amount = decimal(text(entry.get("AMOUNT")));
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("row_index", rowIndex++);
                row.put("voucher_date", voucherDate);
                row.put("ledger_name", ledgerName);
                row.put("voucher_type", voucherType);
                row.put("debit_amount", amount.signum() < 0 ? amount.abs() : BigDecimal.ZERO);
                row.put("credit_amount", amount.signum() > 0 ? amount.abs() : BigDecimal.ZERO);
                row.put("payload_json", toJson(entry));
                rows.add(row);
            }
        }
        return rows;
    }

    public List<Map<String, Object>> liveLedgerVouchers(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<String> dates = textArray(root.at("/ENVELOPE/DSPVCHDATE"));
        List<String> ledgers = textArray(root.at("/ENVELOPE/DSPVCHLEDACCOUNT"));
        List<String> types = textArray(root.at("/ENVELOPE/DSPVCHTYPE"));
        List<String> debits = textArray(root.at("/ENVELOPE/DSPVCHDRAMT"));
        List<String> credits = textArray(root.at("/ENVELOPE/DSPVCHCRAMT"));
        int total = Math.max(Math.max(dates.size(), ledgers.size()), Math.max(types.size(), Math.max(debits.size(), credits.size())));
        for (int index = 0; index < total; index++) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", index);
            row.put("voucher_date", at(dates, index));
            row.put("ledger_name", at(ledgers, index));
            row.put("voucher_type", at(types, index));
            row.put("debit_amount", decimal(at(debits, index)));
            row.put("credit_amount", decimal(at(credits, index)));
            row.put("payload_json", "{}");
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> balanceSheet(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<String> names = textArray(root.at("/ENVELOPE/BSNAME"));
        List<BigDecimal> amounts = amountArray(root.at("/ENVELOPE/BSAMT"));
        int total = Math.max(names.size(), amounts.size());
        boolean assetSection = false;
        for (int index = 0; index < total; index++) {
            String name = at(names, index);
            if (name.isEmpty()) {
                continue;
            }
            BigDecimal amount = index < amounts.size() ? amounts.get(index) : BigDecimal.ZERO;
            String kind = classifyBalanceSheetKind(name, amount, assetSection);
            if ("ASSET".equals(kind)) {
                assetSection = true;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", rows.size());
            row.put("name", name);
            row.put("amount", amount.abs());
            row.put("kind", kind);
            row.put("payload_json", "{}");
            rows.add(row);
        }
        return rebalanceBalanceSheetRows(rows);
    }

    public List<Map<String, Object>> profitLoss(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<String> names = textArray(root.at("/ENVELOPE/DSPACCNAME"));
        List<BigDecimal> amounts = amountArray(root.at("/ENVELOPE/PLAMT"));
        int total = Math.max(names.size(), amounts.size());
        boolean expenseSection = false;
        for (int index = 0; index < total; index++) {
            String name = at(names, index);
            if (name.isEmpty()) {
                continue;
            }
            if (shouldSkipProfitLossRow(names, index)) {
                continue;
            }
            BigDecimal amount = index < amounts.size() ? amounts.get(index) : BigDecimal.ZERO;
            String kind = classifyProfitLossKind(name, expenseSection);
            if ("EXPENSE".equals(kind)) {
                expenseSection = true;
            }
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("row_index", rows.size());
            row.put("name", name);
            row.put("amount", amount.abs());
            row.put("kind", kind);
            row.put("payload_json", "{}");
            rows.add(row);
        }
        return rows;
    }

    public List<Map<String, Object>> genericRows(JsonNode root) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        JsonNode listNode = firstMeaningfulCollection(root);
        if (listNode != null && listNode.isArray()) {
            int index = 0;
            for (JsonNode item : listNode) {
                rows.add(genericRow(item, index++));
            }
            return rows;
        }
        rows.add(genericRow(root, 0));
        return rows;
    }

    private Iterable<JsonNode> vouchers(JsonNode root) {
        List<JsonNode> vouchers = new ArrayList<JsonNode>();
        for (JsonNode message : arrayOf(first(root, "TALLYMESSAGE", "ENVELOPE"))) {
            if (message.has("BODY")) {
                JsonNode bodyData = message.path("BODY").path("DATA");
                for (JsonNode nestedMessage : arrayOf(bodyData.get("TALLYMESSAGE"))) {
                    addVouchers(vouchers, nestedMessage.get("VOUCHER"));
                }
            } else {
                addVouchers(vouchers, message.get("VOUCHER"));
            }
        }
        return vouchers;
    }

    private void addVouchers(List<JsonNode> target, JsonNode value) {
        for (JsonNode item : arrayOf(value)) {
            target.add(item);
        }
    }

    private JsonNode first(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            JsonNode current = node.get(key);
            if (current != null && !current.isMissingNode() && !current.isNull()) {
                return current;
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = text(node.get(key));
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private String extractVoucherAmount(JsonNode voucher) {
        String party = firstText(voucher, "PARTYLEDGERNAME", "PARTYNAME");
        String fallback = "";
        for (JsonNode entry : arrayOf(first(voucher, "LEDGERENTRIES", "ALLLEDGERENTRIES"))) {
            String amount = text(entry.get("AMOUNT"));
            String ledgerName = text(entry.get("LEDGERNAME"));
            if (fallback.isEmpty() && !amount.isEmpty()) {
                fallback = amount;
            }
            if (!party.isEmpty() && party.equalsIgnoreCase(ledgerName) && !amount.isEmpty()) {
                return amount;
            }
        }
        return fallback;
    }

    private String classifyBalanceSheetKind(String name, BigDecimal amount, boolean assetSectionStarted) {
        if (amount != null) {
            if (amount.signum() < 0) {
                return "ASSET";
            }
            if (amount.signum() > 0) {
                return "LIABILITY";
            }
        }
        String normalized = normalizedLabel(name);
        if (normalized.contains("asset")
                || normalized.contains("profit & loss")
                || normalized.contains("profit and loss")
                || normalized.contains("stock in hand")
                || normalized.contains("cash-in-hand")
                || normalized.contains("bank accounts")
                || normalized.contains("branch / divisions")
                || normalized.contains("branch/divisions")
                || normalized.contains("sundry debtors")
                || normalized.contains("deposits")
                || normalized.contains("loans & advances")
                || normalized.contains("loans and advances")
                || normalized.contains("fixed assets")
                || normalized.contains("investments")
        ) {
            return "ASSET";
        }
        if (normalized.contains("capital")
                || normalized.contains("liabilit")
                || normalized.contains("duties")
                || normalized.contains("provisions")
                || normalized.contains("reserves")
                || normalized.contains("secured loans")
                || normalized.contains("unsecured loans")
                || normalized.contains("suspense")
                || normalized.contains("sundry creditors")
        ) {
            return "LIABILITY";
        }
        return assetSectionStarted ? "ASSET" : "LIABILITY";
    }

    private List<Map<String, Object>> rebalanceBalanceSheetRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return rows;
        }

        BigDecimal liabilitiesTotal = BigDecimal.ZERO;
        BigDecimal assetsTotal = BigDecimal.ZERO;
        boolean hasDifferenceRow = false;
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            String kind = normalizedLabel(stringValue(row.get("kind")));
            String name = normalizedLabel(stringValue(row.get("name")));
            BigDecimal amount = decimal(stringValue(row.get("amount")));
            if (name.contains("difference in opening balances")) {
                hasDifferenceRow = true;
            }
            if ("liability".equals(kind)) {
                liabilitiesTotal = liabilitiesTotal.add(amount.abs());
            } else if ("asset".equals(kind)) {
                assetsTotal = assetsTotal.add(amount.abs());
            }
        }

        BigDecimal difference = assetsTotal.subtract(liabilitiesTotal);
        if (hasDifferenceRow || difference.compareTo(BigDecimal.ZERO) == 0) {
            return rows;
        }

        Map<String, Object> balancingRow = new LinkedHashMap<String, Object>();
        balancingRow.put("row_index", rows.size());
        balancingRow.put("name", "Difference in opening balances");
        balancingRow.put("amount", difference.abs());
        balancingRow.put("kind", difference.signum() > 0 ? "LIABILITY" : "ASSET");
        balancingRow.put("payload_json", "{}");
        rows.add(balancingRow);
        return rows;
    }

    private String classifyProfitLossKind(String name, boolean expenseSectionStarted) {
        String normalized = normalizedLabel(name);
        if (normalized.contains("closing stock")
                || normalized.contains("opening stock")) {
            return "INCOME";
        }
        if (normalized.contains("purchase")
                || normalized.contains("cost of sales")
                || normalized.contains("direct expenses")
                || normalized.contains("indirect expenses")
                || normalized.startsWith("add:")
                || normalized.startsWith("less:")
                || expenseSectionStarted) {
            return "EXPENSE";
        }
        if (normalized.contains("sales")
                || normalized.contains("income")
                || normalized.contains("direct income")
                || normalized.contains("indirect income")) {
            return "INCOME";
        }
        return expenseSectionStarted ? "EXPENSE" : "INCOME";
    }

    private boolean shouldSkipProfitLossRow(List<String> names, int index) {
        String normalized = normalizedLabel(at(names, index));
        if (!normalized.endsWith(":")) {
            return false;
        }
        String next = normalizedLabel(at(names, index + 1));
        String nextAfter = normalizedLabel(at(names, index + 2));
        return next.contains("opening stock")
                || next.startsWith("add:")
                || next.startsWith("less:")
                || nextAfter.startsWith("add:")
                || nextAfter.startsWith("less:");
    }

    private String normalizedLabel(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    private List<String> textArray(JsonNode node) {
        List<String> values = new ArrayList<String>();
        for (JsonNode item : arrayOf(node)) {
            values.add(text(item));
        }
        return values;
    }

    private List<BigDecimal> amountArray(JsonNode node) {
        List<BigDecimal> values = new ArrayList<BigDecimal>();
        for (JsonNode item : arrayOf(node)) {
            if (item != null && item.isObject()) {
                values.add(decimal(firstText(item, "BSMAINAMT", "PLSUBAMT", "BSSUBAMT", "PLMAINAMT")));
            } else {
                values.add(decimal(text(item)));
            }
        }
        return values;
    }

    private JsonNode firstNode(JsonNode... candidates) {
        for (JsonNode candidate : candidates) {
            if (candidate != null && !candidate.isNull() && !candidate.isMissingNode()) {
                return candidate;
            }
        }
        return null;
    }

    private Iterable<JsonNode> arrayOf(JsonNode node) {
        List<JsonNode> nodes = new ArrayList<JsonNode>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return nodes;
        }
        if (node.isArray()) {
            Iterator<JsonNode> iterator = node.elements();
            while (iterator.hasNext()) {
                nodes.add(iterator.next());
            }
            return nodes;
        }
        nodes.add(node);
        return nodes;
    }

    private JsonNode firstMeaningfulCollection(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return null;
        }
        if (root.isArray()) {
            return root;
        }
        if (root.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode value = field.getValue();
                if (value != null && value.isArray()) {
                    return value;
                }
            }
        }
        return null;
    }

    private Map<String, Object> genericRow(JsonNode node, int index) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("row_index", index);
        row.putAll(toPlainMap(node));
        row.put("payload_json", toJson(node));
        return row;
    }

    private Map<String, Object> toPlainMap(JsonNode node) {
        try {
            if (node == null || node.isNull() || node.isMissingNode()) {
                return new LinkedHashMap<String, Object>();
            }
            return objectMapper.convertValue(node, Map.class);
        } catch (IllegalArgumentException ignored) {
            Map<String, Object> fallback = new LinkedHashMap<String, Object>();
            fallback.put("value", text(node));
            return fallback;
        }
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = text(item);
                if (!value.isEmpty()) {
                    return value;
                }
            }
            return "";
        }
        if (node.isObject()) {
            JsonNode textNode = node.get("#text");
            if (textNode != null) {
                return text(textNode);
            }
            JsonNode valueNode = node.get("value");
            if (valueNode != null) {
                return text(valueNode);
            }
            JsonNode textValueNode = node.get("text");
            if (textValueNode != null) {
                return text(textValueNode);
            }
            JsonNode nameTextNode = node.get("@NAME");
            if (nameTextNode != null && node.size() == 1) {
                return text(nameTextNode);
            }
            JsonNode nameNode = node.get("NAME");
            if (nameNode != null && node.size() == 1) {
                return text(nameNode);
            }
        }
        return node.asText("").trim();
    }

    private BigDecimal decimal(String raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        String cleaned = raw.replaceAll("[^0-9.\\-]", "").trim();
        if (cleaned.isEmpty() || "-".equals(cleaned)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node == null ? objectMapper.createObjectNode() : node);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private String at(List<String> values, int index) {
        if (index < 0 || index >= values.size()) {
            return "";
        }
        return values.get(index) == null ? "" : values.get(index);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
