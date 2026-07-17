package com.tallybackend.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TallyCacheNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TallyCacheNormalizer normalizer = new TallyCacheNormalizer(objectMapper);

    @Test
    void dayBookAndLedgerVoucherRowsAreDerivedFromVoucherPayload() throws Exception {
        JsonNode root = objectMapper.readTree("{\n" +
                "  \"TALLYMESSAGE\": {\n" +
                "    \"VOUCHER\": {\n" +
                "      \"DATE\": \"20260415\",\n" +
                "      \"VOUCHERTYPENAME\": \"Sales\",\n" +
                "      \"VOUCHERNUMBER\": \"42\",\n" +
                "      \"PARTYLEDGERNAME\": \"Customer A\",\n" +
                "      \"NARRATION\": \"Invoice\",\n" +
                "      \"LEDGERENTRIES\": [\n" +
                "        {\"LEDGERNAME\": \"Customer A\", \"AMOUNT\": \"1500.00\"},\n" +
                "        {\"LEDGERNAME\": \"Sales Account\", \"AMOUNT\": \"-1500.00\"}\n" +
                "      ]\n" +
                "    }\n" +
                "  }\n" +
                "}");

        List<Map<String, Object>> dayBookRows = normalizer.dayBook(root);
        List<Map<String, Object>> ledgerVoucherRows = normalizer.ledgerVouchersFromDayBook(root);

        assertEquals(1, dayBookRows.size());
        assertEquals("20260415", dayBookRows.get(0).get("voucher_date"));
        assertEquals("Sales", dayBookRows.get(0).get("voucher_type"));
        assertEquals(new BigDecimal("1500.00"), dayBookRows.get(0).get("amount"));

        assertEquals(2, ledgerVoucherRows.size());
        assertEquals("Customer A", ledgerVoucherRows.get(0).get("ledger_name"));
        assertEquals(new BigDecimal("1500.00"), ledgerVoucherRows.get(0).get("credit_amount"));
        assertEquals(new BigDecimal("1500.00"), ledgerVoucherRows.get(1).get("debit_amount"));
    }

    @Test
    void statementRowsAreClassifiedForBalanceSheetAndProfitLoss() throws Exception {
        JsonNode balanceSheet = objectMapper.readTree("{\"ENVELOPE\":{\"BSNAME\":[\"Capital\",\"Cash\"],\"BSAMT\":[\"1000\",{\"BSMAINAMT\":\"-250\"}]}}");
        JsonNode profitLoss = objectMapper.readTree("{\"ENVELOPE\":{\"DSPACCNAME\":[\"Sales\",\"Rent\"],\"PLAMT\":[\"800\",\"-100\"]}}");

        List<Map<String, Object>> balanceRows = normalizer.balanceSheet(balanceSheet);
        List<Map<String, Object>> profitRows = normalizer.profitLoss(profitLoss);

        assertEquals("LIABILITY", balanceRows.get(0).get("kind"));
        assertEquals("ASSET", balanceRows.get(1).get("kind"));
        assertEquals("INCOME", profitRows.get(0).get("kind"));
        assertEquals("EXPENSE", profitRows.get(1).get("kind"));
    }

    @Test
    void statementAmountsPreferTallyMixedMainAndSubAmountFields() throws Exception {
        JsonNode profitLoss = objectMapper.readTree("{\"ENVELOPE\":{\"DSPACCNAME\":[\"Sales Accounts\",\"Opening Stock\",\"Add: Purchase Accounts\",\"Less: Closing Stock\"],\"PLAMT\":[{\"PLSUBAMT\":null,\"BSMAINAMT\":\"-59525.00\"},{\"PLSUBAMT\":\"-69520.00\",\"BSMAINAMT\":null},{\"PLSUBAMT\":\"988.00\",\"BSMAINAMT\":null},{\"PLSUBAMT\":\"-69556.00\",\"BSMAINAMT\":null}]}}");

        List<Map<String, Object>> rows = normalizer.profitLoss(profitLoss);

        assertEquals(new BigDecimal("-59525.00"), rows.get(0).get("amount"));
        assertEquals(new BigDecimal("-69520.00"), rows.get(1).get("amount"));
        assertEquals(new BigDecimal("988.00"), rows.get(2).get("amount"));
        assertEquals(new BigDecimal("-69556.00"), rows.get(3).get("amount"));
    }
}
