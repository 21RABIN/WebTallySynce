package com.tallybackend.sync.service;

import com.tallybackend.sync.dto.BulkSyncRequest;
import com.tallybackend.sync.dto.BulkSyncResponse;
import com.tallybackend.sync.dto.TallySyncResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class TallyBulkSyncService {

    private final ErpEntityAdapterService erpEntityAdapterService;
    private final TallySyncService tallySyncService;
    private final TallySyncLogService tallySyncLogService;

    public TallyBulkSyncService(ErpEntityAdapterService erpEntityAdapterService,
                                TallySyncService tallySyncService,
                                TallySyncLogService tallySyncLogService) {
        this.erpEntityAdapterService = erpEntityAdapterService;
        this.tallySyncService = tallySyncService;
        this.tallySyncLogService = tallySyncLogService;
    }

    public BulkSyncResponse syncCustomers(BulkSyncRequest request) {
        return syncList("CUSTOMER", erpEntityAdapterService.findCustomers(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, new BulkExecutor() {
            @Override
            public TallySyncResult execute(Long id, boolean forceSync) {
                return tallySyncService.syncCustomer(id, forceSync);
            }
        });
    }

    public BulkSyncResponse syncSuppliers(BulkSyncRequest request) {
        return syncList("SUPPLIER", erpEntityAdapterService.findSuppliers(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, new BulkExecutor() {
            @Override
            public TallySyncResult execute(Long id, boolean forceSync) {
                return tallySyncService.syncSupplier(id, forceSync);
            }
        });
    }

    public BulkSyncResponse syncProducts(BulkSyncRequest request) {
        return syncList("PRODUCT", erpEntityAdapterService.findProducts(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, new BulkExecutor() {
            @Override
            public TallySyncResult execute(Long id, boolean forceSync) {
                return tallySyncService.syncProduct(id, forceSync);
            }
        });
    }

    public BulkSyncResponse syncSalesInvoices(BulkSyncRequest request) {
        return syncList("SALES_INVOICE", erpEntityAdapterService.findSalesInvoices(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, new BulkExecutor() {
            @Override
            public TallySyncResult execute(Long id, boolean forceSync) {
                return tallySyncService.syncSalesInvoice(id, forceSync);
            }
        });
    }

    public BulkSyncResponse syncPurchaseInvoices(BulkSyncRequest request) {
        return syncList("PURCHASE_INVOICE", erpEntityAdapterService.findPurchaseInvoices(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, new BulkExecutor() {
            @Override
            public TallySyncResult execute(Long id, boolean forceSync) {
                return tallySyncService.syncPurchaseInvoice(id, forceSync);
            }
        });
    }

    public BulkSyncResponse syncPayments(BulkSyncRequest request) {
        return syncList("PAYMENT", erpEntityAdapterService.findPayments(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, new BulkExecutor() {
            @Override
            public TallySyncResult execute(Long id, boolean forceSync) {
                return tallySyncService.syncPayment(id, forceSync);
            }
        });
    }

    public BulkSyncResponse syncAllMasters(BulkSyncRequest request) {
        BulkSyncResponse totals = new BulkSyncResponse();
        merge(totals, syncCustomers(request));
        merge(totals, syncSuppliers(request));
        merge(totals, syncProducts(request));
        merge(totals, syncStockGroups(request));
        merge(totals, syncStockCategories(request));
        merge(totals, syncGodowns(request));
        merge(totals, syncCostCategories(request));
        merge(totals, syncCostCentres(request));
        return totals;
    }

    public BulkSyncResponse syncStockGroups(BulkSyncRequest request) {
        return syncList("STOCK_GROUP", erpEntityAdapterService.findStockGroups(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncStockGroup(id, forceSync));
    }

    public BulkSyncResponse syncStockCategories(BulkSyncRequest request) {
        return syncList("STOCK_CATEGORY", erpEntityAdapterService.findStockCategories(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncStockCategory(id, forceSync));
    }

    public BulkSyncResponse syncGodowns(BulkSyncRequest request) {
        return syncList("GODOWN", erpEntityAdapterService.findGodowns(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncGodown(id, forceSync));
    }

    public BulkSyncResponse syncCostCategories(BulkSyncRequest request) {
        return syncList("COST_CATEGORY", erpEntityAdapterService.findCostCategories(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncCostCategory(id, forceSync));
    }

    public BulkSyncResponse syncCostCentres(BulkSyncRequest request) {
        return syncList("COST_CENTRE", erpEntityAdapterService.findCostCentres(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncCostCentre(id, forceSync));
    }

    public BulkSyncResponse syncBoms(BulkSyncRequest request) {
        return syncList("BOM", erpEntityAdapterService.findBoms(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncBom(id, forceSync));
    }

    public BulkSyncResponse syncPriceLevels(BulkSyncRequest request) {
        return syncList("PRICE_LEVEL", erpEntityAdapterService.findPriceLevels(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncPriceLevel(id, forceSync));
    }

    public BulkSyncResponse syncVoucherTypes(BulkSyncRequest request) {
        return syncList("VOUCHER_TYPE", erpEntityAdapterService.findVoucherTypes(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncVoucherType(id, forceSync));
    }

    public BulkSyncResponse syncBudgets(BulkSyncRequest request) {
        return syncList("BUDGET", erpEntityAdapterService.findBudgets(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncBudget(id, forceSync));
    }

    public BulkSyncResponse syncEmployees(BulkSyncRequest request) {
        return syncList("EMPLOYEE", erpEntityAdapterService.findEmployees(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncEmployee(id, forceSync));
    }

    public BulkSyncResponse syncEmployeeGroups(BulkSyncRequest request) {
        return syncList("EMPLOYEE_GROUP", erpEntityAdapterService.findEmployeeGroups(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncEmployeeGroup(id, forceSync));
    }

    public BulkSyncResponse syncPayHeads(BulkSyncRequest request) {
        return syncList("PAY_HEAD", erpEntityAdapterService.findPayHeads(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncPayHead(id, forceSync));
    }

    public BulkSyncResponse syncAttendanceTypes(BulkSyncRequest request) {
        return syncList("ATTENDANCE_TYPE", erpEntityAdapterService.findAttendanceTypes(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncAttendanceType(id, forceSync));
    }

    public BulkSyncResponse syncSalesOrders(BulkSyncRequest request) {
        return syncList("SALES_ORDER", erpEntityAdapterService.findSalesOrders(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncSalesOrder(id, forceSync));
    }

    public BulkSyncResponse syncPurchaseOrders(BulkSyncRequest request) {
        return syncList("PURCHASE_ORDER", erpEntityAdapterService.findPurchaseOrders(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncPurchaseOrder(id, forceSync));
    }

    public BulkSyncResponse syncDeliveryNotes(BulkSyncRequest request) {
        return syncList("DELIVERY_NOTE", erpEntityAdapterService.findDeliveryNotes(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncDeliveryNote(id, forceSync));
    }

    public BulkSyncResponse syncGoodsReceipts(BulkSyncRequest request) {
        return syncList("GOODS_RECEIPT", erpEntityAdapterService.findGoodsReceipts(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncGoodsReceipt(id, forceSync));
    }

    public BulkSyncResponse syncStockJournals(BulkSyncRequest request) {
        return syncList("STOCK_JOURNAL", erpEntityAdapterService.findStockJournals(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncStockJournal(id, forceSync));
    }

    public BulkSyncResponse syncMaterialIns(BulkSyncRequest request) {
        return syncList("MATERIAL_IN", erpEntityAdapterService.findMaterialIns(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncMaterialIn(id, forceSync));
    }

    public BulkSyncResponse syncMaterialOuts(BulkSyncRequest request) {
        return syncList("MATERIAL_OUT", erpEntityAdapterService.findMaterialOuts(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncMaterialOut(id, forceSync));
    }

    public BulkSyncResponse syncContras(BulkSyncRequest request) {
        return syncList("CONTRA", erpEntityAdapterService.findContras(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncContra(id, forceSync));
    }

    public BulkSyncResponse syncJournals(BulkSyncRequest request) {
        return syncList("JOURNAL", erpEntityAdapterService.findJournals(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncJournal(id, forceSync));
    }

    public BulkSyncResponse syncDebitNotes(BulkSyncRequest request) {
        return syncList("DEBIT_NOTE", erpEntityAdapterService.findDebitNotes(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncDebitNote(id, forceSync));
    }

    public BulkSyncResponse syncCreditNotes(BulkSyncRequest request) {
        return syncList("CREDIT_NOTE", erpEntityAdapterService.findCreditNotes(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncCreditNote(id, forceSync));
    }

    public BulkSyncResponse syncRejectionIns(BulkSyncRequest request) {
        return syncList("REJECTION_IN", erpEntityAdapterService.findRejectionIns(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncRejectionIn(id, forceSync));
    }

    public BulkSyncResponse syncRejectionOuts(BulkSyncRequest request) {
        return syncList("REJECTION_OUT", erpEntityAdapterService.findRejectionOuts(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncRejectionOut(id, forceSync));
    }

    public BulkSyncResponse syncPayrollVouchers(BulkSyncRequest request) {
        return syncList("PAYROLL", erpEntityAdapterService.findPayrollVouchers(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncPayrollVoucher(id, forceSync));
    }

    public BulkSyncResponse syncPhysicalStocks(BulkSyncRequest request) {
        return syncList("PHYSICAL_STOCK", erpEntityAdapterService.findPhysicalStocks(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncPhysicalStock(id, forceSync));
    }

    public BulkSyncResponse syncAttendanceVouchers(BulkSyncRequest request) {
        return syncList("ATTENDANCE_VOUCHER", erpEntityAdapterService.findAttendanceVouchers(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncAttendanceVoucher(id, forceSync));
    }

    public BulkSyncResponse syncJobWorkInOrders(BulkSyncRequest request) {
        return syncList("JOB_WORK_IN_ORDER", erpEntityAdapterService.findJobWorkInOrders(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncJobWorkInOrder(id, forceSync));
    }

    public BulkSyncResponse syncJobWorkOutOrders(BulkSyncRequest request) {
        return syncList("JOB_WORK_OUT_ORDER", erpEntityAdapterService.findJobWorkOutOrders(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncJobWorkOutOrder(id, forceSync));
    }

    public BulkSyncResponse syncMemorandums(BulkSyncRequest request) {
        return syncList("MEMORANDUM", erpEntityAdapterService.findMemorandums(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncMemorandum(id, forceSync));
    }

    public BulkSyncResponse syncReversingJournals(BulkSyncRequest request) {
        return syncList("REVERSING_JOURNAL", erpEntityAdapterService.findReversingJournals(request.getFromDate(), request.getToDate(), request.getBusinessUnitId()), request, (id, forceSync) -> tallySyncService.syncReversingJournal(id, forceSync));
    }

    private BulkSyncResponse syncList(String entityType, List<Map<String, Object>> items, BulkSyncRequest request, BulkExecutor executor) {
        BulkSyncResponse response = new BulkSyncResponse();
        response.setTotal(items.size());
        for (Map<String, Object> item : items) {
            Long id = ErpPayloadSupport.longValue(item, "id");
            if (id == null) {
                response.setFailed(response.getFailed() + 1);
                response.getFailedItems().add(new BulkSyncResponse.FailedItem(null, "Entity id missing"));
                continue;
            }
            if (request.isSkipAlreadySynced() && tallySyncLogService.hasSuccessfulSync(entityType, id)) {
                response.setSkipped(response.getSkipped() + 1);
                continue;
            }
            TallySyncResult result = executor.execute(id, request.isForceSync());
            if (result.isSuccess()) {
                response.setSuccess(response.getSuccess() + 1);
            } else if ("DUPLICATE".equalsIgnoreCase(result.getStatus())) {
                response.setSkipped(response.getSkipped() + 1);
            } else {
                response.setFailed(response.getFailed() + 1);
                response.getFailedItems().add(new BulkSyncResponse.FailedItem(id, result.getErrors().isEmpty() ? result.getMessage() : result.getErrors().get(0)));
            }
        }
        return response;
    }

    private void merge(BulkSyncResponse totals, BulkSyncResponse add) {
        totals.setTotal(totals.getTotal() + add.getTotal());
        totals.setSuccess(totals.getSuccess() + add.getSuccess());
        totals.setFailed(totals.getFailed() + add.getFailed());
        totals.setSkipped(totals.getSkipped() + add.getSkipped());
        totals.getFailedItems().addAll(add.getFailedItems());
    }

    private interface BulkExecutor {
        TallySyncResult execute(Long id, boolean forceSync);
    }
}
