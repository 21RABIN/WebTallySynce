package com.tallybackend.sync.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DefaultErpEntityAdapterService implements ErpEntityAdapterService {

    private final boolean mockEnabled;

    public DefaultErpEntityAdapterService(@Value("${erp.mock.enabled:true}") boolean mockEnabled) {
        this.mockEnabled = mockEnabled;
    }

    @Override
    public Optional<Map<String, Object>> findCustomerById(Long customerId) {
        return mock(customerLedger(customerId, "Customer"));
    }

    @Override
    public Optional<Map<String, Object>> findSupplierById(Long supplierId) {
        return mock(supplierLedger(supplierId, "Supplier"));
    }

    @Override
    public Optional<Map<String, Object>> findProductById(Long productId) {
        return mock(product(productId));
    }

    @Override
    public Optional<Map<String, Object>> findSalesInvoiceById(Long invoiceId) {
        return mock(salesInvoice(invoiceId));
    }

    @Override
    public Optional<Map<String, Object>> findPurchaseInvoiceById(Long invoiceId) {
        return mock(purchaseInvoice(invoiceId));
    }

    @Override
    public Optional<Map<String, Object>> findPaymentById(Long paymentId) {
        return mock(payment(paymentId));
    }

    @Override
    public Optional<Map<String, Object>> findRefundById(Long paymentId) {
        return mock(refund(paymentId));
    }

    @Override
    public Optional<Map<String, Object>> findCancelInvoiceById(Long invoiceId) {
        return mock(simpleVoucher(invoiceId, "Sales", "Cancelled invoice sample"));
    }

    @Override
    public Optional<Map<String, Object>> findReturnInvoiceById(Long invoiceId) {
        return mock(salesInvoice(invoiceId));
    }

    @Override
    public List<Map<String, Object>> findCustomers(LocalDate fromDate, LocalDate toDate, Long businessUnitId) {
        return singleton(customerLedger(1L, "Customer"));
    }

    @Override
    public List<Map<String, Object>> findSuppliers(LocalDate fromDate, LocalDate toDate, Long businessUnitId) {
        return singleton(supplierLedger(1L, "Supplier"));
    }

    @Override
    public List<Map<String, Object>> findProducts(LocalDate fromDate, LocalDate toDate, Long businessUnitId) {
        return singleton(product(1L));
    }

    @Override
    public List<Map<String, Object>> findSalesInvoices(LocalDate fromDate, LocalDate toDate, Long businessUnitId) {
        return singleton(salesInvoice(1L));
    }

    @Override
    public List<Map<String, Object>> findPurchaseInvoices(LocalDate fromDate, LocalDate toDate, Long businessUnitId) {
        return singleton(purchaseInvoice(1L));
    }

    @Override
    public List<Map<String, Object>> findPayments(LocalDate fromDate, LocalDate toDate, Long businessUnitId) {
        return singleton(payment(1L));
    }

    @Override
    public Optional<Map<String, Object>> findStockGroupById(Long id) { return mock(namedParentEntity(id, "Stock Group", "parent", "Primary")); }

    @Override
    public Optional<Map<String, Object>> findStockCategoryById(Long id) { return mock(namedEntity(id, "Stock Category")); }

    @Override
    public Optional<Map<String, Object>> findGodownById(Long id) { return mock(namedParentEntity(id, "Godown", "parent", "Main Location")); }

    @Override
    public Optional<Map<String, Object>> findCostCategoryById(Long id) { return mock(namedEntity(id, "Cost Category")); }

    @Override
    public Optional<Map<String, Object>> findCostCentreById(Long id) { return mock(namedParentEntity(id, "Cost Centre", "category", "Primary Cost Category")); }

    @Override
    public Optional<Map<String, Object>> findBomById(Long id) {
        return mock(mapOf(
                "id", id,
                "name", "BOM-" + id,
                "stockItem", "Sample Product " + id,
                "quantity", decimal("1.000")
        ));
    }

    @Override
    public Optional<Map<String, Object>> findPriceLevelById(Long id) { return mock(namedEntity(id, "Price Level")); }

    @Override
    public Optional<Map<String, Object>> findPriceListById(Long id) { return mock(namedEntity(id, "Price List")); }

    @Override
    public Optional<Map<String, Object>> findVoucherTypeById(Long id) { return mock(namedParentEntity(id, "Voucher Type", "parent", "Sales")); }

    @Override
    public Optional<Map<String, Object>> findBudgetById(Long id) { return mock(namedEntity(id, "Budget")); }

    @Override
    public Optional<Map<String, Object>> findEmployeeById(Long id) {
        return mock(mapOf(
                "id", id,
                "name", "Employee " + id,
                "category", "Primary Cost Category",
                "employeeId", "EMP" + id,
                "aadhaar", "123412341234",
                "uan", "100200300400"
        ));
    }

    @Override
    public Optional<Map<String, Object>> findEmployeeGroupById(Long id) { return mock(namedEntity(id, "Employee Group")); }

    @Override
    public Optional<Map<String, Object>> findPayHeadById(Long id) { return mock(namedParentEntity(id, "Pay Head", "parent", "Indirect Expenses")); }

    @Override
    public Optional<Map<String, Object>> findAttendanceTypeById(Long id) { return mock(namedEntity(id, "Attendance Type")); }

    @Override
    public Optional<Map<String, Object>> findSalesOrderById(Long id) { return mock(simpleVoucher(id, "Sales Order", "Sample sales order")); }

    @Override
    public Optional<Map<String, Object>> findPurchaseOrderById(Long id) { return mock(simpleVoucher(id, "Purchase Order", "Sample purchase order")); }

    @Override
    public Optional<Map<String, Object>> findDeliveryNoteById(Long id) { return mock(simpleVoucher(id, "Delivery Note", "Sample delivery note")); }

    @Override
    public Optional<Map<String, Object>> findGoodsReceiptById(Long id) { return mock(simpleVoucher(id, "Receipt Note", "Sample goods receipt")); }

    @Override
    public Optional<Map<String, Object>> findStockJournalById(Long id) { return mock(simpleVoucher(id, "Stock Journal", "Sample stock journal")); }

    @Override
    public Optional<Map<String, Object>> findMaterialInById(Long id) { return mock(simpleVoucher(id, "Material In", "Sample material inward")); }

    @Override
    public Optional<Map<String, Object>> findMaterialOutById(Long id) { return mock(simpleVoucher(id, "Material Out", "Sample material outward")); }

    @Override
    public Optional<Map<String, Object>> findContraById(Long id) { return mock(simpleVoucher(id, "Contra", "Sample contra voucher")); }

    @Override
    public Optional<Map<String, Object>> findJournalById(Long id) { return mock(simpleVoucher(id, "Journal", "Sample journal voucher")); }

    @Override
    public Optional<Map<String, Object>> findDebitNoteById(Long id) { return mock(simpleVoucher(id, "Debit Note", "Sample debit note")); }

    @Override
    public Optional<Map<String, Object>> findCreditNoteById(Long id) { return mock(simpleVoucher(id, "Credit Note", "Sample credit note")); }

    @Override
    public Optional<Map<String, Object>> findRejectionInById(Long id) { return mock(simpleVoucher(id, "Rejections In", "Sample rejection in")); }

    @Override
    public Optional<Map<String, Object>> findRejectionOutById(Long id) { return mock(simpleVoucher(id, "Rejections Out", "Sample rejection out")); }

    @Override
    public Optional<Map<String, Object>> findPayrollVoucherById(Long id) { return mock(simpleVoucher(id, "Payroll", "Sample payroll voucher")); }

    @Override
    public Optional<Map<String, Object>> findPhysicalStockById(Long id) { return mock(simpleVoucher(id, "Physical Stock", "Sample physical stock voucher")); }

    @Override
    public Optional<Map<String, Object>> findAttendanceVoucherById(Long id) { return mock(simpleVoucher(id, "Attendance", "Sample attendance voucher")); }

    @Override
    public Optional<Map<String, Object>> findJobWorkInOrderById(Long id) { return mock(simpleVoucher(id, "Job Work In Order", "Sample job work in")); }

    @Override
    public Optional<Map<String, Object>> findJobWorkOutOrderById(Long id) { return mock(simpleVoucher(id, "Job Work Out Order", "Sample job work out")); }

    @Override
    public Optional<Map<String, Object>> findMemorandumById(Long id) { return mock(simpleVoucher(id, "Memorandum", "Sample memorandum voucher")); }

    @Override
    public Optional<Map<String, Object>> findReversingJournalById(Long id) { return mock(simpleVoucher(id, "Reversing Journal", "Sample reversing journal")); }

    @Override
    public List<Map<String, Object>> findStockGroups(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(namedParentEntity(1L, "Stock Group", "parent", "Primary")); }

    @Override
    public List<Map<String, Object>> findStockCategories(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(namedEntity(1L, "Stock Category")); }

    @Override
    public List<Map<String, Object>> findGodowns(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(namedParentEntity(1L, "Godown", "parent", "Main Location")); }

    @Override
    public List<Map<String, Object>> findCostCategories(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(namedEntity(1L, "Cost Category")); }

    @Override
    public List<Map<String, Object>> findCostCentres(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(namedParentEntity(1L, "Cost Centre", "category", "Primary Cost Category")); }

    @Override
    public List<Map<String, Object>> findBoms(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(findBomById(1L).get()); }

    @Override
    public List<Map<String, Object>> findPriceLevels(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(namedEntity(1L, "Price Level")); }

    @Override
    public List<Map<String, Object>> findPriceLists(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(namedEntity(1L, "Price List")); }

    @Override
    public List<Map<String, Object>> findVoucherTypes(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(namedParentEntity(1L, "Voucher Type", "parent", "Sales")); }

    @Override
    public List<Map<String, Object>> findBudgets(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(namedEntity(1L, "Budget")); }

    @Override
    public List<Map<String, Object>> findEmployees(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(findEmployeeById(1L).get()); }

    @Override
    public List<Map<String, Object>> findEmployeeGroups(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(namedEntity(1L, "Employee Group")); }

    @Override
    public List<Map<String, Object>> findPayHeads(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(namedParentEntity(1L, "Pay Head", "parent", "Indirect Expenses")); }

    @Override
    public List<Map<String, Object>> findAttendanceTypes(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(namedEntity(1L, "Attendance Type")); }

    @Override
    public List<Map<String, Object>> findSalesOrders(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Sales Order", "Sample sales order")); }

    @Override
    public List<Map<String, Object>> findPurchaseOrders(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Purchase Order", "Sample purchase order")); }

    @Override
    public List<Map<String, Object>> findDeliveryNotes(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Delivery Note", "Sample delivery note")); }

    @Override
    public List<Map<String, Object>> findGoodsReceipts(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Receipt Note", "Sample goods receipt")); }

    @Override
    public List<Map<String, Object>> findStockJournals(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Stock Journal", "Sample stock journal")); }

    @Override
    public List<Map<String, Object>> findMaterialIns(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Material In", "Sample material inward")); }

    @Override
    public List<Map<String, Object>> findMaterialOuts(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Material Out", "Sample material outward")); }

    @Override
    public List<Map<String, Object>> findContras(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Contra", "Sample contra voucher")); }

    @Override
    public List<Map<String, Object>> findJournals(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Journal", "Sample journal voucher")); }

    @Override
    public List<Map<String, Object>> findDebitNotes(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Debit Note", "Sample debit note")); }

    @Override
    public List<Map<String, Object>> findCreditNotes(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Credit Note", "Sample credit note")); }

    @Override
    public List<Map<String, Object>> findRejectionIns(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Rejections In", "Sample rejection in")); }

    @Override
    public List<Map<String, Object>> findRejectionOuts(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Rejections Out", "Sample rejection out")); }

    @Override
    public List<Map<String, Object>> findPayrollVouchers(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Payroll", "Sample payroll voucher")); }

    @Override
    public List<Map<String, Object>> findPhysicalStocks(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Physical Stock", "Sample physical stock voucher")); }

    @Override
    public List<Map<String, Object>> findAttendanceVouchers(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Attendance", "Sample attendance voucher")); }

    @Override
    public List<Map<String, Object>> findJobWorkInOrders(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Job Work In Order", "Sample job work in")); }

    @Override
    public List<Map<String, Object>> findJobWorkOutOrders(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Job Work Out Order", "Sample job work out")); }

    @Override
    public List<Map<String, Object>> findMemorandums(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Memorandum", "Sample memorandum voucher")); }

    @Override
    public List<Map<String, Object>> findReversingJournals(LocalDate fromDate, LocalDate toDate, Long businessUnitId) { return singleton(simpleVoucher(1L, "Reversing Journal", "Sample reversing journal")); }

    private Optional<Map<String, Object>> mock(Map<String, Object> payload) {
        return mockEnabled ? Optional.of(payload) : Optional.empty();
    }

    private List<Map<String, Object>> singleton(Map<String, Object> payload) {
        return mockEnabled ? Collections.singletonList(payload) : Collections.emptyList();
    }

    private Map<String, Object> customerLedger(Long id, String prefix) {
        return mapOf(
                "id", id,
                "name", prefix + " " + id,
                "ledgerGroup", "Sundry Debtors",
                "gstin", "29ABCDE1234F1Z5",
                "stateName", "Karnataka",
                "email", "customer" + id + "@example.com",
                "phone", "9876543210"
        );
    }

    private Map<String, Object> supplierLedger(Long id, String prefix) {
        return mapOf(
                "id", id,
                "name", prefix + " " + id,
                "ledgerGroup", "Sundry Creditors",
                "gstin", "29ABCDE1234F1Z5",
                "stateName", "Karnataka",
                "email", "supplier" + id + "@example.com",
                "phone", "9876501234"
        );
    }

    private Map<String, Object> product(Long id) {
        return mapOf(
                "id", id,
                "name", "Sample Product " + id,
                "uom", "Nos",
                "stockGroup", "Primary",
                "hsnCode", "8471",
                "gstRate", decimal("18.00")
        );
    }

    private Map<String, Object> salesInvoice(Long id) {
        Map<String, Object> customer = customerLedger(id, "Customer");
        return mapOf(
                "id", id,
                "businessUnitId", 1L,
                "invoiceNumber", "SI-" + id,
                "invoiceDate", "2026-05-18",
                "customer", customer,
                "customerName", customer.get("name"),
                "partyLedgerName", customer.get("name"),
                "taxableAmount", decimal("1000.00"),
                "gstAmount", decimal("180.00"),
                "roundOff", decimal("0.00"),
                "totalAmount", decimal("1180.00")
        );
    }

    private Map<String, Object> purchaseInvoice(Long id) {
        Map<String, Object> supplier = supplierLedger(id, "Supplier");
        return mapOf(
                "id", id,
                "businessUnitId", 1L,
                "invoiceNumber", "PI-" + id,
                "invoiceDate", "2026-05-18",
                "supplier", supplier,
                "supplierName", supplier.get("name"),
                "partyLedgerName", supplier.get("name"),
                "taxableAmount", decimal("1000.00"),
                "gstAmount", decimal("180.00"),
                "totalAmount", decimal("1180.00")
        );
    }

    private Map<String, Object> payment(Long id) {
        Map<String, Object> supplier = supplierLedger(id, "Supplier");
        return mapOf(
                "id", id,
                "businessUnitId", 1L,
                "paymentNumber", "PAY-" + id,
                "paymentDate", "2026-05-18",
                "supplier", supplier,
                "partyLedgerName", supplier.get("name"),
                "amount", decimal("1180.00"),
                "bankLedgerName", "HDFC Bank"
        );
    }

    private Map<String, Object> refund(Long id) {
        Map<String, Object> customer = customerLedger(id, "Customer");
        return mapOf(
                "id", id,
                "businessUnitId", 1L,
                "paymentNumber", "REF-" + id,
                "paymentDate", "2026-05-18",
                "customer", customer,
                "partyLedgerName", customer.get("name"),
                "amount", decimal("500.00"),
                "bankLedgerName", "Cash"
        );
    }

    private Map<String, Object> simpleVoucher(Long id, String voucherType, String narration) {
        Map<String, Object> ledgerEntry1 = mapOf(
                "ledgerName", "Sales Ledger",
                "amount", decimal("1000.00"),
                "isPartyLedger", false
        );
        Map<String, Object> ledgerEntry2 = mapOf(
                "ledgerName", "Customer " + id,
                "amount", decimal("-1000.00"),
                "isPartyLedger", true
        );
        Map<String, Object> inventoryEntry = mapOf(
                "stockItemName", "Sample Product " + id,
                "quantity", decimal("2.00"),
                "rate", decimal("500.00"),
                "godown", "Main Location"
        );
        return mapOf(
                "id", id,
                "voucherNumber", voucherType.substring(0, Math.min(3, voucherType.length())).toUpperCase() + "-" + id,
                "voucherDate", "2026-05-18",
                "partyLedgerName", "Customer " + id,
                "narration", narration,
                "ledgerEntries", Arrays.<Object>asList(ledgerEntry1, ledgerEntry2),
                "inventoryEntries", Collections.<Object>singletonList(inventoryEntry)
        );
    }

    private Map<String, Object> namedEntity(Long id, String prefix) {
        return mapOf("id", id, "name", prefix + " " + id);
    }

    private Map<String, Object> namedParentEntity(Long id, String prefix, String parentKey, String parentValue) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("name", prefix + " " + id);
        payload.put(parentKey, parentValue);
        return payload;
    }

    private Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
