package com.tallybackend.sync.controller;

import com.tallybackend.sync.dto.BulkSyncRequest;
import com.tallybackend.sync.dto.BulkSyncResponse;
import com.tallybackend.sync.service.TallyBulkSyncService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tally/bulk-sync")
@Tag(name = "Tally Bulk Sync")
public class TallyBulkSyncController {

    private final TallyBulkSyncService tallyBulkSyncService;

    public TallyBulkSyncController(TallyBulkSyncService tallyBulkSyncService) {
        this.tallyBulkSyncService = tallyBulkSyncService;
    }

    @PostMapping("/customers")
    public BulkSyncResponse customers(@RequestBody BulkSyncRequest request) {
        return tallyBulkSyncService.syncCustomers(request);
    }

    @PostMapping("/suppliers")
    public BulkSyncResponse suppliers(@RequestBody BulkSyncRequest request) {
        return tallyBulkSyncService.syncSuppliers(request);
    }

    @PostMapping("/products")
    public BulkSyncResponse products(@RequestBody BulkSyncRequest request) {
        return tallyBulkSyncService.syncProducts(request);
    }

    @PostMapping("/sales-invoices")
    public BulkSyncResponse salesInvoices(@RequestBody BulkSyncRequest request) {
        return tallyBulkSyncService.syncSalesInvoices(request);
    }

    @PostMapping("/purchase-invoices")
    public BulkSyncResponse purchaseInvoices(@RequestBody BulkSyncRequest request) {
        return tallyBulkSyncService.syncPurchaseInvoices(request);
    }

    @PostMapping("/payments")
    public BulkSyncResponse payments(@RequestBody BulkSyncRequest request) {
        return tallyBulkSyncService.syncPayments(request);
    }

    @PostMapping("/all-masters")
    public BulkSyncResponse allMasters(@RequestBody BulkSyncRequest request) {
        return tallyBulkSyncService.syncAllMasters(request);
    }

    @PostMapping("/stock-groups") public BulkSyncResponse stockGroups(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncStockGroups(request); }
    @PostMapping("/stock-categories") public BulkSyncResponse stockCategories(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncStockCategories(request); }
    @PostMapping("/godowns") public BulkSyncResponse godowns(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncGodowns(request); }
    @PostMapping("/cost-categories") public BulkSyncResponse costCategories(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncCostCategories(request); }
    @PostMapping("/cost-centres") public BulkSyncResponse costCentres(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncCostCentres(request); }
    @PostMapping("/boms") public BulkSyncResponse boms(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncBoms(request); }
    @PostMapping("/price-levels") public BulkSyncResponse priceLevels(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncPriceLevels(request); }
    @PostMapping("/voucher-types") public BulkSyncResponse voucherTypes(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncVoucherTypes(request); }
    @PostMapping("/budgets") public BulkSyncResponse budgets(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncBudgets(request); }
    @PostMapping("/employees") public BulkSyncResponse employees(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncEmployees(request); }
    @PostMapping("/employee-groups") public BulkSyncResponse employeeGroups(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncEmployeeGroups(request); }
    @PostMapping("/pay-heads") public BulkSyncResponse payHeads(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncPayHeads(request); }
    @PostMapping("/attendance-types") public BulkSyncResponse attendanceTypes(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncAttendanceTypes(request); }
    @PostMapping("/sales-orders") public BulkSyncResponse salesOrders(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncSalesOrders(request); }
    @PostMapping("/purchase-orders") public BulkSyncResponse purchaseOrders(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncPurchaseOrders(request); }
    @PostMapping("/delivery-notes") public BulkSyncResponse deliveryNotes(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncDeliveryNotes(request); }
    @PostMapping("/goods-receipts") public BulkSyncResponse goodsReceipts(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncGoodsReceipts(request); }
    @PostMapping("/stock-journals") public BulkSyncResponse stockJournals(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncStockJournals(request); }
    @PostMapping("/material-ins") public BulkSyncResponse materialIns(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncMaterialIns(request); }
    @PostMapping("/material-outs") public BulkSyncResponse materialOuts(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncMaterialOuts(request); }
    @PostMapping("/contras") public BulkSyncResponse contras(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncContras(request); }
    @PostMapping("/journals") public BulkSyncResponse journals(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncJournals(request); }
    @PostMapping("/debit-notes") public BulkSyncResponse debitNotes(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncDebitNotes(request); }
    @PostMapping("/credit-notes") public BulkSyncResponse creditNotes(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncCreditNotes(request); }
    @PostMapping("/rejections-in") public BulkSyncResponse rejectionsIn(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncRejectionIns(request); }
    @PostMapping("/rejections-out") public BulkSyncResponse rejectionsOut(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncRejectionOuts(request); }
    @PostMapping("/payroll") public BulkSyncResponse payroll(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncPayrollVouchers(request); }
    @PostMapping("/physical-stocks") public BulkSyncResponse physicalStocks(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncPhysicalStocks(request); }
    @PostMapping("/attendance-vouchers") public BulkSyncResponse attendanceVouchers(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncAttendanceVouchers(request); }
    @PostMapping("/job-work-in-orders") public BulkSyncResponse jobWorkInOrders(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncJobWorkInOrders(request); }
    @PostMapping("/job-work-out-orders") public BulkSyncResponse jobWorkOutOrders(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncJobWorkOutOrders(request); }
    @PostMapping("/memorandums") public BulkSyncResponse memorandums(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncMemorandums(request); }
    @PostMapping("/reversing-journals") public BulkSyncResponse reversingJournals(@RequestBody BulkSyncRequest request) { return tallyBulkSyncService.syncReversingJournals(request); }
}
