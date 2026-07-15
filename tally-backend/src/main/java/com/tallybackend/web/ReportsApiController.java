package com.tallybackend.web;

import com.tallybackend.cache.TallyPostWriteRefreshService;
import com.tallybackend.service.ConnectorGatewayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/reports")
public class ReportsApiController extends AbstractConnectorController {

    public ReportsApiController(ConnectorGatewayService connectorGatewayService,
                                TallyPostWriteRefreshService tallyPostWriteRefreshService) {
        super(connectorGatewayService, tallyPostWriteRefreshService);
    }

    @GetMapping("/day-book")
    public ResponseEntity<String> dayBook(HttpServletRequest request) { return get("/reports/day-book", request); }

    @GetMapping("/stock-items")
    public ResponseEntity<String> stockItems(HttpServletRequest request) { return get("/reports/stock-items", request); }

    @GetMapping("/stock-groups")
    public ResponseEntity<String> stockGroups(HttpServletRequest request) { return get("/reports/stock-groups", request); }

    @GetMapping("/ledgers")
    public ResponseEntity<String> ledgers(HttpServletRequest request) { return get("/reports/ledgers", request); }

    @GetMapping("/cost-centres")
    public ResponseEntity<String> costCentres(HttpServletRequest request) { return get("/reports/cost-centres", request); }

    @GetMapping("/cost-categories")
    public ResponseEntity<String> costCategories(HttpServletRequest request) { return get("/reports/cost-categories", request); }

    @GetMapping("/godowns")
    public ResponseEntity<String> godowns(HttpServletRequest request) { return get("/reports/godowns", request); }

    @GetMapping("/uoms")
    public ResponseEntity<String> uoms(HttpServletRequest request) { return get("/reports/uoms", request); }

    @GetMapping("/tax-rates")
    public ResponseEntity<String> taxRates(HttpServletRequest request) { return get("/reports/tax-rates", request); }

    @GetMapping("/outstanding-receivables")
    public ResponseEntity<String> outstandingReceivables(HttpServletRequest request) { return get("/reports/outstanding-receivables", request); }

    @GetMapping("/outstanding-payables")
    public ResponseEntity<String> outstandingPayables(HttpServletRequest request) { return get("/reports/outstanding-payables", request); }

    @GetMapping("/ledger-vouchers")
    public ResponseEntity<String> ledgerVouchers(HttpServletRequest request) { return get("/reports/ledger-vouchers", request); }

    @GetMapping("/stock-summary")
    public ResponseEntity<String> stockSummary(HttpServletRequest request) { return get("/reports/stock-summary", request); }

    @GetMapping("/batch-availability")
    public ResponseEntity<String> batchAvailability(HttpServletRequest request) { return get("/reports/batch-availability", request); }

    @GetMapping("/price-lists")
    public ResponseEntity<String> priceLists(HttpServletRequest request) { return get("/reports/price-lists", request); }

    @GetMapping("/companies")
    public ResponseEntity<String> companies(HttpServletRequest request) { return get("/reports/companies", request); }

    @GetMapping("/bank-reco-status")
    public ResponseEntity<String> bankRecoStatus(HttpServletRequest request) { return get("/reports/bank-reco-status", request); }

    @GetMapping("/trial-balance")
    public ResponseEntity<String> trialBalance(HttpServletRequest request) { return get("/reports/trial-balance", request); }

    @GetMapping("/balance-sheet")
    public ResponseEntity<String> balanceSheet(HttpServletRequest request) { return get("/reports/balance-sheet", request); }

    @GetMapping("/profit-loss")
    public ResponseEntity<String> profitLoss(HttpServletRequest request) { return get("/reports/profit-loss", request); }

    @GetMapping("/cash-book")
    public ResponseEntity<String> cashBook(HttpServletRequest request) { return get("/reports/cash-book", request); }

    @GetMapping("/bank-book")
    public ResponseEntity<String> bankBook(HttpServletRequest request) { return get("/reports/bank-book", request); }

    @GetMapping("/cash-flow")
    public ResponseEntity<String> cashFlow(HttpServletRequest request) { return get("/reports/cash-flow", request); }

    @GetMapping("/funds-flow")
    public ResponseEntity<String> fundsFlow(HttpServletRequest request) { return get("/reports/funds-flow", request); }

    @GetMapping("/sales-register")
    public ResponseEntity<String> salesRegister(HttpServletRequest request) { return get("/reports/sales-register", request); }

    @GetMapping("/sales-trend")
    public ResponseEntity<String> salesTrend(HttpServletRequest request) { return get("/reports/sales-trend", request); }

    @GetMapping("/purchase-register")
    public ResponseEntity<String> purchaseRegister(HttpServletRequest request) { return get("/reports/purchase-register", request); }

    @GetMapping("/journal-register")
    public ResponseEntity<String> journalRegister(HttpServletRequest request) { return get("/reports/journal-register", request); }

    @GetMapping("/receipt-register")
    public ResponseEntity<String> receiptRegister(HttpServletRequest request) { return get("/reports/receipt-register", request); }

    @GetMapping("/payment-register")
    public ResponseEntity<String> paymentRegister(HttpServletRequest request) { return get("/reports/payment-register", request); }

    @GetMapping("/gstr-1")
    public ResponseEntity<String> gstr1(HttpServletRequest request) { return get("/reports/gstr-1", request); }

    @GetMapping("/gstr-2")
    public ResponseEntity<String> gstr2(HttpServletRequest request) { return get("/reports/gstr-2", request); }

    @GetMapping("/gstr-3b")
    public ResponseEntity<String> gstr3b(HttpServletRequest request) { return get("/reports/gstr-3b", request); }

    // Advanced Inventory Reports
    @GetMapping("/stock-ageing-analysis")
    public ResponseEntity<String> stockAgeingAnalysis(HttpServletRequest request) { return get("/reports/stock-ageing-analysis", request); }

    @GetMapping("/movement-analysis")
    public ResponseEntity<String> movementAnalysis(HttpServletRequest request) { return get("/reports/movement-analysis", request); }

    @GetMapping("/reorder-status")
    public ResponseEntity<String> reorderStatus(HttpServletRequest request) { return get("/reports/reorder-status", request); }

    // Statutory TDS/TCS Reports
    @GetMapping("/form-26q")
    public ResponseEntity<String> form26q(HttpServletRequest request) { return get("/reports/form-26q", request); }

    @GetMapping("/form-24q")
    public ResponseEntity<String> form24q(HttpServletRequest request) { return get("/reports/form-24q", request); }

    @GetMapping("/form-27eq")
    public ResponseEntity<String> form27eq(HttpServletRequest request) { return get("/reports/form-27eq", request); }

    @GetMapping("/tds-outstandings")
    public ResponseEntity<String> tdsOutstandings(HttpServletRequest request) { return get("/reports/tds-outstandings", request); }

    // Financial & Costing Reports
    @GetMapping("/cost-centre-breakup")
    public ResponseEntity<String> costCentreBreakup(HttpServletRequest request) { return get("/reports/cost-centre-breakup", request); }

    @GetMapping("/ratio-analysis")
    public ResponseEntity<String> ratioAnalysis(HttpServletRequest request) { return get("/reports/ratio-analysis", request); }
}
