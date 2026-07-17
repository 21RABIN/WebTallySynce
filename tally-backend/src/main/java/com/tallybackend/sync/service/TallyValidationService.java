package com.tallybackend.sync.service;

import com.tallybackend.sync.dto.TallyValidationResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class TallyValidationService {
    private static final Pattern GSTIN_PATTERN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][A-Z0-9]Z[A-Z0-9]$");

    private final TallyLedgerMappingService tallyLedgerMappingService;

    public TallyValidationService(TallyLedgerMappingService tallyLedgerMappingService) {
        this.tallyLedgerMappingService = tallyLedgerMappingService;
    }

    public TallyValidationResult validateCustomer(Map<String, Object> customer) {
        TallyValidationResult result = new TallyValidationResult();
        String name = ErpPayloadSupport.stringValue(customer, "name", "customerName");
        if (name == null) {
            result.addError("Customer name is required");
        }
        String group = ErpPayloadSupport.stringValue(customer, "ledgerGroup", "groupName", "parent");
        if (group == null) {
            result.addWarning("Ledger group missing; default Sundry Debtors will be used");
        }
        validateGstinAndState(customer, result);
        return result;
    }

    public TallyValidationResult validateSupplier(Map<String, Object> supplier) {
        TallyValidationResult result = new TallyValidationResult();
        String name = ErpPayloadSupport.stringValue(supplier, "name", "supplierName");
        if (name == null) {
            result.addError("Supplier name is required");
        }
        String group = ErpPayloadSupport.stringValue(supplier, "ledgerGroup", "groupName", "parent");
        if (group == null) {
            result.addWarning("Ledger group missing; default Sundry Creditors will be used");
        }
        return result;
    }

    public TallyValidationResult validateProduct(Map<String, Object> product) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(product, "name", "productName") == null) {
            result.addError("Product name is required");
        }
        if (ErpPayloadSupport.stringValue(product, "uom", "unit", "unitName", "baseUnit") == null) {
            result.addError("UOM is required");
        }
        if (ErpPayloadSupport.stringValue(product, "stockGroup", "groupName", "parent") == null) {
            result.addWarning("Stock group missing; default Primary will be used");
        }
        BigDecimal gstRate = ErpPayloadSupport.decimalValue(product, "gstRate", "taxRate");
        if (gstRate != null && gstRate.compareTo(BigDecimal.ZERO) < 0) {
            result.addError("GST rate must be valid");
        }
        return result;
    }

    public TallyValidationResult validateSalesInvoice(Map<String, Object> invoice) {
        TallyValidationResult result = commonInvoiceValidation(invoice, "customer");
        Long businessUnitId = ErpPayloadSupport.longValue(invoice, "businessUnitId");
        requireMapping(result, businessUnitId, "SALES", "Sales ledger mapping not configured");
        requireCustomerLedger(result, invoice);
        BigDecimal gstAmount = zeroIfNull(ErpPayloadSupport.decimalValue(invoice, "gstAmount", "taxAmount"));
        if (gstAmount.compareTo(BigDecimal.ZERO) > 0) {
            requireAnyMapping(result, businessUnitId,
                    new String[]{"OUTPUT_CGST", "OUTPUT_SGST", "OUTPUT_IGST"},
                    "GST ledger mapping required when GST amount > 0");
        }
        requireBalancedInvoice(result, invoice);
        return result;
    }

    public TallyValidationResult validatePurchaseInvoice(Map<String, Object> invoice) {
        TallyValidationResult result = commonInvoiceValidation(invoice, "supplier");
        Long businessUnitId = ErpPayloadSupport.longValue(invoice, "businessUnitId");
        requireMapping(result, businessUnitId, "PURCHASE", "Purchase ledger mapping required");
        String supplierName = ErpPayloadSupport.resolvePartyName(invoice, "supplier", "supplierName", "supplierLedgerName", "partyLedgerName");
        if (supplierName == null) {
            result.addError("Supplier required");
        }
        BigDecimal gstAmount = zeroIfNull(ErpPayloadSupport.decimalValue(invoice, "gstAmount", "taxAmount"));
        if (gstAmount.compareTo(BigDecimal.ZERO) > 0) {
            requireAnyMapping(result, businessUnitId,
                    new String[]{"INPUT_CGST", "INPUT_SGST", "INPUT_IGST"},
                    "GST input ledger mapping required when GST amount > 0");
        }
        return result;
    }

    public TallyValidationResult validatePayment(Map<String, Object> payment) {
        TallyValidationResult result = new TallyValidationResult();
        BigDecimal amount = ErpPayloadSupport.decimalValue(payment, "amount", "paymentAmount");
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            result.addError("Amount > 0 is required");
        }
        String mode = ErpPayloadSupport.stringValue(payment, "mode", "paymentMode");
        if (mode == null) {
            result.addError("Mode is required");
        }
        Long businessUnitId = ErpPayloadSupport.longValue(payment, "businessUnitId");
        if ("CASH".equalsIgnoreCase(mode)) {
            requireMapping(result, businessUnitId, "CASH", "Cash ledger mapping required");
        } else {
            requireMapping(result, businessUnitId, "BANK", "Cash/bank ledger mapping required");
        }
        String party = ErpPayloadSupport.resolvePartyName(payment, "supplier", "partyLedgerName", "supplierLedgerName", "customerLedgerName", "partyName");
        if (party == null) {
            result.addError("Party ledger required");
        }
        return result;
    }

    private TallyValidationResult commonInvoiceValidation(Map<String, Object> invoice, String partyType) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(invoice, "invoiceNumber", "number") == null) {
            result.addError("Invoice number required");
        }
        if (ErpPayloadSupport.tallyDate(invoice, "invoiceDate", "date") == null) {
            result.addError("Invoice date required");
        }
        String partyName = "customer".equalsIgnoreCase(partyType)
                ? ErpPayloadSupport.resolvePartyName(invoice, "customer", "customerName", "customerLedgerName", "partyLedgerName")
                : ErpPayloadSupport.resolvePartyName(invoice, "supplier", "supplierName", "supplierLedgerName", "partyLedgerName");
        if (partyName == null) {
            result.addError(("customer".equalsIgnoreCase(partyType) ? "Customer" : "Supplier") + " required");
        }
        List<Map<String, Object>> items = ErpPayloadSupport.listOfMaps(invoice, "items", "invoiceItems", "lines");
        if (items.isEmpty()) {
            result.addError("Invoice items required");
        }
        BigDecimal taxableAmount = zeroIfNull(ErpPayloadSupport.decimalValue(invoice, "taxableAmount", "subTotal"));
        if (taxableAmount.compareTo(BigDecimal.ZERO) < 0) {
            result.addError("Taxable amount must be >= 0");
        }
        BigDecimal totalAmount = ErpPayloadSupport.decimalValue(invoice, "totalAmount", "grandTotal");
        if (totalAmount == null || totalAmount.compareTo(BigDecimal.ZERO) <= 0) {
            result.addError("Total amount must be > 0");
        }
        BigDecimal gstAmount = zeroIfNull(ErpPayloadSupport.decimalValue(invoice, "gstAmount", "taxAmount"));
        BigDecimal discount = zeroIfNull(ErpPayloadSupport.decimalValue(invoice, "discountAmount", "discount"));
        BigDecimal roundOff = zeroIfNull(ErpPayloadSupport.decimalValue(invoice, "roundOff", "roundOffAmount"));
        if (totalAmount != null) {
            BigDecimal expected = taxableAmount.add(gstAmount).subtract(discount).add(roundOff);
            if (expected.compareTo(totalAmount) != 0) {
                result.addError("Total amount must match taxable + GST - discount + roundOff");
            }
        }
        return result;
    }

    private void validateGstinAndState(Map<String, Object> payload, TallyValidationResult result) {
        String gstin = ErpPayloadSupport.stringValue(payload, "gstin", "gstNumber");
        if (gstin != null && !GSTIN_PATTERN.matcher(gstin).matches()) {
            result.addError("GSTIN format is invalid");
        }
        Boolean gstEnabled = ErpPayloadSupport.booleanValue(payload, "gstEnabled", "isGstEnabled");
        if (Boolean.TRUE.equals(gstEnabled) && ErpPayloadSupport.stringValue(payload, "stateName", "state") == null) {
            result.addError("State name required if GST enabled");
        }
        if (gstin == null) {
            result.addWarning("GST number is empty");
        }
    }

    private void requireMapping(TallyValidationResult result, Long businessUnitId, String type, String message) {
        try {
            tallyLedgerMappingService.getRequiredMapping(businessUnitId, type);
        } catch (Exception ex) {
            result.addError(message);
        }
    }

    private void requireAnyMapping(TallyValidationResult result, Long businessUnitId, String[] types, String message) {
        for (String type : types) {
            try {
                tallyLedgerMappingService.getRequiredMapping(businessUnitId, type);
                return;
            } catch (Exception ignored) {
            }
        }
        result.addError(message);
    }

    private void requireCustomerLedger(TallyValidationResult result, Map<String, Object> invoice) {
        String customer = ErpPayloadSupport.resolvePartyName(invoice, "customer", "customerName", "customerLedgerName", "partyLedgerName");
        if (customer == null) {
            result.addError("Customer ledger name is missing");
        }
    }

    private void requireBalancedInvoice(TallyValidationResult result, Map<String, Object> invoice) {
        BigDecimal totalAmount = zeroIfNull(ErpPayloadSupport.decimalValue(invoice, "totalAmount", "grandTotal"));
        if (totalAmount.compareTo(BigDecimal.ZERO) == 0) {
            result.addError("Voucher should be balanced");
        }
    }

    public TallyValidationResult validateStockGroup(Map<String, Object> payload) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(payload, "name", "stockGroupName") == null) {
            result.addError("Stock group name is required");
        }
        return result;
    }

    public TallyValidationResult validateStockCategory(Map<String, Object> payload) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(payload, "name", "stockCategoryName") == null) {
            result.addError("Stock category name is required");
        }
        return result;
    }

    public TallyValidationResult validateGodown(Map<String, Object> payload) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(payload, "name", "godownName") == null) {
            result.addError("Godown name is required");
        }
        return result;
    }

    public TallyValidationResult validateCostCategory(Map<String, Object> payload) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(payload, "name", "costCategoryName") == null) {
            result.addError("Cost category name is required");
        }
        return result;
    }

    public TallyValidationResult validateCostCentre(Map<String, Object> payload) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(payload, "name", "costCentreName") == null) {
            result.addError("Cost centre name is required");
        }
        return result;
    }

    public TallyValidationResult validateBom(Map<String, Object> payload) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(payload, "name", "bomName") == null) {
            result.addError("BOM name is required");
        }
        if (ErpPayloadSupport.stringValue(payload, "stockItem", "itemName") == null) {
            result.addError("Stock item is required for BOM");
        }
        return result;
    }

    public TallyValidationResult validatePriceLevel(Map<String, Object> payload) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(payload, "name", "priceLevelName") == null) {
            result.addError("Price level name is required");
        }
        return result;
    }

    public TallyValidationResult validateVoucherType(Map<String, Object> payload) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(payload, "name", "voucherTypeName") == null) {
            result.addError("Voucher type name is required");
        }
        return result;
    }

    public TallyValidationResult validateBudget(Map<String, Object> payload) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(payload, "name", "budgetName") == null) {
            result.addError("Budget name is required");
        }
        return result;
    }

    public TallyValidationResult validateEmployee(Map<String, Object> payload) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(payload, "name", "employeeName") == null) {
            result.addError("Employee name is required");
        }
        return result;
    }

    public TallyValidationResult validateEmployeeGroup(Map<String, Object> payload) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(payload, "name", "employeeGroupName") == null) {
            result.addError("Employee group name is required");
        }
        return result;
    }

    public TallyValidationResult validatePayHead(Map<String, Object> payload) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(payload, "name", "payHeadName") == null) {
            result.addError("Pay head name is required");
        }
        return result;
    }

    public TallyValidationResult validateAttendanceType(Map<String, Object> payload) {
        TallyValidationResult result = new TallyValidationResult();
        if (ErpPayloadSupport.stringValue(payload, "name", "attendanceTypeName") == null) {
            result.addError("Attendance type name is required");
        }
        return result;
    }

    public TallyValidationResult validateSimpleVoucher(Map<String, Object> payload, String voucherType) {
        TallyValidationResult result = new TallyValidationResult();
        List<Map<String, Object>> entries = ErpPayloadSupport.listOfMaps(payload, "ledgerEntries", "entries", "LEDGERENTRIES.LIST");
        if (entries.isEmpty()) {
            result.addWarning("No ledger entries provided for " + voucherType);
        }
        return result;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
