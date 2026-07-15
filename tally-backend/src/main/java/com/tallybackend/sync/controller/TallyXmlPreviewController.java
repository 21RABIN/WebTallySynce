package com.tallybackend.sync.controller;

import com.tallybackend.sync.dto.XmlPreviewResponse;
import com.tallybackend.sync.service.TallySyncService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tally/xml-preview")
@Tag(name = "Tally XML Preview")
public class TallyXmlPreviewController {

    private final TallySyncService tallySyncService;

    public TallyXmlPreviewController(TallySyncService tallySyncService) {
        this.tallySyncService = tallySyncService;
    }

    @GetMapping("/customer/{customerId}")
    public XmlPreviewResponse customer(@PathVariable Long customerId) {
        return tallySyncService.previewCustomer(customerId);
    }

    @GetMapping("/supplier/{supplierId}")
    public XmlPreviewResponse supplier(@PathVariable Long supplierId) {
        return tallySyncService.previewSupplier(supplierId);
    }

    @GetMapping("/product/{productId}")
    public XmlPreviewResponse product(@PathVariable Long productId) {
        return tallySyncService.previewProduct(productId);
    }

    @GetMapping("/sales-invoice/{invoiceId}")
    public XmlPreviewResponse salesInvoice(@PathVariable Long invoiceId) {
        return tallySyncService.previewSalesInvoice(invoiceId);
    }

    @GetMapping("/purchase-invoice/{invoiceId}")
    public XmlPreviewResponse purchaseInvoice(@PathVariable Long invoiceId) {
        return tallySyncService.previewPurchaseInvoice(invoiceId);
    }

    @GetMapping("/payment/{paymentId}")
    public XmlPreviewResponse payment(@PathVariable Long paymentId) {
        return tallySyncService.previewPayment(paymentId);
    }

    @GetMapping("/refund/{paymentId}")
    public XmlPreviewResponse refund(@PathVariable Long paymentId) {
        return tallySyncService.previewRefund(paymentId);
    }

    @GetMapping("/stock-group/{id}") public XmlPreviewResponse stockGroup(@PathVariable Long id) { return tallySyncService.previewStockGroup(id); }
    @GetMapping("/stock-category/{id}") public XmlPreviewResponse stockCategory(@PathVariable Long id) { return tallySyncService.previewStockCategory(id); }
    @GetMapping("/godown/{id}") public XmlPreviewResponse godown(@PathVariable Long id) { return tallySyncService.previewGodown(id); }
    @GetMapping("/cost-category/{id}") public XmlPreviewResponse costCategory(@PathVariable Long id) { return tallySyncService.previewCostCategory(id); }
    @GetMapping("/cost-centre/{id}") public XmlPreviewResponse costCentre(@PathVariable Long id) { return tallySyncService.previewCostCentre(id); }
    @GetMapping("/bom/{id}") public XmlPreviewResponse bom(@PathVariable Long id) { return tallySyncService.previewBom(id); }
    @GetMapping("/price-level/{id}") public XmlPreviewResponse priceLevel(@PathVariable Long id) { return tallySyncService.previewPriceLevel(id); }
    @GetMapping("/voucher-type/{id}") public XmlPreviewResponse voucherType(@PathVariable Long id) { return tallySyncService.previewVoucherType(id); }
    @GetMapping("/budget/{id}") public XmlPreviewResponse budget(@PathVariable Long id) { return tallySyncService.previewBudget(id); }
    @GetMapping("/employee/{id}") public XmlPreviewResponse employee(@PathVariable Long id) { return tallySyncService.previewEmployee(id); }
    @GetMapping("/employee-group/{id}") public XmlPreviewResponse employeeGroup(@PathVariable Long id) { return tallySyncService.previewEmployeeGroup(id); }
    @GetMapping("/pay-head/{id}") public XmlPreviewResponse payHead(@PathVariable Long id) { return tallySyncService.previewPayHead(id); }
    @GetMapping("/attendance-type/{id}") public XmlPreviewResponse attendanceType(@PathVariable Long id) { return tallySyncService.previewAttendanceType(id); }
    @GetMapping("/sales-order/{id}") public XmlPreviewResponse salesOrder(@PathVariable Long id) { return tallySyncService.previewSalesOrder(id); }
    @GetMapping("/purchase-order/{id}") public XmlPreviewResponse purchaseOrder(@PathVariable Long id) { return tallySyncService.previewPurchaseOrder(id); }
    @GetMapping("/delivery-note/{id}") public XmlPreviewResponse deliveryNote(@PathVariable Long id) { return tallySyncService.previewDeliveryNote(id); }
    @GetMapping("/goods-receipt/{id}") public XmlPreviewResponse goodsReceipt(@PathVariable Long id) { return tallySyncService.previewGoodsReceipt(id); }
    @GetMapping("/stock-journal/{id}") public XmlPreviewResponse stockJournal(@PathVariable Long id) { return tallySyncService.previewStockJournal(id); }
    @GetMapping("/material-in/{id}") public XmlPreviewResponse materialIn(@PathVariable Long id) { return tallySyncService.previewMaterialIn(id); }
    @GetMapping("/material-out/{id}") public XmlPreviewResponse materialOut(@PathVariable Long id) { return tallySyncService.previewMaterialOut(id); }
    @GetMapping("/contra/{id}") public XmlPreviewResponse contra(@PathVariable Long id) { return tallySyncService.previewContra(id); }
    @GetMapping("/journal/{id}") public XmlPreviewResponse journal(@PathVariable Long id) { return tallySyncService.previewJournal(id); }
    @GetMapping("/debit-note/{id}") public XmlPreviewResponse debitNote(@PathVariable Long id) { return tallySyncService.previewDebitNote(id); }
    @GetMapping("/credit-note/{id}") public XmlPreviewResponse creditNote(@PathVariable Long id) { return tallySyncService.previewCreditNote(id); }
    @GetMapping("/rejection-in/{id}") public XmlPreviewResponse rejectionIn(@PathVariable Long id) { return tallySyncService.previewRejectionIn(id); }
    @GetMapping("/rejection-out/{id}") public XmlPreviewResponse rejectionOut(@PathVariable Long id) { return tallySyncService.previewRejectionOut(id); }
    @GetMapping("/payroll/{id}") public XmlPreviewResponse payroll(@PathVariable Long id) { return tallySyncService.previewPayrollVoucher(id); }
    @GetMapping("/physical-stock/{id}") public XmlPreviewResponse physicalStock(@PathVariable Long id) { return tallySyncService.previewPhysicalStock(id); }
    @GetMapping("/attendance/{id}") public XmlPreviewResponse attendance(@PathVariable Long id) { return tallySyncService.previewAttendanceVoucher(id); }
    @GetMapping("/job-work-in-order/{id}") public XmlPreviewResponse jobWorkInOrder(@PathVariable Long id) { return tallySyncService.previewJobWorkInOrder(id); }
    @GetMapping("/job-work-out-order/{id}") public XmlPreviewResponse jobWorkOutOrder(@PathVariable Long id) { return tallySyncService.previewJobWorkOutOrder(id); }
    @GetMapping("/memorandum/{id}") public XmlPreviewResponse memorandum(@PathVariable Long id) { return tallySyncService.previewMemorandum(id); }
    @GetMapping("/reversing-journal/{id}") public XmlPreviewResponse reversingJournal(@PathVariable Long id) { return tallySyncService.previewReversingJournal(id); }
}
