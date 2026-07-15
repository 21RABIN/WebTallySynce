package com.tallybackend.sync.service;

import com.tallybackend.sync.entity.TallyLedgerMapping;
import com.tallybackend.sync.exception.SyncApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class TallyVoucherXmlBuilder {

    private final TallyLedgerMappingService tallyLedgerMappingService;

    public TallyVoucherXmlBuilder(TallyLedgerMappingService tallyLedgerMappingService) {
        this.tallyLedgerMappingService = tallyLedgerMappingService;
    }

    public String buildSalesVoucherXml(Map<String, Object> invoice) {
        Long businessUnitId = ErpPayloadSupport.longValue(invoice, "businessUnitId");
        String customerLedger = resolveCustomerLedger(invoice);
        String salesLedger = requireMappingName(businessUnitId, "SALES");
        String voucherNumber = ErpPayloadSupport.stringValue(invoice, "invoiceNumber", "number");
        String voucherDate = ErpPayloadSupport.tallyDate(invoice, "invoiceDate", "date");
        BigDecimal totalAmount = requiredAmount(invoice, "totalAmount", "grandTotal");
        BigDecimal gstAmount = defaultAmount(invoice, "gstAmount", "taxAmount");
        BigDecimal taxableAmount = defaultAmount(invoice, "taxableAmount", "subTotal");
        BigDecimal roundOff = defaultAmount(invoice, "roundOff", "roundOffAmount");

        List<LedgerEntry> ledgers = new ArrayList<>();
        ledgers.add(new LedgerEntry(customerLedger, negate(totalAmount), true));
        ledgers.add(new LedgerEntry(salesLedger, taxableAmount, false));
        appendOutputGstLedgers(ledgers, businessUnitId, invoice, gstAmount);
        appendOptionalLedger(ledgers, businessUnitId, "ROUND_OFF", roundOff);
        return buildVoucherEnvelope("Sales", voucherNumber, voucherDate, ledgers, invoice, "Invoice Voucher View");
    }

    public String buildPurchaseVoucherXml(Map<String, Object> invoice) {
        Long businessUnitId = ErpPayloadSupport.longValue(invoice, "businessUnitId");
        String supplierLedger = resolveSupplierLedger(invoice);
        String purchaseLedger = requireMappingName(businessUnitId, "PURCHASE");
        String voucherNumber = ErpPayloadSupport.stringValue(invoice, "invoiceNumber", "number");
        String voucherDate = ErpPayloadSupport.tallyDate(invoice, "invoiceDate", "date");
        BigDecimal totalAmount = requiredAmount(invoice, "totalAmount", "grandTotal");
        BigDecimal gstAmount = defaultAmount(invoice, "gstAmount", "taxAmount");
        BigDecimal taxableAmount = defaultAmount(invoice, "taxableAmount", "subTotal");

        List<LedgerEntry> ledgers = new ArrayList<>();
        ledgers.add(new LedgerEntry(supplierLedger, totalAmount, true));
        ledgers.add(new LedgerEntry(purchaseLedger, negate(taxableAmount), false));
        appendInputGstLedgers(ledgers, businessUnitId, invoice, gstAmount);
        return buildVoucherEnvelope("Purchase", voucherNumber, voucherDate, ledgers, invoice, "Invoice Voucher View");
    }

    public String buildReceiptVoucherXml(Map<String, Object> payment) {
        Long businessUnitId = ErpPayloadSupport.longValue(payment, "businessUnitId");
        String bankLedger = resolveBankOrCashLedger(payment, businessUnitId);
        String partyLedger = resolveCustomerLedger(payment);
        String voucherNumber = ErpPayloadSupport.stringValue(payment, "paymentNumber", "referenceNumber", "receiptNumber");
        String voucherDate = ErpPayloadSupport.tallyDate(payment, "paymentDate", "date");
        BigDecimal amount = requiredAmount(payment, "amount", "paymentAmount");

        List<LedgerEntry> ledgers = new ArrayList<>();
        ledgers.add(new LedgerEntry(bankLedger, amount, false));
        ledgers.add(new LedgerEntry(partyLedger, negate(amount), true));
        return buildVoucherEnvelope("Receipt", voucherNumber, voucherDate, ledgers, payment, "Accounting Voucher View");
    }

    public String buildPaymentVoucherXml(Map<String, Object> payment) {
        Long businessUnitId = ErpPayloadSupport.longValue(payment, "businessUnitId");
        String bankLedger = resolveBankOrCashLedger(payment, businessUnitId);
        String partyLedger = resolveSupplierLedger(payment);
        String voucherNumber = ErpPayloadSupport.stringValue(payment, "paymentNumber", "referenceNumber");
        String voucherDate = ErpPayloadSupport.tallyDate(payment, "paymentDate", "date");
        BigDecimal amount = requiredAmount(payment, "amount", "paymentAmount");

        List<LedgerEntry> ledgers = new ArrayList<>();
        ledgers.add(new LedgerEntry(partyLedger, amount, true));
        ledgers.add(new LedgerEntry(bankLedger, negate(amount), false));
        return buildVoucherEnvelope("Payment", voucherNumber, voucherDate, ledgers, payment, "Accounting Voucher View");
    }

    public String buildRefundVoucherXml(Map<String, Object> refund) {
        return buildPaymentVoucherXml(refund);
    }

    public String buildCancelVoucherXml(Map<String, Object> invoice) {
        String voucherType = ErpPayloadSupport.stringValue(invoice, "voucherType", "tallyVoucherType", "documentType");
        String voucherNumber = ErpPayloadSupport.stringValue(invoice, "invoiceNumber", "number");
        String voucherDate = ErpPayloadSupport.tallyDate(invoice, "invoiceDate", "date");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart());
        xml.append("          <VOUCHER ACTION=\"Cancel\" VCHTYPE=\"").append(ErpPayloadSupport.xml(defaultString(voucherType, "Sales"))).append("\">\n");
        xml.append(tag("DATE", voucherDate, 6));
        xml.append(tag("VOUCHERTYPENAME", defaultString(voucherType, "Sales"), 6));
        xml.append(tag("VOUCHERNUMBER", voucherNumber, 6));
        xml.append(tag("PERSISTEDVIEW", "Accounting Voucher View", 6));
        xml.append("          </VOUCHER>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    public String buildSalesOrderXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Sales Order", payload, "Accounting Voucher View");
    }

    public String buildPurchaseOrderXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Purchase Order", payload, "Accounting Voucher View");
    }

    public String buildDeliveryNoteXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Delivery Note", payload, "Accounting Voucher View");
    }

    public String buildGoodsReceiptXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Receipt Note", payload, "Accounting Voucher View");
    }

    public String buildStockJournalXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Stock Journal", payload, "Consumption Voucher View");
    }

    public String buildMaterialInXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Material In", payload, "Consumption Voucher View");
    }

    public String buildMaterialOutXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Material Out", payload, "Consumption Voucher View");
    }

    public String buildContraXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Contra", payload, "Accounting Voucher View");
    }

    public String buildJournalXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Journal", payload, "Accounting Voucher View");
    }

    public String buildDebitNoteXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Debit Note", payload, "Accounting Voucher View");
    }

    public String buildCreditNoteXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Credit Note", payload, "Accounting Voucher View");
    }

    public String buildRejectionInXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Rejections In", payload, "Consumption Voucher View");
    }

    public String buildRejectionOutXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Rejections Out", payload, "Consumption Voucher View");
    }

    public String buildPayrollVoucherXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Payroll", payload, "Accounting Voucher View");
    }

    public String buildPhysicalStockXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Physical Stock", payload, "Accounting Voucher View");
    }

    public String buildAttendanceVoucherXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Attendance", payload, "Accounting Voucher View");
    }

    public String buildJobWorkInOrderXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Job Work In Order", payload, "Accounting Voucher View");
    }

    public String buildJobWorkOutOrderXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Job Work Out Order", payload, "Accounting Voucher View");
    }

    public String buildMemorandumXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Memorandum", payload, "Accounting Voucher View");
    }

    public String buildReversingJournalXml(Map<String, Object> payload) {
        return buildSimpleVoucher("Reversing Journal", payload, "Accounting Voucher View");
    }

    public String buildReturnVoucherXml(Map<String, Object> invoice) {
        Long businessUnitId = ErpPayloadSupport.longValue(invoice, "businessUnitId");
        String customerLedger = resolveCustomerLedger(invoice);
        String salesLedger = requireMappingName(businessUnitId, "SALES");
        String voucherNumber = ErpPayloadSupport.stringValue(invoice, "invoiceNumber", "number");
        String voucherDate = ErpPayloadSupport.tallyDate(invoice, "invoiceDate", "date");
        BigDecimal totalAmount = requiredAmount(invoice, "totalAmount", "grandTotal");
        BigDecimal gstAmount = defaultAmount(invoice, "gstAmount", "taxAmount");
        BigDecimal taxableAmount = defaultAmount(invoice, "taxableAmount", "subTotal");

        List<LedgerEntry> ledgers = new ArrayList<>();
        ledgers.add(new LedgerEntry(customerLedger, totalAmount, true));
        ledgers.add(new LedgerEntry(salesLedger, negate(taxableAmount), false));
        appendInputLikeReversalGstLedgers(ledgers, businessUnitId, invoice, gstAmount);
        return buildVoucherEnvelope("Credit Note", voucherNumber, voucherDate, ledgers, invoice, "Accounting Voucher View");
    }

    private String buildSimpleVoucher(String voucherType, Map<String, Object> source, String persistedView) {
        String voucherNumber = ErpPayloadSupport.stringValue(source, "voucherNumber", "number", "referenceNumber");
        String voucherDate = ErpPayloadSupport.tallyDate(source, "voucherDate", "date", "voucherDate");
        String narration = ErpPayloadSupport.stringValue(source, "narration", "remarks", "notes");
        List<Map<String, Object>> ledgerEntries = ErpPayloadSupport.listOfMaps(source, "ledgerEntries", "entries", "LEDGERENTRIES.LIST");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart());
        xml.append("          <VOUCHER VCHTYPE=\"").append(ErpPayloadSupport.xml(voucherType)).append("\" ACTION=\"Create\" OBJVIEW=\"").append(ErpPayloadSupport.xml(persistedView)).append("\">\n");
        if (voucherDate != null) {
            xml.append(tag("DATE", voucherDate, 6));
        }
        xml.append(tag("VOUCHERTYPENAME", voucherType, 6));
        if (voucherNumber != null) {
            xml.append(tag("VOUCHERNUMBER", voucherNumber, 6));
        }
        xml.append(tag("PERSISTEDVIEW", persistedView, 6));
        if (narration != null) {
            xml.append(tag("NARRATION", narration, 6));
        }
        String partyLedger = ErpPayloadSupport.stringValue(source, "partyLedgerName", "partyName");
        if (partyLedger != null) {
            xml.append(tag("PARTYLEDGERNAME", partyLedger, 6));
        }
        if (ledgerEntries != null) {
            for (Map<String, Object> entry : ledgerEntries) {
                String ledgerName = ErpPayloadSupport.stringValue(entry, "ledgerName", "LEDGERNAME", "name");
                BigDecimal amount = ErpPayloadSupport.decimalValue(entry, "amount", "AMOUNT");
                boolean isParty = ErpPayloadSupport.booleanValue(entry, "isPartyLedger", "ISPARTYLEDGER");
                if (ledgerName != null && amount != null) {
                    xml.append("            <LEDGERENTRIES.LIST>\n");
                    xml.append(tag("LEDGERNAME", ledgerName, 7));
                    xml.append(tag("ISPARTYLEDGER", isParty ? "Yes" : "No", 7));
                    xml.append(tag("AMOUNT", ErpPayloadSupport.amount(amount), 7));
                    xml.append("            </LEDGERENTRIES.LIST>\n");
                }
            }
        }
        List<Map<String, Object>> inventoryEntries = ErpPayloadSupport.listOfMaps(source, "inventoryEntries", "stockItems", "INVENTORYENTRIES.LIST");
        if (inventoryEntries != null) {
            for (Map<String, Object> entry : inventoryEntries) {
                String itemName = ErpPayloadSupport.stringValue(entry, "stockItemName", "itemName", "STOCKITEMNAME");
                BigDecimal qty = ErpPayloadSupport.decimalValue(entry, "quantity", "QTY", "qty");
                BigDecimal rate = ErpPayloadSupport.decimalValue(entry, "rate", "RATE");
                if (itemName != null && qty != null) {
                    xml.append("            <INVENTORYENTRIES.LIST>\n");
                    xml.append(tag("STOCKITEMNAME", itemName, 7));
                    xml.append(tag("QTY", ErpPayloadSupport.amount(qty), 7));
                    if (rate != null) {
                        xml.append(tag("RATE", ErpPayloadSupport.amount(rate), 7));
                    }
                    String godown = ErpPayloadSupport.stringValue(entry, "godown", "GODOWN", "godownName");
                    if (godown != null) {
                        xml.append(tag("GODOWN", godown, 7));
                    }
                    xml.append("            </INVENTORYENTRIES.LIST>\n");
                }
            }
        }
        xml.append("          </VOUCHER>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    private String buildVoucherEnvelope(String voucherType,
                                        String voucherNumber,
                                        String voucherDate,
                                        List<LedgerEntry> ledgers,
                                        Map<String, Object> source,
                                        String persistedView) {
        String narration = ErpPayloadSupport.stringValue(source, "narration", "remarks", "notes");
        String partyName = ErpPayloadSupport.resolvePartyName(source, "customer", "customerLedgerName", "customerName", "partyLedgerName", "partyName", "supplierLedgerName", "supplierName");
        StringBuilder xml = new StringBuilder();
        xml.append(envelopeStart());
        xml.append("          <VOUCHER VCHTYPE=\"").append(ErpPayloadSupport.xml(voucherType)).append("\" ACTION=\"Create\" OBJVIEW=\"").append(ErpPayloadSupport.xml(persistedView)).append("\">\n");
        xml.append(tag("DATE", voucherDate, 6));
        xml.append(tag("VOUCHERTYPENAME", voucherType, 6));
        if (voucherNumber != null) {
            xml.append(tag("VOUCHERNUMBER", voucherNumber, 6));
        }
        xml.append(tag("PERSISTEDVIEW", persistedView, 6));
        xml.append(tag("ISINVOICE", persistedView.toLowerCase().contains("invoice") ? "Yes" : "No", 6));
        if (partyName != null) {
            xml.append(tag("PARTYLEDGERNAME", partyName, 6));
        }
        if (narration != null) {
            xml.append(tag("NARRATION", narration, 6));
        }
        for (LedgerEntry entry : ledgers) {
            xml.append("            <LEDGERENTRIES.LIST>\n");
            xml.append(tag("LEDGERNAME", entry.ledgerName, 7));
            xml.append(tag("ISPARTYLEDGER", entry.partyLedger ? "Yes" : "No", 7));
            xml.append(tag("AMOUNT", ErpPayloadSupport.amount(entry.amount), 7));
            xml.append("            </LEDGERENTRIES.LIST>\n");
        }
        xml.append("          </VOUCHER>\n");
        xml.append(envelopeEnd());
        return xml.toString();
    }

    private void appendOutputGstLedgers(List<LedgerEntry> ledgers, Long businessUnitId, Map<String, Object> invoice, BigDecimal gstAmount) {
        BigDecimal cgst = defaultAmount(invoice, "cgstAmount");
        BigDecimal sgst = defaultAmount(invoice, "sgstAmount");
        BigDecimal igst = defaultAmount(invoice, "igstAmount");
        if (gstAmount.compareTo(BigDecimal.ZERO) > 0 && cgst.add(sgst).add(igst).compareTo(BigDecimal.ZERO) == 0) {
            igst = gstAmount;
        }
        appendOptionalLedger(ledgers, businessUnitId, "OUTPUT_CGST", cgst);
        appendOptionalLedger(ledgers, businessUnitId, "OUTPUT_SGST", sgst);
        appendOptionalLedger(ledgers, businessUnitId, "OUTPUT_IGST", igst);
    }

    private void appendInputGstLedgers(List<LedgerEntry> ledgers, Long businessUnitId, Map<String, Object> invoice, BigDecimal gstAmount) {
        BigDecimal cgst = defaultAmount(invoice, "cgstAmount");
        BigDecimal sgst = defaultAmount(invoice, "sgstAmount");
        BigDecimal igst = defaultAmount(invoice, "igstAmount");
        if (gstAmount.compareTo(BigDecimal.ZERO) > 0 && cgst.add(sgst).add(igst).compareTo(BigDecimal.ZERO) == 0) {
            igst = gstAmount;
        }
        appendOptionalLedger(ledgers, businessUnitId, "INPUT_CGST", negate(cgst));
        appendOptionalLedger(ledgers, businessUnitId, "INPUT_SGST", negate(sgst));
        appendOptionalLedger(ledgers, businessUnitId, "INPUT_IGST", negate(igst));
    }

    private void appendInputLikeReversalGstLedgers(List<LedgerEntry> ledgers, Long businessUnitId, Map<String, Object> invoice, BigDecimal gstAmount) {
        BigDecimal cgst = defaultAmount(invoice, "cgstAmount");
        BigDecimal sgst = defaultAmount(invoice, "sgstAmount");
        BigDecimal igst = defaultAmount(invoice, "igstAmount");
        if (gstAmount.compareTo(BigDecimal.ZERO) > 0 && cgst.add(sgst).add(igst).compareTo(BigDecimal.ZERO) == 0) {
            igst = gstAmount;
        }
        appendOptionalLedger(ledgers, businessUnitId, "OUTPUT_CGST", negate(cgst));
        appendOptionalLedger(ledgers, businessUnitId, "OUTPUT_SGST", negate(sgst));
        appendOptionalLedger(ledgers, businessUnitId, "OUTPUT_IGST", negate(igst));
    }

    private void appendOptionalLedger(List<LedgerEntry> ledgers, Long businessUnitId, String mappingType, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        String ledgerName = requireMappingName(businessUnitId, mappingType);
        ledgers.add(new LedgerEntry(ledgerName, amount, false));
    }

    private String resolveCustomerLedger(Map<String, Object> source) {
        String direct = ErpPayloadSupport.stringValue(source, "customerLedgerName", "partyLedgerName", "customerName", "partyName");
        if (direct != null) {
            return direct;
        }
        Map<String, Object> customer = ErpPayloadSupport.objectValue(source, "customer");
        return defaultString(ErpPayloadSupport.stringValue(customer, "ledgerName", "name"), null);
    }

    private String resolveSupplierLedger(Map<String, Object> source) {
        String direct = ErpPayloadSupport.stringValue(source, "supplierLedgerName", "partyLedgerName", "supplierName", "partyName", "expenseLedgerName");
        if (direct != null) {
            return direct;
        }
        Map<String, Object> supplier = ErpPayloadSupport.objectValue(source, "supplier");
        return defaultString(ErpPayloadSupport.stringValue(supplier, "ledgerName", "name"), null);
    }

    private String resolveBankOrCashLedger(Map<String, Object> source, Long businessUnitId) {
        String mode = defaultString(ErpPayloadSupport.stringValue(source, "mode", "paymentMode"), "BANK");
        String mappingType = "CASH".equalsIgnoreCase(mode) ? "CASH" : "BANK";
        return requireMappingName(businessUnitId, mappingType);
    }

    private String requireMappingName(Long businessUnitId, String mappingType) {
        TallyLedgerMapping mapping = tallyLedgerMappingService.getRequiredMapping(businessUnitId, mappingType);
        if (mapping.getTallyLedgerName() == null || mapping.getTallyLedgerName().trim().isEmpty()) {
            throw new SyncApiException(HttpStatus.BAD_REQUEST, "Ledger mapping is configured without a Tally ledger name");
        }
        return mapping.getTallyLedgerName().trim();
    }

    private BigDecimal requiredAmount(Map<String, Object> source, String... keys) {
        BigDecimal value = ErpPayloadSupport.decimalValue(source, keys);
        if (value == null) {
            throw new SyncApiException(HttpStatus.BAD_REQUEST, "Required amount is missing");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal defaultAmount(Map<String, Object> source, String... keys) {
        BigDecimal value = ErpPayloadSupport.decimalValue(source, keys);
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal negate(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : value.negate().setScale(2, RoundingMode.HALF_UP);
    }

    private String envelopeStart() {
        return "<ENVELOPE>\n" +
                "  <HEADER>\n" +
                "    <TALLYREQUEST>Import Data</TALLYREQUEST>\n" +
                "  </HEADER>\n" +
                "  <BODY>\n" +
                "    <IMPORTDATA>\n" +
                "      <REQUESTDESC>\n" +
                "        <REPORTNAME>Vouchers</REPORTNAME>\n" +
                "      </REQUESTDESC>\n" +
                "      <REQUESTDATA>\n" +
                "        <TALLYMESSAGE xmlns:UDF=\"TallyUDF\">\n";
    }

    private String envelopeEnd() {
        return "        </TALLYMESSAGE>\n" +
                "      </REQUESTDATA>\n" +
                "    </IMPORTDATA>\n" +
                "  </BODY>\n" +
                "</ENVELOPE>";
    }

    private String tag(String tag, String value, int level) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < level; i++) {
            indent.append("  ");
        }
        return indent + "<" + tag + ">" + ErpPayloadSupport.xml(defaultString(value, "")) + "</" + tag + ">\n";
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static final class LedgerEntry {
        private final String ledgerName;
        private final BigDecimal amount;
        private final boolean partyLedger;

        private LedgerEntry(String ledgerName, BigDecimal amount, boolean partyLedger) {
            this.ledgerName = ledgerName;
            this.amount = amount;
            this.partyLedger = partyLedger;
        }
    }
}
