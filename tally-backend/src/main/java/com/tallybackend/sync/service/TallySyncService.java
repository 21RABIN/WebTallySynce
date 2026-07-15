package com.tallybackend.sync.service;

import com.tallybackend.sync.dto.TallyParsedResponse;
import com.tallybackend.sync.dto.TallySyncResult;
import com.tallybackend.sync.dto.TallyValidationResult;
import com.tallybackend.sync.dto.XmlPreviewResponse;
import com.tallybackend.sync.entity.TallySyncLog;
import com.tallybackend.sync.entity.TallySyncStatus;
import com.tallybackend.sync.exception.SyncApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class TallySyncService {

    private final ErpEntityAdapterService erpEntityAdapterService;
    private final TallyValidationService tallyValidationService;
    private final TallyMasterXmlBuilder tallyMasterXmlBuilder;
    private final TallyVoucherXmlBuilder tallyVoucherXmlBuilder;
    private final TallySyncLogService tallySyncLogService;
    private final TallyXmlTransportService tallyXmlTransportService;
    private final TallyResponseParser tallyResponseParser;

    public TallySyncService(ErpEntityAdapterService erpEntityAdapterService,
                            TallyValidationService tallyValidationService,
                            TallyMasterXmlBuilder tallyMasterXmlBuilder,
                            TallyVoucherXmlBuilder tallyVoucherXmlBuilder,
                            TallySyncLogService tallySyncLogService,
                            TallyXmlTransportService tallyXmlTransportService,
                            TallyResponseParser tallyResponseParser) {
        this.erpEntityAdapterService = erpEntityAdapterService;
        this.tallyValidationService = tallyValidationService;
        this.tallyMasterXmlBuilder = tallyMasterXmlBuilder;
        this.tallyVoucherXmlBuilder = tallyVoucherXmlBuilder;
        this.tallySyncLogService = tallySyncLogService;
        this.tallyXmlTransportService = tallyXmlTransportService;
        this.tallyResponseParser = tallyResponseParser;
    }

    public TallySyncResult syncCustomer(Long customerId, boolean forceSync) {
        return syncEntity("CUSTOMER", customerId, "LEDGER", forceSync,
                erpEntityAdapterService.findCustomerById(customerId),
                new Validator() {
                    @Override
                    public TallyValidationResult validate(Map<String, Object> payload) {
                        return tallyValidationService.validateCustomer(payload);
                    }
                },
                new XmlBuilder() {
                    @Override
                    public String build(Map<String, Object> payload) {
                        return tallyMasterXmlBuilder.buildCustomerLedgerXml(payload);
                    }
                },
                "Customer synced to Tally successfully");
    }

    public TallySyncResult syncSupplier(Long supplierId, boolean forceSync) {
        return syncEntity("SUPPLIER", supplierId, "LEDGER", forceSync,
                erpEntityAdapterService.findSupplierById(supplierId),
                new Validator() {
                    @Override
                    public TallyValidationResult validate(Map<String, Object> payload) {
                        return tallyValidationService.validateSupplier(payload);
                    }
                },
                new XmlBuilder() {
                    @Override
                    public String build(Map<String, Object> payload) {
                        return tallyMasterXmlBuilder.buildSupplierLedgerXml(payload);
                    }
                },
                "Supplier synced to Tally successfully");
    }

    public TallySyncResult syncProduct(Long productId, boolean forceSync) {
        return syncEntity("PRODUCT", productId, "STOCKITEM", forceSync,
                erpEntityAdapterService.findProductById(productId),
                new Validator() {
                    @Override
                    public TallyValidationResult validate(Map<String, Object> payload) {
                        return tallyValidationService.validateProduct(payload);
                    }
                },
                new XmlBuilder() {
                    @Override
                    public String build(Map<String, Object> payload) {
                        return tallyMasterXmlBuilder.buildProductStockItemXml(payload);
                    }
                },
                "Product synced to Tally successfully");
    }

    public TallySyncResult syncSalesInvoice(Long invoiceId, boolean forceSync) {
        return syncEntity("SALES_INVOICE", invoiceId, "SALES", forceSync,
                erpEntityAdapterService.findSalesInvoiceById(invoiceId),
                new Validator() {
                    @Override
                    public TallyValidationResult validate(Map<String, Object> payload) {
                        return tallyValidationService.validateSalesInvoice(payload);
                    }
                },
                new XmlBuilder() {
                    @Override
                    public String build(Map<String, Object> payload) {
                        return tallyVoucherXmlBuilder.buildSalesVoucherXml(payload);
                    }
                },
                "Sales invoice synced to Tally successfully");
    }

    public TallySyncResult syncPurchaseInvoice(Long invoiceId, boolean forceSync) {
        return syncEntity("PURCHASE_INVOICE", invoiceId, "PURCHASE", forceSync,
                erpEntityAdapterService.findPurchaseInvoiceById(invoiceId),
                new Validator() {
                    @Override
                    public TallyValidationResult validate(Map<String, Object> payload) {
                        return tallyValidationService.validatePurchaseInvoice(payload);
                    }
                },
                new XmlBuilder() {
                    @Override
                    public String build(Map<String, Object> payload) {
                        return tallyVoucherXmlBuilder.buildPurchaseVoucherXml(payload);
                    }
                },
                "Purchase invoice synced to Tally successfully");
    }

    public TallySyncResult syncPayment(Long paymentId, boolean forceSync) {
        return syncEntity("PAYMENT", paymentId, "PAYMENT", forceSync,
                erpEntityAdapterService.findPaymentById(paymentId),
                new Validator() {
                    @Override
                    public TallyValidationResult validate(Map<String, Object> payload) {
                        return tallyValidationService.validatePayment(payload);
                    }
                },
                new XmlBuilder() {
                    @Override
                    public String build(Map<String, Object> payload) {
                        return tallyVoucherXmlBuilder.buildPaymentVoucherXml(payload);
                    }
                },
                "Payment synced to Tally successfully");
    }

    public TallySyncResult syncRefund(Long paymentId, boolean forceSync) {
        return syncEntity("REFUND", paymentId, "PAYMENT", forceSync,
                erpEntityAdapterService.findRefundById(paymentId),
                new Validator() {
                    @Override
                    public TallyValidationResult validate(Map<String, Object> payload) {
                        return tallyValidationService.validatePayment(payload);
                    }
                },
                new XmlBuilder() {
                    @Override
                    public String build(Map<String, Object> payload) {
                        return tallyVoucherXmlBuilder.buildRefundVoucherXml(payload);
                    }
                },
                "Refund synced to Tally successfully");
    }

    public TallySyncResult syncCancelInvoice(Long invoiceId, boolean forceSync) {
        return syncEntity("CANCEL_INVOICE", invoiceId, "CANCELLATION", forceSync,
                erpEntityAdapterService.findCancelInvoiceById(invoiceId),
                new Validator() {
                    @Override
                    public TallyValidationResult validate(Map<String, Object> payload) {
                        return tallyValidationService.validateSalesInvoice(payload);
                    }
                },
                new XmlBuilder() {
                    @Override
                    public String build(Map<String, Object> payload) {
                        return tallyVoucherXmlBuilder.buildCancelVoucherXml(payload);
                    }
                },
                "Cancel invoice synced to Tally successfully");
    }

    public TallySyncResult syncReturnInvoice(Long invoiceId, boolean forceSync) {
        return syncEntity("RETURN_INVOICE", invoiceId, "CREDIT_NOTE", forceSync,
                erpEntityAdapterService.findReturnInvoiceById(invoiceId),
                new Validator() {
                    @Override
                    public TallyValidationResult validate(Map<String, Object> payload) {
                        return tallyValidationService.validateSalesInvoice(payload);
                    }
                },
                new XmlBuilder() {
                    @Override
                    public String build(Map<String, Object> payload) {
                        return tallyVoucherXmlBuilder.buildReturnVoucherXml(payload);
                    }
                },
                "Return invoice synced to Tally successfully");
    }

    public TallySyncResult syncStockGroup(Long id, boolean forceSync) {
        return syncEntity("STOCK_GROUP", id, "STOCKGROUP", forceSync,
                erpEntityAdapterService.findStockGroupById(id),
                payload -> tallyValidationService.validateStockGroup(payload),
                payload -> tallyMasterXmlBuilder.buildStockGroupXml(payload),
                "Stock group synced to Tally successfully");
    }

    public TallySyncResult syncStockCategory(Long id, boolean forceSync) {
        return syncEntity("STOCK_CATEGORY", id, "STOCKCATEGORY", forceSync,
                erpEntityAdapterService.findStockCategoryById(id),
                payload -> tallyValidationService.validateStockCategory(payload),
                payload -> tallyMasterXmlBuilder.buildStockCategoryXml(payload),
                "Stock category synced to Tally successfully");
    }

    public TallySyncResult syncGodown(Long id, boolean forceSync) {
        return syncEntity("GODOWN", id, "GODOWN", forceSync,
                erpEntityAdapterService.findGodownById(id),
                payload -> tallyValidationService.validateGodown(payload),
                payload -> tallyMasterXmlBuilder.buildGodownXml(payload),
                "Godown synced to Tally successfully");
    }

    public TallySyncResult syncCostCategory(Long id, boolean forceSync) {
        return syncEntity("COST_CATEGORY", id, "COSTCATEGORY", forceSync,
                erpEntityAdapterService.findCostCategoryById(id),
                payload -> tallyValidationService.validateCostCategory(payload),
                payload -> tallyMasterXmlBuilder.buildCostCategoryXml(payload),
                "Cost category synced to Tally successfully");
    }

    public TallySyncResult syncCostCentre(Long id, boolean forceSync) {
        return syncEntity("COST_CENTRE", id, "COSTCENTRE", forceSync,
                erpEntityAdapterService.findCostCentreById(id),
                payload -> tallyValidationService.validateCostCentre(payload),
                payload -> tallyMasterXmlBuilder.buildCostCentreXml(payload),
                "Cost centre synced to Tally successfully");
    }

    public TallySyncResult syncBom(Long id, boolean forceSync) {
        return syncEntity("BOM", id, "BOM", forceSync,
                erpEntityAdapterService.findBomById(id),
                payload -> tallyValidationService.validateBom(payload),
                payload -> tallyMasterXmlBuilder.buildBomXml(payload),
                "BOM synced to Tally successfully");
    }

    public TallySyncResult syncPriceLevel(Long id, boolean forceSync) {
        return syncEntity("PRICE_LEVEL", id, "PRICELEVEL", forceSync,
                erpEntityAdapterService.findPriceLevelById(id),
                payload -> tallyValidationService.validatePriceLevel(payload),
                payload -> tallyMasterXmlBuilder.buildPriceLevelXml(payload),
                "Price level synced to Tally successfully");
    }

    public TallySyncResult syncVoucherType(Long id, boolean forceSync) {
        return syncEntity("VOUCHER_TYPE", id, "VOUCHERTYPE", forceSync,
                erpEntityAdapterService.findVoucherTypeById(id),
                payload -> tallyValidationService.validateVoucherType(payload),
                payload -> tallyMasterXmlBuilder.buildVoucherTypeXml(payload),
                "Voucher type synced to Tally successfully");
    }

    public TallySyncResult syncBudget(Long id, boolean forceSync) {
        return syncEntity("BUDGET", id, "BUDGET", forceSync,
                erpEntityAdapterService.findBudgetById(id),
                payload -> tallyValidationService.validateBudget(payload),
                payload -> tallyMasterXmlBuilder.buildBudgetXml(payload),
                "Budget synced to Tally successfully");
    }

    public TallySyncResult syncEmployee(Long id, boolean forceSync) {
        return syncEntity("EMPLOYEE", id, "COSTCENTRE", forceSync,
                erpEntityAdapterService.findEmployeeById(id),
                payload -> tallyValidationService.validateEmployee(payload),
                payload -> tallyMasterXmlBuilder.buildEmployeeXml(payload),
                "Employee synced to Tally successfully");
    }

    public TallySyncResult syncEmployeeGroup(Long id, boolean forceSync) {
        return syncEntity("EMPLOYEE_GROUP", id, "COSTCENTRE", forceSync,
                erpEntityAdapterService.findEmployeeGroupById(id),
                payload -> tallyValidationService.validateEmployeeGroup(payload),
                payload -> tallyMasterXmlBuilder.buildEmployeeGroupXml(payload),
                "Employee group synced to Tally successfully");
    }

    public TallySyncResult syncPayHead(Long id, boolean forceSync) {
        return syncEntity("PAY_HEAD", id, "LEDGER", forceSync,
                erpEntityAdapterService.findPayHeadById(id),
                payload -> tallyValidationService.validatePayHead(payload),
                payload -> tallyMasterXmlBuilder.buildPayHeadXml(payload),
                "Pay head synced to Tally successfully");
    }

    public TallySyncResult syncAttendanceType(Long id, boolean forceSync) {
        return syncEntity("ATTENDANCE_TYPE", id, "ATTENDANCETYPE", forceSync,
                erpEntityAdapterService.findAttendanceTypeById(id),
                payload -> tallyValidationService.validateAttendanceType(payload),
                payload -> tallyMasterXmlBuilder.buildAttendanceTypeXml(payload),
                "Attendance type synced to Tally successfully");
    }

    public TallySyncResult syncSalesOrder(Long id, boolean forceSync) {
        return syncSimpleVoucher("SALES_ORDER", id, "Sales Order", forceSync,
                erpEntityAdapterService.findSalesOrderById(id));
    }

    public TallySyncResult syncPurchaseOrder(Long id, boolean forceSync) {
        return syncSimpleVoucher("PURCHASE_ORDER", id, "Purchase Order", forceSync,
                erpEntityAdapterService.findPurchaseOrderById(id));
    }

    public TallySyncResult syncDeliveryNote(Long id, boolean forceSync) {
        return syncSimpleVoucher("DELIVERY_NOTE", id, "Delivery Note", forceSync,
                erpEntityAdapterService.findDeliveryNoteById(id));
    }

    public TallySyncResult syncGoodsReceipt(Long id, boolean forceSync) {
        return syncSimpleVoucher("GOODS_RECEIPT", id, "Receipt Note", forceSync,
                erpEntityAdapterService.findGoodsReceiptById(id));
    }

    public TallySyncResult syncStockJournal(Long id, boolean forceSync) {
        return syncSimpleVoucher("STOCK_JOURNAL", id, "Stock Journal", forceSync,
                erpEntityAdapterService.findStockJournalById(id));
    }

    public TallySyncResult syncMaterialIn(Long id, boolean forceSync) {
        return syncSimpleVoucher("MATERIAL_IN", id, "Material In", forceSync,
                erpEntityAdapterService.findMaterialInById(id));
    }

    public TallySyncResult syncMaterialOut(Long id, boolean forceSync) {
        return syncSimpleVoucher("MATERIAL_OUT", id, "Material Out", forceSync,
                erpEntityAdapterService.findMaterialOutById(id));
    }

    public TallySyncResult syncContra(Long id, boolean forceSync) {
        return syncSimpleVoucher("CONTRA", id, "Contra", forceSync,
                erpEntityAdapterService.findContraById(id));
    }

    public TallySyncResult syncJournal(Long id, boolean forceSync) {
        return syncSimpleVoucher("JOURNAL", id, "Journal", forceSync,
                erpEntityAdapterService.findJournalById(id));
    }

    public TallySyncResult syncDebitNote(Long id, boolean forceSync) {
        return syncSimpleVoucher("DEBIT_NOTE", id, "Debit Note", forceSync,
                erpEntityAdapterService.findDebitNoteById(id));
    }

    public TallySyncResult syncCreditNote(Long id, boolean forceSync) {
        return syncSimpleVoucher("CREDIT_NOTE", id, "Credit Note", forceSync,
                erpEntityAdapterService.findCreditNoteById(id));
    }

    public TallySyncResult syncRejectionIn(Long id, boolean forceSync) {
        return syncSimpleVoucher("REJECTION_IN", id, "Rejections In", forceSync,
                erpEntityAdapterService.findRejectionInById(id));
    }

    public TallySyncResult syncRejectionOut(Long id, boolean forceSync) {
        return syncSimpleVoucher("REJECTION_OUT", id, "Rejections Out", forceSync,
                erpEntityAdapterService.findRejectionOutById(id));
    }

    public TallySyncResult syncPayrollVoucher(Long id, boolean forceSync) {
        return syncSimpleVoucher("PAYROLL", id, "Payroll", forceSync,
                erpEntityAdapterService.findPayrollVoucherById(id));
    }

    public TallySyncResult syncPhysicalStock(Long id, boolean forceSync) {
        return syncSimpleVoucher("PHYSICAL_STOCK", id, "Physical Stock", forceSync,
                erpEntityAdapterService.findPhysicalStockById(id));
    }

    public TallySyncResult syncAttendanceVoucher(Long id, boolean forceSync) {
        return syncSimpleVoucher("ATTENDANCE_VOUCHER", id, "Attendance", forceSync,
                erpEntityAdapterService.findAttendanceVoucherById(id));
    }

    public TallySyncResult syncJobWorkInOrder(Long id, boolean forceSync) {
        return syncSimpleVoucher("JOB_WORK_IN_ORDER", id, "Job Work In Order", forceSync,
                erpEntityAdapterService.findJobWorkInOrderById(id));
    }

    public TallySyncResult syncJobWorkOutOrder(Long id, boolean forceSync) {
        return syncSimpleVoucher("JOB_WORK_OUT_ORDER", id, "Job Work Out Order", forceSync,
                erpEntityAdapterService.findJobWorkOutOrderById(id));
    }

    public TallySyncResult syncMemorandum(Long id, boolean forceSync) {
        return syncSimpleVoucher("MEMORANDUM", id, "Memorandum", forceSync,
                erpEntityAdapterService.findMemorandumById(id));
    }

    public TallySyncResult syncReversingJournal(Long id, boolean forceSync) {
        return syncSimpleVoucher("REVERSING_JOURNAL", id, "Reversing Journal", forceSync,
                erpEntityAdapterService.findReversingJournalById(id));
    }

    public XmlPreviewResponse previewCustomer(Long customerId) {
        return preview("CUSTOMER", customerId, erpEntityAdapterService.findCustomerById(customerId), new XmlBuilder() {
            @Override
            public String build(Map<String, Object> payload) {
                return tallyMasterXmlBuilder.buildCustomerLedgerXml(payload);
            }
        });
    }

    public XmlPreviewResponse previewSupplier(Long supplierId) {
        return preview("SUPPLIER", supplierId, erpEntityAdapterService.findSupplierById(supplierId), new XmlBuilder() {
            @Override
            public String build(Map<String, Object> payload) {
                return tallyMasterXmlBuilder.buildSupplierLedgerXml(payload);
            }
        });
    }

    public XmlPreviewResponse previewProduct(Long productId) {
        return preview("PRODUCT", productId, erpEntityAdapterService.findProductById(productId), new XmlBuilder() {
            @Override
            public String build(Map<String, Object> payload) {
                return tallyMasterXmlBuilder.buildProductStockItemXml(payload);
            }
        });
    }

    public XmlPreviewResponse previewSalesInvoice(Long invoiceId) {
        return preview("SALES_INVOICE", invoiceId, erpEntityAdapterService.findSalesInvoiceById(invoiceId), new XmlBuilder() {
            @Override
            public String build(Map<String, Object> payload) {
                return tallyVoucherXmlBuilder.buildSalesVoucherXml(payload);
            }
        });
    }

    public XmlPreviewResponse previewPurchaseInvoice(Long invoiceId) {
        return preview("PURCHASE_INVOICE", invoiceId, erpEntityAdapterService.findPurchaseInvoiceById(invoiceId), new XmlBuilder() {
            @Override
            public String build(Map<String, Object> payload) {
                return tallyVoucherXmlBuilder.buildPurchaseVoucherXml(payload);
            }
        });
    }

    public XmlPreviewResponse previewPayment(Long paymentId) {
        return preview("PAYMENT", paymentId, erpEntityAdapterService.findPaymentById(paymentId), new XmlBuilder() {
            @Override
            public String build(Map<String, Object> payload) {
                return tallyVoucherXmlBuilder.buildPaymentVoucherXml(payload);
            }
        });
    }

    public XmlPreviewResponse previewRefund(Long paymentId) {
        return preview("REFUND", paymentId, erpEntityAdapterService.findRefundById(paymentId), new XmlBuilder() {
            @Override
            public String build(Map<String, Object> payload) {
                return tallyVoucherXmlBuilder.buildRefundVoucherXml(payload);
            }
        });
    }

    public TallyValidationResult validateCustomer(Long customerId) {
        return requirePresent(erpEntityAdapterService.findCustomerById(customerId), "CUSTOMER", customerId, tallyValidationService.validateCustomer(requirePresent(erpEntityAdapterService.findCustomerById(customerId), "CUSTOMER", customerId)));
    }

    public TallyValidationResult validateSupplier(Long supplierId) {
        return requirePresent(erpEntityAdapterService.findSupplierById(supplierId), "SUPPLIER", supplierId, tallyValidationService.validateSupplier(requirePresent(erpEntityAdapterService.findSupplierById(supplierId), "SUPPLIER", supplierId)));
    }

    public TallyValidationResult validateProduct(Long productId) {
        return requirePresent(erpEntityAdapterService.findProductById(productId), "PRODUCT", productId, tallyValidationService.validateProduct(requirePresent(erpEntityAdapterService.findProductById(productId), "PRODUCT", productId)));
    }

    public XmlPreviewResponse previewStockGroup(Long id) {
        return preview("STOCK_GROUP", id, erpEntityAdapterService.findStockGroupById(id), payload -> tallyMasterXmlBuilder.buildStockGroupXml(payload));
    }

    public XmlPreviewResponse previewStockCategory(Long id) {
        return preview("STOCK_CATEGORY", id, erpEntityAdapterService.findStockCategoryById(id), payload -> tallyMasterXmlBuilder.buildStockCategoryXml(payload));
    }

    public XmlPreviewResponse previewGodown(Long id) {
        return preview("GODOWN", id, erpEntityAdapterService.findGodownById(id), payload -> tallyMasterXmlBuilder.buildGodownXml(payload));
    }

    public XmlPreviewResponse previewCostCategory(Long id) {
        return preview("COST_CATEGORY", id, erpEntityAdapterService.findCostCategoryById(id), payload -> tallyMasterXmlBuilder.buildCostCategoryXml(payload));
    }

    public XmlPreviewResponse previewCostCentre(Long id) {
        return preview("COST_CENTRE", id, erpEntityAdapterService.findCostCentreById(id), payload -> tallyMasterXmlBuilder.buildCostCentreXml(payload));
    }

    public XmlPreviewResponse previewBom(Long id) {
        return preview("BOM", id, erpEntityAdapterService.findBomById(id), payload -> tallyMasterXmlBuilder.buildBomXml(payload));
    }

    public XmlPreviewResponse previewPriceLevel(Long id) {
        return preview("PRICE_LEVEL", id, erpEntityAdapterService.findPriceLevelById(id), payload -> tallyMasterXmlBuilder.buildPriceLevelXml(payload));
    }

    public XmlPreviewResponse previewVoucherType(Long id) {
        return preview("VOUCHER_TYPE", id, erpEntityAdapterService.findVoucherTypeById(id), payload -> tallyMasterXmlBuilder.buildVoucherTypeXml(payload));
    }

    public XmlPreviewResponse previewBudget(Long id) {
        return preview("BUDGET", id, erpEntityAdapterService.findBudgetById(id), payload -> tallyMasterXmlBuilder.buildBudgetXml(payload));
    }

    public XmlPreviewResponse previewEmployee(Long id) {
        return preview("EMPLOYEE", id, erpEntityAdapterService.findEmployeeById(id), payload -> tallyMasterXmlBuilder.buildEmployeeXml(payload));
    }

    public XmlPreviewResponse previewEmployeeGroup(Long id) {
        return preview("EMPLOYEE_GROUP", id, erpEntityAdapterService.findEmployeeGroupById(id), payload -> tallyMasterXmlBuilder.buildEmployeeGroupXml(payload));
    }

    public XmlPreviewResponse previewPayHead(Long id) {
        return preview("PAY_HEAD", id, erpEntityAdapterService.findPayHeadById(id), payload -> tallyMasterXmlBuilder.buildPayHeadXml(payload));
    }

    public XmlPreviewResponse previewAttendanceType(Long id) {
        return preview("ATTENDANCE_TYPE", id, erpEntityAdapterService.findAttendanceTypeById(id), payload -> tallyMasterXmlBuilder.buildAttendanceTypeXml(payload));
    }

    public XmlPreviewResponse previewSalesOrder(Long id) {
        return preview("SALES_ORDER", id, erpEntityAdapterService.findSalesOrderById(id), payload -> tallyVoucherXmlBuilder.buildSalesOrderXml(payload));
    }

    public XmlPreviewResponse previewPurchaseOrder(Long id) {
        return preview("PURCHASE_ORDER", id, erpEntityAdapterService.findPurchaseOrderById(id), payload -> tallyVoucherXmlBuilder.buildPurchaseOrderXml(payload));
    }

    public XmlPreviewResponse previewDeliveryNote(Long id) {
        return preview("DELIVERY_NOTE", id, erpEntityAdapterService.findDeliveryNoteById(id), payload -> tallyVoucherXmlBuilder.buildDeliveryNoteXml(payload));
    }

    public XmlPreviewResponse previewGoodsReceipt(Long id) {
        return preview("GOODS_RECEIPT", id, erpEntityAdapterService.findGoodsReceiptById(id), payload -> tallyVoucherXmlBuilder.buildGoodsReceiptXml(payload));
    }

    public XmlPreviewResponse previewStockJournal(Long id) {
        return preview("STOCK_JOURNAL", id, erpEntityAdapterService.findStockJournalById(id), payload -> tallyVoucherXmlBuilder.buildStockJournalXml(payload));
    }

    public XmlPreviewResponse previewMaterialIn(Long id) {
        return preview("MATERIAL_IN", id, erpEntityAdapterService.findMaterialInById(id), payload -> tallyVoucherXmlBuilder.buildMaterialInXml(payload));
    }

    public XmlPreviewResponse previewMaterialOut(Long id) {
        return preview("MATERIAL_OUT", id, erpEntityAdapterService.findMaterialOutById(id), payload -> tallyVoucherXmlBuilder.buildMaterialOutXml(payload));
    }

    public XmlPreviewResponse previewContra(Long id) {
        return preview("CONTRA", id, erpEntityAdapterService.findContraById(id), payload -> tallyVoucherXmlBuilder.buildContraXml(payload));
    }

    public XmlPreviewResponse previewJournal(Long id) {
        return preview("JOURNAL", id, erpEntityAdapterService.findJournalById(id), payload -> tallyVoucherXmlBuilder.buildJournalXml(payload));
    }

    public XmlPreviewResponse previewDebitNote(Long id) {
        return preview("DEBIT_NOTE", id, erpEntityAdapterService.findDebitNoteById(id), payload -> tallyVoucherXmlBuilder.buildDebitNoteXml(payload));
    }

    public XmlPreviewResponse previewCreditNote(Long id) {
        return preview("CREDIT_NOTE", id, erpEntityAdapterService.findCreditNoteById(id), payload -> tallyVoucherXmlBuilder.buildCreditNoteXml(payload));
    }

    public XmlPreviewResponse previewRejectionIn(Long id) {
        return preview("REJECTION_IN", id, erpEntityAdapterService.findRejectionInById(id), payload -> tallyVoucherXmlBuilder.buildRejectionInXml(payload));
    }

    public XmlPreviewResponse previewRejectionOut(Long id) {
        return preview("REJECTION_OUT", id, erpEntityAdapterService.findRejectionOutById(id), payload -> tallyVoucherXmlBuilder.buildRejectionOutXml(payload));
    }

    public XmlPreviewResponse previewPayrollVoucher(Long id) {
        return preview("PAYROLL", id, erpEntityAdapterService.findPayrollVoucherById(id), payload -> tallyVoucherXmlBuilder.buildPayrollVoucherXml(payload));
    }

    public XmlPreviewResponse previewPhysicalStock(Long id) {
        return preview("PHYSICAL_STOCK", id, erpEntityAdapterService.findPhysicalStockById(id), payload -> tallyVoucherXmlBuilder.buildPhysicalStockXml(payload));
    }

    public XmlPreviewResponse previewAttendanceVoucher(Long id) {
        return preview("ATTENDANCE_VOUCHER", id, erpEntityAdapterService.findAttendanceVoucherById(id), payload -> tallyVoucherXmlBuilder.buildAttendanceVoucherXml(payload));
    }

    public XmlPreviewResponse previewJobWorkInOrder(Long id) {
        return preview("JOB_WORK_IN_ORDER", id, erpEntityAdapterService.findJobWorkInOrderById(id), payload -> tallyVoucherXmlBuilder.buildJobWorkInOrderXml(payload));
    }

    public XmlPreviewResponse previewJobWorkOutOrder(Long id) {
        return preview("JOB_WORK_OUT_ORDER", id, erpEntityAdapterService.findJobWorkOutOrderById(id), payload -> tallyVoucherXmlBuilder.buildJobWorkOutOrderXml(payload));
    }

    public XmlPreviewResponse previewMemorandum(Long id) {
        return preview("MEMORANDUM", id, erpEntityAdapterService.findMemorandumById(id), payload -> tallyVoucherXmlBuilder.buildMemorandumXml(payload));
    }

    public XmlPreviewResponse previewReversingJournal(Long id) {
        return preview("REVERSING_JOURNAL", id, erpEntityAdapterService.findReversingJournalById(id), payload -> tallyVoucherXmlBuilder.buildReversingJournalXml(payload));
    }

    public TallyValidationResult validateStockGroup(Long id) {
        return requirePresent(erpEntityAdapterService.findStockGroupById(id), "STOCK_GROUP", id, tallyValidationService.validateStockGroup(requirePresent(erpEntityAdapterService.findStockGroupById(id), "STOCK_GROUP", id)));
    }

    public TallyValidationResult validateStockCategory(Long id) {
        return requirePresent(erpEntityAdapterService.findStockCategoryById(id), "STOCK_CATEGORY", id, tallyValidationService.validateStockCategory(requirePresent(erpEntityAdapterService.findStockCategoryById(id), "STOCK_CATEGORY", id)));
    }

    public TallyValidationResult validateGodown(Long id) {
        return requirePresent(erpEntityAdapterService.findGodownById(id), "GODOWN", id, tallyValidationService.validateGodown(requirePresent(erpEntityAdapterService.findGodownById(id), "GODOWN", id)));
    }

    public TallyValidationResult validateCostCategory(Long id) {
        return requirePresent(erpEntityAdapterService.findCostCategoryById(id), "COST_CATEGORY", id, tallyValidationService.validateCostCategory(requirePresent(erpEntityAdapterService.findCostCategoryById(id), "COST_CATEGORY", id)));
    }

    public TallyValidationResult validateCostCentre(Long id) {
        return requirePresent(erpEntityAdapterService.findCostCentreById(id), "COST_CENTRE", id, tallyValidationService.validateCostCentre(requirePresent(erpEntityAdapterService.findCostCentreById(id), "COST_CENTRE", id)));
    }

    public TallyValidationResult validateBom(Long id) {
        return requirePresent(erpEntityAdapterService.findBomById(id), "BOM", id, tallyValidationService.validateBom(requirePresent(erpEntityAdapterService.findBomById(id), "BOM", id)));
    }

    public TallyValidationResult validatePriceLevel(Long id) {
        return requirePresent(erpEntityAdapterService.findPriceLevelById(id), "PRICE_LEVEL", id, tallyValidationService.validatePriceLevel(requirePresent(erpEntityAdapterService.findPriceLevelById(id), "PRICE_LEVEL", id)));
    }

    public TallyValidationResult validateVoucherType(Long id) {
        return requirePresent(erpEntityAdapterService.findVoucherTypeById(id), "VOUCHER_TYPE", id, tallyValidationService.validateVoucherType(requirePresent(erpEntityAdapterService.findVoucherTypeById(id), "VOUCHER_TYPE", id)));
    }

    public TallyValidationResult validateBudget(Long id) {
        return requirePresent(erpEntityAdapterService.findBudgetById(id), "BUDGET", id, tallyValidationService.validateBudget(requirePresent(erpEntityAdapterService.findBudgetById(id), "BUDGET", id)));
    }

    public TallyValidationResult validateEmployee(Long id) {
        return requirePresent(erpEntityAdapterService.findEmployeeById(id), "EMPLOYEE", id, tallyValidationService.validateEmployee(requirePresent(erpEntityAdapterService.findEmployeeById(id), "EMPLOYEE", id)));
    }

    public TallyValidationResult validateEmployeeGroup(Long id) {
        return requirePresent(erpEntityAdapterService.findEmployeeGroupById(id), "EMPLOYEE_GROUP", id, tallyValidationService.validateEmployeeGroup(requirePresent(erpEntityAdapterService.findEmployeeGroupById(id), "EMPLOYEE_GROUP", id)));
    }

    public TallyValidationResult validatePayHead(Long id) {
        return requirePresent(erpEntityAdapterService.findPayHeadById(id), "PAY_HEAD", id, tallyValidationService.validatePayHead(requirePresent(erpEntityAdapterService.findPayHeadById(id), "PAY_HEAD", id)));
    }

    public TallyValidationResult validateAttendanceType(Long id) {
        return requirePresent(erpEntityAdapterService.findAttendanceTypeById(id), "ATTENDANCE_TYPE", id, tallyValidationService.validateAttendanceType(requirePresent(erpEntityAdapterService.findAttendanceTypeById(id), "ATTENDANCE_TYPE", id)));
    }

    public TallyValidationResult validateSalesInvoice(Long invoiceId) {
        return requirePresent(erpEntityAdapterService.findSalesInvoiceById(invoiceId), "SALES_INVOICE", invoiceId, tallyValidationService.validateSalesInvoice(requirePresent(erpEntityAdapterService.findSalesInvoiceById(invoiceId), "SALES_INVOICE", invoiceId)));
    }

    public TallyValidationResult validatePurchaseInvoice(Long invoiceId) {
        return requirePresent(erpEntityAdapterService.findPurchaseInvoiceById(invoiceId), "PURCHASE_INVOICE", invoiceId, tallyValidationService.validatePurchaseInvoice(requirePresent(erpEntityAdapterService.findPurchaseInvoiceById(invoiceId), "PURCHASE_INVOICE", invoiceId)));
    }

    public TallyValidationResult validatePayment(Long paymentId) {
        return requirePresent(erpEntityAdapterService.findPaymentById(paymentId), "PAYMENT", paymentId, tallyValidationService.validatePayment(requirePresent(erpEntityAdapterService.findPaymentById(paymentId), "PAYMENT", paymentId)));
    }

    public TallySyncResult retrySyncLog(Long syncLogId) {
        TallySyncLog log = tallySyncLogService.findById(syncLogId);
        if (log.getRequestXml() == null || log.getRequestXml().trim().isEmpty()) {
            throw new SyncApiException(HttpStatus.BAD_REQUEST, "Sync log does not contain requestXml");
        }
        tallySyncLogService.markRetrying(log);
        try {
            String responseXml = tallyXmlTransportService.importXml(log.getRequestXml());
            TallyParsedResponse parsed = tallyResponseParser.parse(responseXml);
            if (parsed.isSuccess()) {
                tallySyncLogService.markSuccess(log, parsed);
                return buildResult(true, TallySyncStatus.SUCCESS.name(), "Sync retried successfully", log.getEntityType(), log.getEntityId(), log.getId(), parsed.getTallyGuid(), Collections.<String>emptyList());
            }
            tallySyncLogService.markFailed(log, defaultFailureMessage(parsed), parsed.getRawResponse());
            return buildResult(false, TallySyncStatus.FAILED.name(), "Tally sync failed", log.getEntityType(), log.getEntityId(), log.getId(), parsed.getTallyGuid(), errorsFromParsed(parsed));
        } catch (ResourceAccessException ex) {
            tallySyncLogService.markFailed(log, "Tally sync failed: " + ex.getMessage(), null);
            return buildResult(false, TallySyncStatus.FAILED.name(), "Tally sync failed", log.getEntityType(), log.getEntityId(), log.getId(), null, Collections.singletonList(ex.getMessage()));
        }
    }

    public List<TallySyncResult> retryFailedSyncs() {
        List<TallySyncResult> results = new ArrayList<TallySyncResult>();
        for (TallySyncLog log : tallySyncLogService.findFailed()) {
            results.add(retrySyncLog(log.getId()));
        }
        return results;
    }

    private TallySyncResult syncSimpleVoucher(String entityType, Long entityId, String tallyVoucherType, boolean forceSync, Optional<Map<String, Object>> maybePayload) {
        return syncEntity(entityType, entityId, tallyVoucherType, forceSync, maybePayload,
                payload -> tallyValidationService.validateSimpleVoucher(payload, tallyVoucherType),
                payload -> buildVoucherXml(tallyVoucherType, payload),
                tallyVoucherType + " synced to Tally successfully");
    }

    private String buildVoucherXml(String voucherType, Map<String, Object> payload) {
        switch (voucherType) {
            case "Sales Order": return tallyVoucherXmlBuilder.buildSalesOrderXml(payload);
            case "Purchase Order": return tallyVoucherXmlBuilder.buildPurchaseOrderXml(payload);
            case "Delivery Note": return tallyVoucherXmlBuilder.buildDeliveryNoteXml(payload);
            case "Receipt Note": return tallyVoucherXmlBuilder.buildGoodsReceiptXml(payload);
            case "Stock Journal": return tallyVoucherXmlBuilder.buildStockJournalXml(payload);
            case "Material In": return tallyVoucherXmlBuilder.buildMaterialInXml(payload);
            case "Material Out": return tallyVoucherXmlBuilder.buildMaterialOutXml(payload);
            case "Contra": return tallyVoucherXmlBuilder.buildContraXml(payload);
            case "Journal": return tallyVoucherXmlBuilder.buildJournalXml(payload);
            case "Debit Note": return tallyVoucherXmlBuilder.buildDebitNoteXml(payload);
            case "Credit Note": return tallyVoucherXmlBuilder.buildCreditNoteXml(payload);
            case "Rejections In": return tallyVoucherXmlBuilder.buildRejectionInXml(payload);
            case "Rejections Out": return tallyVoucherXmlBuilder.buildRejectionOutXml(payload);
            case "Payroll": return tallyVoucherXmlBuilder.buildPayrollVoucherXml(payload);
            case "Physical Stock": return tallyVoucherXmlBuilder.buildPhysicalStockXml(payload);
            case "Attendance": return tallyVoucherXmlBuilder.buildAttendanceVoucherXml(payload);
            case "Job Work In Order": return tallyVoucherXmlBuilder.buildJobWorkInOrderXml(payload);
            case "Job Work Out Order": return tallyVoucherXmlBuilder.buildJobWorkOutOrderXml(payload);
            case "Memorandum": return tallyVoucherXmlBuilder.buildMemorandumXml(payload);
            case "Reversing Journal": return tallyVoucherXmlBuilder.buildReversingJournalXml(payload);
            default: throw new SyncApiException(HttpStatus.BAD_REQUEST, "Unknown voucher type: " + voucherType);
        }
    }

    private TallySyncResult syncEntity(String entityType,
                                       Long entityId,
                                       String tallyType,
                                       boolean forceSync,
                                       Optional<Map<String, Object>> maybePayload,
                                       Validator validator,
                                       XmlBuilder builder,
                                       String successMessage) {
        if (!forceSync && tallySyncLogService.hasSuccessfulSync(entityType, entityId)) {
            TallySyncLog duplicateLog = tallySyncLogService.createDuplicate(entityType, entityId, tallyType, "Entity already synced successfully");
            return buildResult(false, TallySyncStatus.DUPLICATE.name(), "Entity already synced to Tally", entityType, entityId, duplicateLog.getId(), duplicateLog.getTallyGuid(), Collections.singletonList("Entity already synced successfully"));
        }
        Map<String, Object> payload = requirePresent(maybePayload, entityType, entityId);
        TallyValidationResult validation = validator.validate(payload);
        if (!validation.isValid()) {
            TallySyncLog failedLog = tallySyncLogService.createFailed(entityType, entityId, tallyType, null, joinErrors(validation.getErrors()));
            return buildResult(false, TallySyncStatus.FAILED.name(), "Validation failed", entityType, entityId, failedLog.getId(), null, validation.getErrors());
        }
        String requestXml = builder.build(payload);
        TallySyncLog log = tallySyncLogService.createPending(entityType, entityId, tallyType, requestXml);
        try {
            String responseXml = tallyXmlTransportService.importXml(requestXml);
            TallyParsedResponse parsed = tallyResponseParser.parse(responseXml);
            if (parsed.isSuccess()) {
                tallySyncLogService.markSuccess(log, parsed);
                return buildResult(true, TallySyncStatus.SUCCESS.name(), successMessage, entityType, entityId, log.getId(), parsed.getTallyGuid(), Collections.<String>emptyList());
            }
            tallySyncLogService.markFailed(log, defaultFailureMessage(parsed), parsed.getRawResponse());
            return buildResult(false, TallySyncStatus.FAILED.name(), "Tally sync failed", entityType, entityId, log.getId(), parsed.getTallyGuid(), errorsFromParsed(parsed));
        } catch (ResourceAccessException ex) {
            tallySyncLogService.markFailed(log, "Tally sync failed: " + ex.getMessage(), null);
            return buildResult(false, TallySyncStatus.FAILED.name(), "Tally sync failed", entityType, entityId, log.getId(), null, Collections.singletonList(ex.getMessage()));
        }
    }

    private XmlPreviewResponse preview(String entityType, Long entityId, Optional<Map<String, Object>> maybePayload, XmlBuilder builder) {
        Map<String, Object> payload = requirePresent(maybePayload, entityType, entityId);
        XmlPreviewResponse response = new XmlPreviewResponse();
        response.setEntityType(entityType);
        response.setEntityId(entityId);
        response.setXml(builder.build(payload));
        return response;
    }

    private Map<String, Object> requirePresent(Optional<Map<String, Object>> maybePayload, String entityType, Long entityId) {
        if (!maybePayload.isPresent()) {
            throw new SyncApiException(HttpStatus.NOT_FOUND, entityType + " not found for id " + entityId);
        }
        return maybePayload.get();
    }

    private TallyValidationResult requirePresent(Optional<Map<String, Object>> maybePayload, String entityType, Long entityId, TallyValidationResult validationResult) {
        requirePresent(maybePayload, entityType, entityId);
        return validationResult;
    }

    private TallySyncResult buildResult(boolean success,
                                        String status,
                                        String message,
                                        String entityType,
                                        Long entityId,
                                        Long syncLogId,
                                        String tallyGuid,
                                        List<String> errors) {
        TallySyncResult result = new TallySyncResult();
        result.setSuccess(success);
        result.setStatus(status);
        result.setMessage(message);
        result.setEntityType(entityType);
        result.setEntityId(entityId);
        result.setSyncLogId(syncLogId);
        result.setTallyGuid(tallyGuid);
        result.setErrors(errors);
        return result;
    }

    private List<String> errorsFromParsed(TallyParsedResponse parsed) {
        List<String> errors = new ArrayList<String>();
        if (parsed.getLineError() != null && !parsed.getLineError().trim().isEmpty()) {
            errors.add(parsed.getLineError());
        }
        if (parsed.getErrors() > 0 && errors.isEmpty()) {
            errors.add("Tally reported " + parsed.getErrors() + " error(s)");
        }
        return errors;
    }

    private String defaultFailureMessage(TallyParsedResponse parsed) {
        List<String> errors = errorsFromParsed(parsed);
        return errors.isEmpty() ? "Tally sync failed" : joinErrors(errors);
    }

    private String joinErrors(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return "Tally sync failed";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < errors.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(errors.get(i));
        }
        return sb.toString();
    }

    private interface Validator {
        TallyValidationResult validate(Map<String, Object> payload);
    }

    private interface XmlBuilder {
        String build(Map<String, Object> payload);
    }
}
