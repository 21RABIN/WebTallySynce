package com.tallybackend.web;

import com.tallybackend.cache.TallyPostWriteRefreshService;
import com.tallybackend.service.ConnectorGatewayService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/vouchers")
public class VouchersApiController extends AbstractConnectorController {

    public VouchersApiController(ConnectorGatewayService connectorGatewayService,
                                 TallyPostWriteRefreshService tallyPostWriteRefreshService) {
        super(connectorGatewayService, tallyPostWriteRefreshService);
    }

    @GetMapping
    public ResponseEntity<String> vouchers(HttpServletRequest request) { return get("/vouchers", request); }
    @PostMapping
    public ResponseEntity<String> vouchersUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers", request, body); }

    @PostMapping(value = "/import-xml", consumes = {
            MediaType.APPLICATION_XML_VALUE,
            MediaType.TEXT_XML_VALUE,
            MediaType.TEXT_PLAIN_VALUE,
            MediaType.APPLICATION_JSON_VALUE
    })
    public ResponseEntity<String> importXml(HttpServletRequest request, @RequestBody(required = false) String body) {
        return postAndRefresh("/vouchers/import-xml", request, body);
    }

    @GetMapping("/sales")
    public ResponseEntity<String> sales(HttpServletRequest request) { return get("/vouchers/sales", request); }
    @PostMapping("/sales")
    public ResponseEntity<String> salesUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/sales", request, body); }

    @GetMapping("/purchase")
    public ResponseEntity<String> purchase(HttpServletRequest request) { return get("/vouchers/purchase", request); }
    @PostMapping("/purchase")
    public ResponseEntity<String> purchaseUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/purchase", request, body); }

    @GetMapping("/sales-orders")
    public ResponseEntity<String> salesOrders(HttpServletRequest request) { return get("/vouchers/sales-orders", request); }
    @PostMapping("/sales-orders")
    public ResponseEntity<String> salesOrdersUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/sales-orders", request, body); }

    @GetMapping("/purchase-orders")
    public ResponseEntity<String> purchaseOrders(HttpServletRequest request) { return get("/vouchers/purchase-orders", request); }
    @PostMapping("/purchase-orders")
    public ResponseEntity<String> purchaseOrdersUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/purchase-orders", request, body); }

    @GetMapping("/delivery-notes")
    public ResponseEntity<String> deliveryNotes(HttpServletRequest request) { return get("/vouchers/delivery-notes", request); }
    @PostMapping("/delivery-notes")
    public ResponseEntity<String> deliveryNotesUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/delivery-notes", request, body); }

    @GetMapping("/goods-receipts")
    public ResponseEntity<String> goodsReceipts(HttpServletRequest request) { return get("/vouchers/goods-receipts", request); }
    @PostMapping("/goods-receipts")
    public ResponseEntity<String> goodsReceiptsUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/goods-receipts", request, body); }

    @GetMapping("/stock-journals")
    public ResponseEntity<String> stockJournals(HttpServletRequest request) { return get("/vouchers/stock-journals", request); }
    @PostMapping("/stock-journals")
    public ResponseEntity<String> stockJournalsUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/stock-journals", request, body); }

    @GetMapping("/material-in")
    public ResponseEntity<String> materialIn(HttpServletRequest request) { return get("/vouchers/material-in", request); }
    @PostMapping("/material-in")
    public ResponseEntity<String> materialInUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/material-in", request, body); }

    @GetMapping("/material-out")
    public ResponseEntity<String> materialOut(HttpServletRequest request) { return get("/vouchers/material-out", request); }
    @PostMapping("/material-out")
    public ResponseEntity<String> materialOutUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/material-out", request, body); }

    @GetMapping("/manufacturing")
    public ResponseEntity<String> manufacturing(HttpServletRequest request) { return get("/vouchers/manufacturing", request); }
    @PostMapping("/manufacturing")
    public ResponseEntity<String> manufacturingUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/manufacturing", request, body); }

    @GetMapping("/receipt")
    public ResponseEntity<String> receipt(HttpServletRequest request) { return get("/vouchers/receipt", request); }
    @PostMapping("/receipt")
    public ResponseEntity<String> receiptUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/receipt", request, body); }

    @GetMapping("/payment")
    public ResponseEntity<String> payment(HttpServletRequest request) { return get("/vouchers/payment", request); }
    @PostMapping("/payment")
    public ResponseEntity<String> paymentUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/payment", request, body); }

    @GetMapping("/contra")
    public ResponseEntity<String> contra(HttpServletRequest request) { return get("/vouchers/contra", request); }
    @PostMapping("/contra")
    public ResponseEntity<String> contraUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/contra", request, body); }

    @GetMapping("/journal")
    public ResponseEntity<String> journal(HttpServletRequest request) { return get("/vouchers/journal", request); }
    @PostMapping("/journal")
    public ResponseEntity<String> journalUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/journal", request, body); }

    @GetMapping("/credit-notes")
    public ResponseEntity<String> creditNotes(HttpServletRequest request) { return get("/vouchers/credit-notes", request); }
    @PostMapping("/credit-notes")
    public ResponseEntity<String> creditNotesUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/credit-notes", request, body); }

    @GetMapping("/debit-notes")
    public ResponseEntity<String> debitNotes(HttpServletRequest request) { return get("/vouchers/debit-notes", request); }
    @PostMapping("/debit-notes")
    public ResponseEntity<String> debitNotesUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/debit-notes", request, body); }

    @GetMapping("/rejections-in")
    public ResponseEntity<String> rejectionsIn(HttpServletRequest request) { return get("/vouchers/rejections-in", request); }
    @PostMapping("/rejections-in")
    public ResponseEntity<String> rejectionsInUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/rejections-in", request, body); }

    @GetMapping("/rejections-out")
    public ResponseEntity<String> rejectionsOut(HttpServletRequest request) { return get("/vouchers/rejections-out", request); }
    @PostMapping("/rejections-out")
    public ResponseEntity<String> rejectionsOutUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/rejections-out", request, body); }

    @GetMapping("/payroll")
    public ResponseEntity<String> payroll(HttpServletRequest request) { return get("/vouchers/payroll", request); }
    @PostMapping("/payroll")
    public ResponseEntity<String> payrollUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/payroll", request, body); }

    @GetMapping("/physical-stock")
    public ResponseEntity<String> physicalStock(HttpServletRequest request) { return get("/vouchers/physical-stock", request); }
    @PostMapping("/physical-stock")
    public ResponseEntity<String> physicalStockUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/physical-stock", request, body); }

    @GetMapping("/attendance")
    public ResponseEntity<String> attendance(HttpServletRequest request) { return get("/vouchers/attendance", request); }
    @PostMapping("/attendance")
    public ResponseEntity<String> attendanceUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/attendance", request, body); }

    @GetMapping("/job-work-in-orders")
    public ResponseEntity<String> jobWorkInOrders(HttpServletRequest request) { return get("/vouchers/job-work-in-orders", request); }
    @PostMapping("/job-work-in-orders")
    public ResponseEntity<String> jobWorkInOrdersUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/job-work-in-orders", request, body); }

    @GetMapping("/job-work-out-orders")
    public ResponseEntity<String> jobWorkOutOrders(HttpServletRequest request) { return get("/vouchers/job-work-out-orders", request); }
    @PostMapping("/job-work-out-orders")
    public ResponseEntity<String> jobWorkOutOrdersUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/job-work-out-orders", request, body); }

    @GetMapping("/memorandum")
    public ResponseEntity<String> memorandum(HttpServletRequest request) { return get("/vouchers/memorandum", request); }
    @PostMapping("/memorandum")
    public ResponseEntity<String> memorandumUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/memorandum", request, body); }

    @GetMapping("/reversing-journal")
    public ResponseEntity<String> reversingJournal(HttpServletRequest request) { return get("/vouchers/reversing-journal", request); }
    @PostMapping("/reversing-journal")
    public ResponseEntity<String> reversingJournalUpsert(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/vouchers/reversing-journal", request, body); }

    @PostMapping("/einvoice/generate")
    public ResponseEntity<String> einvoiceGenerate(HttpServletRequest request, @RequestBody(required = false) String body) { return post("/vouchers/einvoice/generate", request, body); }
    @PostMapping("/sales/einvoice/generate")
    public ResponseEntity<String> salesEinvoiceGenerate(HttpServletRequest request, @RequestBody(required = false) String body) { return post("/vouchers/sales/einvoice/generate", request, body); }
    @PostMapping("/ewaybill/generate")
    public ResponseEntity<String> ewaybillGenerate(HttpServletRequest request, @RequestBody(required = false) String body) { return post("/vouchers/ewaybill/generate", request, body); }
    @PostMapping("/sales/ewaybill/generate")
    public ResponseEntity<String> salesEwaybillGenerate(HttpServletRequest request, @RequestBody(required = false) String body) { return post("/vouchers/sales/ewaybill/generate", request, body); }
    @PostMapping("/einvoice-ewaybill/generate")
    public ResponseEntity<String> einvoiceEwaybillGenerate(HttpServletRequest request, @RequestBody(required = false) String body) { return post("/vouchers/einvoice-ewaybill/generate", request, body); }
    @PostMapping("/sales/einvoice-ewaybill/generate")
    public ResponseEntity<String> salesEinvoiceEwaybillGenerate(HttpServletRequest request, @RequestBody(required = false) String body) { return post("/vouchers/sales/einvoice-ewaybill/generate", request, body); }

    @PostMapping("/einvoice-ready")
    public ResponseEntity<String> einvoiceReady(HttpServletRequest request, @RequestBody(required = false) String body) { return post("/vouchers/einvoice-ready", request, body); }
    @PostMapping("/sales/einvoice-ready")
    public ResponseEntity<String> salesEinvoiceReady(HttpServletRequest request, @RequestBody(required = false) String body) { return post("/vouchers/sales/einvoice-ready", request, body); }
    @GetMapping("/sales/einvoice-status")
    public ResponseEntity<String> salesEinvoiceStatus(HttpServletRequest request) { return get("/vouchers/sales/einvoice-status", request); }

    @PostMapping("/ewaybill-ready")
    public ResponseEntity<String> ewaybillReady(HttpServletRequest request, @RequestBody(required = false) String body) { return post("/vouchers/ewaybill-ready", request, body); }
    @PostMapping("/sales/ewaybill-ready")
    public ResponseEntity<String> salesEwaybillReady(HttpServletRequest request, @RequestBody(required = false) String body) { return post("/vouchers/sales/ewaybill-ready", request, body); }
    @GetMapping("/sales/ewaybill-status")
    public ResponseEntity<String> salesEwaybillStatus(HttpServletRequest request) { return get("/vouchers/sales/ewaybill-status", request); }

    @PostMapping("/einvoice-ewaybill-ready")
    public ResponseEntity<String> einvoiceEwaybillReady(HttpServletRequest request, @RequestBody(required = false) String body) { return post("/vouchers/einvoice-ewaybill-ready", request, body); }
    @PostMapping("/sales/einvoice-ewaybill-ready")
    public ResponseEntity<String> salesEinvoiceEwaybillReady(HttpServletRequest request, @RequestBody(required = false) String body) { return post("/vouchers/sales/einvoice-ewaybill-ready", request, body); }
}
