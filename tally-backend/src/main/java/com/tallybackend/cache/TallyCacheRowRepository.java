package com.tallybackend.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Repository
public class TallyCacheRowRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TallyCacheRowRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void replaceRows(TallyCacheDataset dataset, Long snapshotId, List<Map<String, Object>> rows) {
        String table = tableName(dataset);
        jdbcTemplate.update("DELETE FROM " + table + " WHERE snapshot_id = ?", snapshotId);
        for (Map<String, Object> row : rows) {
            insertRow(dataset, snapshotId, row);
        }
    }

    public void deleteRows(TallyCacheDataset dataset, Long snapshotId) {
        if (snapshotId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM " + tableName(dataset) + " WHERE snapshot_id = ?", snapshotId);
    }

    public List<Map<String, Object>> readRows(TallyCacheDataset dataset, Long snapshotId) {
        if (snapshotId == null) {
            return Collections.emptyList();
        }
        switch (dataset) {
            case COMPANIES:
                return jdbcTemplate.query("SELECT name, reserved_name FROM tally_companies_snapshot_rows WHERE snapshot_id = ? ORDER BY row_index", companiesRowMapper(), snapshotId);
            case GROUPS:
                return jdbcTemplate.query("SELECT name, parent, reserved_name FROM tally_groups_snapshot_rows WHERE snapshot_id = ? ORDER BY row_index", groupsRowMapper(), snapshotId);
            case LEDGERS:
                return jdbcTemplate.query("SELECT name, parent, reserved_name FROM tally_ledgers_snapshot_rows WHERE snapshot_id = ? ORDER BY row_index", ledgersRowMapper(), snapshotId);
            case UOMS:
                return jdbcTemplate.query("SELECT name, original_name, reserved_name, decimal_places, is_simple_unit FROM tally_uoms_snapshot_rows WHERE snapshot_id = ? ORDER BY row_index", uomsRowMapper(), snapshotId);
            case CURRENCIES:
                return jdbcTemplate.query("SELECT name, original_name, symbol, decimal_symbol, decimal_places, warning_note FROM tally_currencies_snapshot_rows WHERE snapshot_id = ? ORDER BY row_index", currenciesRowMapper(), snapshotId);
            case STOCK_GROUPS:
                return jdbcTemplate.query("SELECT name, parent FROM tally_stock_groups_snapshot_rows WHERE snapshot_id = ? ORDER BY row_index", stockGroupsRowMapper(), snapshotId);
            case STOCK_ITEMS:
                return jdbcTemplate.query("SELECT name, parent, base_units, gst_applicable, quantity, rate, item_value, payload_json FROM tally_stock_items_snapshot_rows WHERE snapshot_id = ? ORDER BY row_index", stockItemsRowMapper(), snapshotId);
            case COMPANY_CURRENCY:
                return jdbcTemplate.query("SELECT name, books_from, mailing_name, currency_name, decimal_symbol FROM tally_company_currency_snapshot_rows WHERE snapshot_id = ? ORDER BY row_index", companyCurrencyRowMapper(), snapshotId);
            case COMPANY_FEATURES:
                return jdbcTemplate.query("SELECT name, books_from, email, pincode, country_name, state_name, is_inventory_on, is_gst_on FROM tally_company_features_snapshot_rows WHERE snapshot_id = ? ORDER BY row_index", companyFeaturesRowMapper(), snapshotId);
            case DAY_BOOK:
                return jdbcTemplate.query("SELECT voucher_date, voucher_type, voucher_number, party_ledger, amount, narration, payload_json FROM tally_day_book_snapshot_rows WHERE snapshot_id = ? ORDER BY row_index", dayBookRowMapper(), snapshotId);
            case BALANCE_SHEET:
                return jdbcTemplate.query("SELECT name, amount, kind FROM tally_balance_sheet_snapshot_rows WHERE snapshot_id = ? ORDER BY row_index", statementRowMapper(), snapshotId);
            case PROFIT_LOSS:
                return jdbcTemplate.query("SELECT name, amount, kind FROM tally_profit_loss_snapshot_rows WHERE snapshot_id = ? ORDER BY row_index", statementRowMapper(), snapshotId);
            default:
                return jdbcTemplate.query("SELECT payload_json FROM tally_generic_snapshot_rows WHERE snapshot_id = ? ORDER BY row_index", genericRowMapper(), snapshotId);
        }
    }

    public List<Map<String, Object>> readLedgerVoucherRows(Long snapshotId, String fromDate, String toDate, String ledgerName) {
        if (snapshotId == null) {
            return Collections.emptyList();
        }
        String sql = "SELECT voucher_date, ledger_name, voucher_type, debit_amount, credit_amount " +
                "FROM tally_ledger_vouchers_snapshot_rows WHERE snapshot_id = ? " +
                "AND (? IS NULL OR ? = '' OR voucher_date >= ?) " +
                "AND (? IS NULL OR ? = '' OR voucher_date <= ?) " +
                "AND (? IS NULL OR ? = '' OR LOWER(ledger_name) = LOWER(?)) " +
                "ORDER BY voucher_date, row_index";
        return jdbcTemplate.query(sql, ledgerVoucherRowMapper(),
                snapshotId,
                fromDate, fromDate, fromDate,
                toDate, toDate, toDate,
                ledgerName, ledgerName, ledgerName);
    }

    private void insertRow(TallyCacheDataset dataset, Long snapshotId, Map<String, Object> row) {
        switch (dataset) {
            case COMPANIES:
                jdbcTemplate.update("INSERT INTO tally_companies_snapshot_rows (snapshot_id, row_index, name, reserved_name, payload_json) VALUES (?, ?, ?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("name"), row.get("reserved_name"), row.get("payload_json"));
                return;
            case GROUPS:
                jdbcTemplate.update("INSERT INTO tally_groups_snapshot_rows (snapshot_id, row_index, name, parent, reserved_name, payload_json) VALUES (?, ?, ?, ?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("name"), row.get("parent"), row.get("reserved_name"), row.get("payload_json"));
                return;
            case LEDGERS:
                jdbcTemplate.update("INSERT INTO tally_ledgers_snapshot_rows (snapshot_id, row_index, name, parent, reserved_name, payload_json) VALUES (?, ?, ?, ?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("name"), row.get("parent"), row.get("reserved_name"), row.get("payload_json"));
                return;
            case UOMS:
                jdbcTemplate.update("INSERT INTO tally_uoms_snapshot_rows (snapshot_id, row_index, name, original_name, reserved_name, decimal_places, is_simple_unit, payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("name"), row.get("original_name"), row.get("reserved_name"), row.get("decimal_places"), row.get("is_simple_unit"), row.get("payload_json"));
                return;
            case CURRENCIES:
                jdbcTemplate.update("INSERT INTO tally_currencies_snapshot_rows (snapshot_id, row_index, name, original_name, symbol, decimal_symbol, decimal_places, warning_note, payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("name"), row.get("original_name"), row.get("symbol"), row.get("decimal_symbol"), row.get("decimal_places"), row.get("warning_note"), row.get("payload_json"));
                return;
            case STOCK_GROUPS:
                jdbcTemplate.update("INSERT INTO tally_stock_groups_snapshot_rows (snapshot_id, row_index, name, parent, payload_json) VALUES (?, ?, ?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("name"), row.get("parent"), row.get("payload_json"));
                return;
            case STOCK_ITEMS:
                jdbcTemplate.update("INSERT INTO tally_stock_items_snapshot_rows (snapshot_id, row_index, name, parent, base_units, gst_applicable, quantity, rate, item_value, payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("name"), row.get("parent"), row.get("base_units"), row.get("gst_applicable"),
                        row.get("quantity"), row.get("rate"), row.get("item_value"), row.get("payload_json"));
                return;
            case COMPANY_CURRENCY:
                jdbcTemplate.update("INSERT INTO tally_company_currency_snapshot_rows (snapshot_id, row_index, name, books_from, mailing_name, currency_name, decimal_symbol, payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("name"), row.get("books_from"), row.get("mailing_name"), row.get("currency_name"), row.get("decimal_symbol"), row.get("payload_json"));
                return;
            case COMPANY_FEATURES:
                jdbcTemplate.update("INSERT INTO tally_company_features_snapshot_rows (snapshot_id, row_index, name, books_from, email, pincode, country_name, state_name, is_inventory_on, is_gst_on, payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("name"), row.get("books_from"), row.get("email"), row.get("pincode"), row.get("country_name"), row.get("state_name"), row.get("is_inventory_on"), row.get("is_gst_on"), row.get("payload_json"));
                return;
            case DAY_BOOK:
                jdbcTemplate.update("INSERT INTO tally_day_book_snapshot_rows (snapshot_id, row_index, voucher_date, voucher_type, voucher_number, party_ledger, amount, narration, payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("voucher_date"), row.get("voucher_type"), row.get("voucher_number"),
                        row.get("party_ledger"), row.get("amount"), row.get("narration"), row.get("payload_json"));
                return;
            case LEDGER_VOUCHERS:
                jdbcTemplate.update("INSERT INTO tally_ledger_vouchers_snapshot_rows (snapshot_id, row_index, voucher_date, ledger_name, voucher_type, debit_amount, credit_amount, payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("voucher_date"), row.get("ledger_name"), row.get("voucher_type"),
                        row.get("debit_amount"), row.get("credit_amount"), row.get("payload_json"));
                return;
            case BALANCE_SHEET:
                jdbcTemplate.update("INSERT INTO tally_balance_sheet_snapshot_rows (snapshot_id, row_index, name, amount, kind, payload_json) VALUES (?, ?, ?, ?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("name"), row.get("amount"), row.get("kind"), row.get("payload_json"));
                return;
            case PROFIT_LOSS:
                jdbcTemplate.update("INSERT INTO tally_profit_loss_snapshot_rows (snapshot_id, row_index, name, amount, kind, payload_json) VALUES (?, ?, ?, ?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("name"), row.get("amount"), row.get("kind"), row.get("payload_json"));
                return;
            default:
                jdbcTemplate.update("INSERT INTO tally_generic_snapshot_rows (snapshot_id, row_index, payload_json) VALUES (?, ?, ?)",
                        snapshotId, row.get("row_index"), row.get("payload_json"));
        }
    }

    private String tableName(TallyCacheDataset dataset) {
        switch (dataset) {
            case COMPANIES: return "tally_companies_snapshot_rows";
            case GROUPS: return "tally_groups_snapshot_rows";
            case LEDGERS: return "tally_ledgers_snapshot_rows";
            case UOMS: return "tally_uoms_snapshot_rows";
            case CURRENCIES: return "tally_currencies_snapshot_rows";
            case STOCK_GROUPS: return "tally_stock_groups_snapshot_rows";
            case STOCK_ITEMS: return "tally_stock_items_snapshot_rows";
            case COMPANY_CURRENCY: return "tally_company_currency_snapshot_rows";
            case COMPANY_FEATURES: return "tally_company_features_snapshot_rows";
            case DAY_BOOK: return "tally_day_book_snapshot_rows";
            case LEDGER_VOUCHERS: return "tally_ledger_vouchers_snapshot_rows";
            case BALANCE_SHEET: return "tally_balance_sheet_snapshot_rows";
            case PROFIT_LOSS: return "tally_profit_loss_snapshot_rows";
            default: return "tally_generic_snapshot_rows";
        }
    }

    private RowMapper<Map<String, Object>> companiesRowMapper() {
        return (rs, rowNum) -> mapOf("NAME", rs.getString("name"), "RESERVEDNAME", rs.getString("reserved_name"));
    }

    private RowMapper<Map<String, Object>> groupsRowMapper() {
        return (rs, rowNum) -> mapOf("NAME", rs.getString("name"), "PARENT", rs.getString("parent"), "RESERVEDNAME", rs.getString("reserved_name"));
    }

    private RowMapper<Map<String, Object>> ledgersRowMapper() {
        return (rs, rowNum) -> mapOf("NAME", rs.getString("name"), "PARENT", rs.getString("parent"), "RESERVEDNAME", rs.getString("reserved_name"));
    }

    private RowMapper<Map<String, Object>> uomsRowMapper() {
        return (rs, rowNum) -> mapOf(
                "NAME", rs.getString("name"),
                "RESERVEDNAME", rs.getString("reserved_name"),
                "ORIGINALNAME", rs.getString("original_name"),
                "DECIMALPLACES", rs.getString("decimal_places"),
                "ISSIMPLEUNIT", rs.getString("is_simple_unit"));
    }

    private RowMapper<Map<String, Object>> currenciesRowMapper() {
        return (rs, rowNum) -> mapOf(
                "name", rs.getString("name"),
                "originalName", rs.getString("original_name"),
                "symbol", rs.getString("symbol"),
                "decimalSymbol", rs.getString("decimal_symbol"),
                "decimalPlaces", rs.getString("decimal_places"),
                "warning", mapOf("note", rs.getString("warning_note")));
    }

    private RowMapper<Map<String, Object>> stockGroupsRowMapper() {
        return (rs, rowNum) -> mapOf("NAME", rs.getString("name"), "PARENT", rs.getString("parent"));
    }

    private RowMapper<Map<String, Object>> stockItemsRowMapper() {
        return (rs, rowNum) -> mapOf(
                "NAME", rs.getString("name"),
                "PARENT", rs.getString("parent"),
                "BASEUNITS", rs.getString("base_units"),
                "HSNCODE", extractPayloadText(rs.getString("payload_json"), "HSNCODE"),
                "GSTAPPLICABLE", rs.getString("gst_applicable"),
                "QUANTITY", rs.getBigDecimal("quantity"),
                "RATEPER", rs.getBigDecimal("rate"),
                "OPENINGVALUE", rs.getBigDecimal("item_value"));
    }

    private String extractPayloadText(String payloadJson, String fieldName) {
        if (payloadJson == null || payloadJson.trim().isEmpty() || fieldName == null || fieldName.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            JsonNode value = root.get(fieldName);
            if (value == null || value.isNull()) {
                return null;
            }
            if (value.isTextual() || value.isNumber() || value.isBoolean()) {
                return value.asText();
            }
            JsonNode textNode = value.get("#text");
            return textNode == null || textNode.isNull() ? null : textNode.asText();
        } catch (Exception ignored) {
            return null;
        }
    }

    private RowMapper<Map<String, Object>> dayBookRowMapper() {
        return (rs, rowNum) -> mapOf(
                "date", rs.getString("voucher_date"),
                "voucherType", rs.getString("voucher_type"),
                "voucherNumber", rs.getString("voucher_number"),
                "partyLedger", rs.getString("party_ledger"),
                "amount", rs.getBigDecimal("amount"),
                "narration", rs.getString("narration"),
                "payloadJson", rs.getString("payload_json"));
    }

    private RowMapper<Map<String, Object>> companyCurrencyRowMapper() {
        return (rs, rowNum) -> mapOf(
                "NAME", rs.getString("name"),
                "BOOKSFROM", rs.getString("books_from"),
                "MAILINGNAME", rs.getString("mailing_name"),
                "CURRENCYNAME", rs.getString("currency_name"),
                "DECIMALSYMBOL", rs.getString("decimal_symbol"));
    }

    private RowMapper<Map<String, Object>> companyFeaturesRowMapper() {
        return (rs, rowNum) -> mapOf(
                "NAME", rs.getString("name"),
                "BOOKSFROM", rs.getString("books_from"),
                "EMAIL", rs.getString("email"),
                "PINCODE", rs.getString("pincode"),
                "COUNTRYNAME", rs.getString("country_name"),
                "STATENAME", rs.getString("state_name"),
                "ISINVENTORYON", rs.getString("is_inventory_on"),
                "ISGSTON", rs.getString("is_gst_on"));
    }

    private RowMapper<Map<String, Object>> ledgerVoucherRowMapper() {
        return (rs, rowNum) -> mapOf(
                "date", rs.getString("voucher_date"),
                "ledger", rs.getString("ledger_name"),
                "voucherType", rs.getString("voucher_type"),
                "debitAmount", rs.getBigDecimal("debit_amount"),
                "creditAmount", rs.getBigDecimal("credit_amount"));
    }

    private RowMapper<Map<String, Object>> genericRowMapper() {
        return (rs, rowNum) -> readGenericRow(rs.getString("payload_json"));
    }

    private RowMapper<Map<String, Object>> statementRowMapper() {
        return (rs, rowNum) -> mapOf(
                "name", rs.getString("name"),
                "amount", rs.getBigDecimal("amount"),
                "kind", rs.getString("kind"));
    }

    private Map<String, Object> readGenericRow(String payloadJson) {
        if (payloadJson == null || payloadJson.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(payloadJson, Map.class);
        } catch (Exception ignored) {
            return mapOf("value", payloadJson, "payload_json", payloadJson);
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<String, Object>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            map.put(String.valueOf(values[index]), values[index + 1]);
        }
        return map;
    }
}
