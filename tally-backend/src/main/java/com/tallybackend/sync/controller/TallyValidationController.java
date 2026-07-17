package com.tallybackend.sync.controller;

import com.tallybackend.sync.dto.TallyValidationResult;
import com.tallybackend.sync.service.TallySyncService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tally/validate")
@Tag(name = "Tally Validation")
public class TallyValidationController {

    private final TallySyncService tallySyncService;

    public TallyValidationController(TallySyncService tallySyncService) {
        this.tallySyncService = tallySyncService;
    }

    @PostMapping("/customer/{customerId}")
    public TallyValidationResult customer(@PathVariable Long customerId) {
        return tallySyncService.validateCustomer(customerId);
    }

    @PostMapping("/supplier/{supplierId}")
    public TallyValidationResult supplier(@PathVariable Long supplierId) {
        return tallySyncService.validateSupplier(supplierId);
    }

    @PostMapping("/product/{productId}")
    public TallyValidationResult product(@PathVariable Long productId) {
        return tallySyncService.validateProduct(productId);
    }

    @PostMapping("/sales-invoice/{invoiceId}")
    public TallyValidationResult salesInvoice(@PathVariable Long invoiceId) {
        return tallySyncService.validateSalesInvoice(invoiceId);
    }

    @PostMapping("/purchase-invoice/{invoiceId}")
    public TallyValidationResult purchaseInvoice(@PathVariable Long invoiceId) {
        return tallySyncService.validatePurchaseInvoice(invoiceId);
    }

    @PostMapping("/payment/{paymentId}")
    public TallyValidationResult payment(@PathVariable Long paymentId) {
        return tallySyncService.validatePayment(paymentId);
    }

    @PostMapping("/stock-group/{id}") public TallyValidationResult stockGroup(@PathVariable Long id) { return tallySyncService.validateStockGroup(id); }
    @PostMapping("/stock-category/{id}") public TallyValidationResult stockCategory(@PathVariable Long id) { return tallySyncService.validateStockCategory(id); }
    @PostMapping("/godown/{id}") public TallyValidationResult godown(@PathVariable Long id) { return tallySyncService.validateGodown(id); }
    @PostMapping("/cost-category/{id}") public TallyValidationResult costCategory(@PathVariable Long id) { return tallySyncService.validateCostCategory(id); }
    @PostMapping("/cost-centre/{id}") public TallyValidationResult costCentre(@PathVariable Long id) { return tallySyncService.validateCostCentre(id); }
    @PostMapping("/bom/{id}") public TallyValidationResult bom(@PathVariable Long id) { return tallySyncService.validateBom(id); }
    @PostMapping("/price-level/{id}") public TallyValidationResult priceLevel(@PathVariable Long id) { return tallySyncService.validatePriceLevel(id); }
    @PostMapping("/voucher-type/{id}") public TallyValidationResult voucherType(@PathVariable Long id) { return tallySyncService.validateVoucherType(id); }
    @PostMapping("/budget/{id}") public TallyValidationResult budget(@PathVariable Long id) { return tallySyncService.validateBudget(id); }
    @PostMapping("/employee/{id}") public TallyValidationResult employee(@PathVariable Long id) { return tallySyncService.validateEmployee(id); }
    @PostMapping("/employee-group/{id}") public TallyValidationResult employeeGroup(@PathVariable Long id) { return tallySyncService.validateEmployeeGroup(id); }
    @PostMapping("/pay-head/{id}") public TallyValidationResult payHead(@PathVariable Long id) { return tallySyncService.validatePayHead(id); }
    @PostMapping("/attendance-type/{id}") public TallyValidationResult attendanceType(@PathVariable Long id) { return tallySyncService.validateAttendanceType(id); }
}
