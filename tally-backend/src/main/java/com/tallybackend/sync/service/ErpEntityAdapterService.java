package com.tallybackend.sync.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ErpEntityAdapterService {
    Optional<Map<String, Object>> findCustomerById(Long customerId);

    Optional<Map<String, Object>> findSupplierById(Long supplierId);

    Optional<Map<String, Object>> findProductById(Long productId);

    Optional<Map<String, Object>> findSalesInvoiceById(Long invoiceId);

    Optional<Map<String, Object>> findPurchaseInvoiceById(Long invoiceId);

    Optional<Map<String, Object>> findPaymentById(Long paymentId);

    Optional<Map<String, Object>> findRefundById(Long paymentId);

    Optional<Map<String, Object>> findCancelInvoiceById(Long invoiceId);

    Optional<Map<String, Object>> findReturnInvoiceById(Long invoiceId);

    List<Map<String, Object>> findCustomers(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findSuppliers(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findProducts(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findSalesInvoices(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findPurchaseInvoices(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findPayments(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    Optional<Map<String, Object>> findStockGroupById(Long id);

    Optional<Map<String, Object>> findStockCategoryById(Long id);

    Optional<Map<String, Object>> findGodownById(Long id);

    Optional<Map<String, Object>> findCostCategoryById(Long id);

    Optional<Map<String, Object>> findCostCentreById(Long id);

    Optional<Map<String, Object>> findBomById(Long id);

    Optional<Map<String, Object>> findPriceLevelById(Long id);

    Optional<Map<String, Object>> findPriceListById(Long id);

    Optional<Map<String, Object>> findVoucherTypeById(Long id);

    Optional<Map<String, Object>> findBudgetById(Long id);

    Optional<Map<String, Object>> findEmployeeById(Long id);

    Optional<Map<String, Object>> findEmployeeGroupById(Long id);

    Optional<Map<String, Object>> findPayHeadById(Long id);

    Optional<Map<String, Object>> findAttendanceTypeById(Long id);

    Optional<Map<String, Object>> findSalesOrderById(Long id);

    Optional<Map<String, Object>> findPurchaseOrderById(Long id);

    Optional<Map<String, Object>> findDeliveryNoteById(Long id);

    Optional<Map<String, Object>> findGoodsReceiptById(Long id);

    Optional<Map<String, Object>> findStockJournalById(Long id);

    Optional<Map<String, Object>> findMaterialInById(Long id);

    Optional<Map<String, Object>> findMaterialOutById(Long id);

    Optional<Map<String, Object>> findContraById(Long id);

    Optional<Map<String, Object>> findJournalById(Long id);

    Optional<Map<String, Object>> findDebitNoteById(Long id);

    Optional<Map<String, Object>> findCreditNoteById(Long id);

    Optional<Map<String, Object>> findRejectionInById(Long id);

    Optional<Map<String, Object>> findRejectionOutById(Long id);

    Optional<Map<String, Object>> findPayrollVoucherById(Long id);

    Optional<Map<String, Object>> findPhysicalStockById(Long id);

    Optional<Map<String, Object>> findAttendanceVoucherById(Long id);

    Optional<Map<String, Object>> findJobWorkInOrderById(Long id);

    Optional<Map<String, Object>> findJobWorkOutOrderById(Long id);

    Optional<Map<String, Object>> findMemorandumById(Long id);

    Optional<Map<String, Object>> findReversingJournalById(Long id);

    List<Map<String, Object>> findStockGroups(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findStockCategories(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findGodowns(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findCostCategories(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findCostCentres(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findBoms(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findPriceLevels(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findPriceLists(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findVoucherTypes(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findBudgets(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findEmployees(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findEmployeeGroups(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findPayHeads(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findAttendanceTypes(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findSalesOrders(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findPurchaseOrders(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findDeliveryNotes(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findGoodsReceipts(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findStockJournals(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findMaterialIns(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findMaterialOuts(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findContras(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findJournals(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findDebitNotes(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findCreditNotes(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findRejectionIns(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findRejectionOuts(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findPayrollVouchers(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findPhysicalStocks(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findAttendanceVouchers(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findJobWorkInOrders(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findJobWorkOutOrders(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findMemorandums(LocalDate fromDate, LocalDate toDate, Long businessUnitId);

    List<Map<String, Object>> findReversingJournals(LocalDate fromDate, LocalDate toDate, Long businessUnitId);
}
