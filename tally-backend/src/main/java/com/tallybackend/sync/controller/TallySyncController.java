package com.tallybackend.sync.controller;

import com.tallybackend.sync.dto.TallySyncResult;
import com.tallybackend.sync.service.TallySyncService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
@Tag(name = "Tally Sync APIs")
public class TallySyncController {

    private final TallySyncService tallySyncService;

    public TallySyncController(TallySyncService tallySyncService) {
        this.tallySyncService = tallySyncService;
    }

    @PostMapping("/customer/{customerId}")
    public TallySyncResult syncCustomer(@PathVariable Long customerId,
                                        @RequestParam(defaultValue = "false") boolean forceSync) {
        return tallySyncService.syncCustomer(customerId, forceSync);
    }

    @PostMapping("/supplier/{supplierId}")
    public TallySyncResult syncSupplier(@PathVariable Long supplierId,
                                        @RequestParam(defaultValue = "false") boolean forceSync) {
        return tallySyncService.syncSupplier(supplierId, forceSync);
    }

    @PostMapping("/product/{productId}")
    public TallySyncResult syncProduct(@PathVariable Long productId,
                                       @RequestParam(defaultValue = "false") boolean forceSync) {
        return tallySyncService.syncProduct(productId, forceSync);
    }

    @PostMapping("/sales-invoice/{invoiceId}")
    public TallySyncResult syncSalesInvoice(@PathVariable Long invoiceId,
                                            @RequestParam(defaultValue = "false") boolean forceSync) {
        return tallySyncService.syncSalesInvoice(invoiceId, forceSync);
    }

    @PostMapping("/purchase-invoice/{invoiceId}")
    public TallySyncResult syncPurchaseInvoice(@PathVariable Long invoiceId,
                                               @RequestParam(defaultValue = "false") boolean forceSync) {
        return tallySyncService.syncPurchaseInvoice(invoiceId, forceSync);
    }

    @PostMapping("/payment/{paymentId}")
    public TallySyncResult syncPayment(@PathVariable Long paymentId,
                                       @RequestParam(defaultValue = "false") boolean forceSync) {
        return tallySyncService.syncPayment(paymentId, forceSync);
    }

    @PostMapping("/refund/{paymentId}")
    public TallySyncResult syncRefund(@PathVariable Long paymentId,
                                      @RequestParam(defaultValue = "false") boolean forceSync) {
        return tallySyncService.syncRefund(paymentId, forceSync);
    }

    @PostMapping("/cancel-invoice/{invoiceId}")
    public TallySyncResult syncCancelInvoice(@PathVariable Long invoiceId,
                                             @RequestParam(defaultValue = "false") boolean forceSync) {
        return tallySyncService.syncCancelInvoice(invoiceId, forceSync);
    }

    @PostMapping("/return-invoice/{invoiceId}")
    public TallySyncResult syncReturnInvoice(@PathVariable Long invoiceId,
                                             @RequestParam(defaultValue = "false") boolean forceSync) {
        return tallySyncService.syncReturnInvoice(invoiceId, forceSync);
    }

    @PostMapping("/stock-group/{id}")
    public TallySyncResult syncStockGroup(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncStockGroup(id, forceSync); }

    @PostMapping("/stock-category/{id}")
    public TallySyncResult syncStockCategory(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncStockCategory(id, forceSync); }

    @PostMapping("/godown/{id}")
    public TallySyncResult syncGodown(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncGodown(id, forceSync); }

    @PostMapping("/cost-category/{id}")
    public TallySyncResult syncCostCategory(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncCostCategory(id, forceSync); }

    @PostMapping("/cost-centre/{id}")
    public TallySyncResult syncCostCentre(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncCostCentre(id, forceSync); }

    @PostMapping("/bom/{id}")
    public TallySyncResult syncBom(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncBom(id, forceSync); }

    @PostMapping("/price-level/{id}")
    public TallySyncResult syncPriceLevel(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncPriceLevel(id, forceSync); }

    @PostMapping("/voucher-type/{id}")
    public TallySyncResult syncVoucherType(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncVoucherType(id, forceSync); }

    @PostMapping("/budget/{id}")
    public TallySyncResult syncBudget(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncBudget(id, forceSync); }

    @PostMapping("/employee/{id}")
    public TallySyncResult syncEmployee(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncEmployee(id, forceSync); }

    @PostMapping("/employee-group/{id}")
    public TallySyncResult syncEmployeeGroup(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncEmployeeGroup(id, forceSync); }

    @PostMapping("/pay-head/{id}")
    public TallySyncResult syncPayHead(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncPayHead(id, forceSync); }

    @PostMapping("/attendance-type/{id}")
    public TallySyncResult syncAttendanceType(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncAttendanceType(id, forceSync); }

    @PostMapping("/sales-order/{id}")
    public TallySyncResult syncSalesOrder(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncSalesOrder(id, forceSync); }

    @PostMapping("/purchase-order/{id}")
    public TallySyncResult syncPurchaseOrder(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncPurchaseOrder(id, forceSync); }

    @PostMapping("/delivery-note/{id}")
    public TallySyncResult syncDeliveryNote(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncDeliveryNote(id, forceSync); }

    @PostMapping("/goods-receipt/{id}")
    public TallySyncResult syncGoodsReceipt(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncGoodsReceipt(id, forceSync); }

    @PostMapping("/stock-journal/{id}")
    public TallySyncResult syncStockJournal(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncStockJournal(id, forceSync); }

    @PostMapping("/material-in/{id}")
    public TallySyncResult syncMaterialIn(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncMaterialIn(id, forceSync); }

    @PostMapping("/material-out/{id}")
    public TallySyncResult syncMaterialOut(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncMaterialOut(id, forceSync); }

    @PostMapping("/contra/{id}")
    public TallySyncResult syncContra(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncContra(id, forceSync); }

    @PostMapping("/journal/{id}")
    public TallySyncResult syncJournal(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncJournal(id, forceSync); }

    @PostMapping("/debit-note/{id}")
    public TallySyncResult syncDebitNote(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncDebitNote(id, forceSync); }

    @PostMapping("/credit-note/{id}")
    public TallySyncResult syncCreditNote(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncCreditNote(id, forceSync); }

    @PostMapping("/rejection-in/{id}")
    public TallySyncResult syncRejectionIn(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncRejectionIn(id, forceSync); }

    @PostMapping("/rejection-out/{id}")
    public TallySyncResult syncRejectionOut(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncRejectionOut(id, forceSync); }

    @PostMapping("/payroll/{id}")
    public TallySyncResult syncPayrollVoucher(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncPayrollVoucher(id, forceSync); }

    @PostMapping("/physical-stock/{id}")
    public TallySyncResult syncPhysicalStock(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncPhysicalStock(id, forceSync); }

    @PostMapping("/attendance/{id}")
    public TallySyncResult syncAttendanceVoucher(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncAttendanceVoucher(id, forceSync); }

    @PostMapping("/job-work-in-order/{id}")
    public TallySyncResult syncJobWorkInOrder(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncJobWorkInOrder(id, forceSync); }

    @PostMapping("/job-work-out-order/{id}")
    public TallySyncResult syncJobWorkOutOrder(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncJobWorkOutOrder(id, forceSync); }

    @PostMapping("/memorandum/{id}")
    public TallySyncResult syncMemorandum(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncMemorandum(id, forceSync); }

    @PostMapping("/reversing-journal/{id}")
    public TallySyncResult syncReversingJournal(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean forceSync) { return tallySyncService.syncReversingJournal(id, forceSync); }
}
