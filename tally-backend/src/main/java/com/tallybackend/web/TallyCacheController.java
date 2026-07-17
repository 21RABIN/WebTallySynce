package com.tallybackend.web;

import com.tallybackend.cache.TallyCacheReadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/cache")
public class TallyCacheController {

    private final TallyCacheReadService tallyCacheReadService;

    public TallyCacheController(TallyCacheReadService tallyCacheReadService) {
        this.tallyCacheReadService = tallyCacheReadService;
    }

    @GetMapping("/masters/companies")
    public ResponseEntity<Map<String, Object>> companies(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.companies(resolveCompany(request)));
    }

    @GetMapping("/masters/groups")
    public ResponseEntity<Map<String, Object>> groups(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.groups(resolveCompany(request)));
    }

    @GetMapping("/masters/ledgers")
    public ResponseEntity<Map<String, Object>> ledgers(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.ledgers(resolveCompany(request)));
    }

    @GetMapping("/masters/uoms")
    public ResponseEntity<Map<String, Object>> uoms(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.uoms(resolveCompany(request)));
    }

    @GetMapping("/masters/currencies")
    public ResponseEntity<Map<String, Object>> currencies(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.currencies(resolveCompany(request)));
    }

    @GetMapping("/masters/stock-groups")
    public ResponseEntity<Map<String, Object>> stockGroups(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.stockGroups(resolveCompany(request)));
    }

    @GetMapping("/masters/stock-items")
    public ResponseEntity<Map<String, Object>> stockItems(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.stockItems(resolveCompany(request)));
    }

    @GetMapping("/settings/company-currency")
    public ResponseEntity<Map<String, Object>> companyCurrency(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.companyCurrency(resolveCompany(request)));
    }

    @GetMapping("/settings/company-features")
    public ResponseEntity<Map<String, Object>> companyFeatures(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.companyFeatures(resolveCompany(request)));
    }

    @GetMapping("/settings/gst-registration")
    public ResponseEntity<Map<String, Object>> gstRegistration(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.gstRegistration(resolveCompany(request)));
    }

    @GetMapping("/settings/numbering-rules")
    public ResponseEntity<Map<String, Object>> numberingRules(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.numberingRules(resolveCompany(request)));
    }

    @GetMapping("/settings/tax-rate-tables")
    public ResponseEntity<Map<String, Object>> taxRateTables(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.taxRateTables(resolveCompany(request)));
    }

    @GetMapping("/settings/price-structures")
    public ResponseEntity<Map<String, Object>> priceStructures(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.priceStructures(resolveCompany(request)));
    }

    @GetMapping("/settings/stock-controls")
    public ResponseEntity<Map<String, Object>> stockControls(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.stockControls(resolveCompany(request)));
    }

    @GetMapping("/settings/security-roles")
    public ResponseEntity<Map<String, Object>> securityRoles(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.securityRoles(resolveCompany(request)));
    }

    @GetMapping("/settings/uqc-mappings")
    public ResponseEntity<Map<String, Object>> uqcMappings(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.uqcMappings(resolveCompany(request)));
    }

    @GetMapping("/settings/einvoice")
    public ResponseEntity<Map<String, Object>> einvoice(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.einvoiceSettings(resolveCompany(request)));
    }

    @GetMapping("/settings/ewaybill")
    public ResponseEntity<Map<String, Object>> ewaybill(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.ewaybillSettings(resolveCompany(request)));
    }

    @GetMapping("/reports/day-book")
    public ResponseEntity<Map<String, Object>> dayBook(@RequestParam("from_date") String fromDate,
                                                       @RequestParam("to_date") String toDate,
                                                       HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.dayBook(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/ledger-vouchers")
    public ResponseEntity<Map<String, Object>> ledgerVouchers(@RequestParam("from_date") String fromDate,
                                                              @RequestParam("to_date") String toDate,
                                                              @RequestParam("ledger_name") String ledgerName,
                                                              HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.ledgerVouchers(fromDate, toDate, ledgerName, resolveCompany(request)));
    }

    @GetMapping("/reports/balance-sheet")
    public ResponseEntity<Map<String, Object>> balanceSheet(@RequestParam("from_date") String fromDate,
                                                            @RequestParam("to_date") String toDate,
                                                            HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.balanceSheet(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/profit-loss")
    public ResponseEntity<Map<String, Object>> profitLoss(@RequestParam("from_date") String fromDate,
                                                          @RequestParam("to_date") String toDate,
                                                          HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.profitLoss(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/stock-summary")
    public ResponseEntity<Map<String, Object>> stockSummary(@RequestParam("from_date") String fromDate,
                                                            @RequestParam("to_date") String toDate,
                                                            HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.stockSummary(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/outstanding-receivables")
    public ResponseEntity<Map<String, Object>> outstandingReceivables(@RequestParam("from_date") String fromDate,
                                                                      @RequestParam("to_date") String toDate,
                                                                      HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.outstandingReceivables(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/outstanding-payables")
    public ResponseEntity<Map<String, Object>> outstandingPayables(@RequestParam("from_date") String fromDate,
                                                                   @RequestParam("to_date") String toDate,
                                                                   HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.outstandingPayables(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/batch-availability")
    public ResponseEntity<Map<String, Object>> batchAvailability(@RequestParam("from_date") String fromDate,
                                                                 @RequestParam("to_date") String toDate,
                                                                 HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.batchAvailability(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/price-lists")
    public ResponseEntity<Map<String, Object>> priceLists(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.reportPriceLists(resolveCompany(request)));
    }

    @GetMapping("/reports/bank-reco-status")
    public ResponseEntity<Map<String, Object>> bankRecoStatus(@RequestParam("from_date") String fromDate,
                                                              @RequestParam("to_date") String toDate,
                                                              HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.bankRecoStatus(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/trial-balance")
    public ResponseEntity<Map<String, Object>> trialBalance(@RequestParam("from_date") String fromDate,
                                                            @RequestParam("to_date") String toDate,
                                                            HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.trialBalance(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/cash-book")
    public ResponseEntity<Map<String, Object>> cashBook(@RequestParam("from_date") String fromDate,
                                                        @RequestParam("to_date") String toDate,
                                                        HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.cashBook(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/bank-book")
    public ResponseEntity<Map<String, Object>> bankBook(@RequestParam("from_date") String fromDate,
                                                        @RequestParam("to_date") String toDate,
                                                        HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.bankBook(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/cash-flow")
    public ResponseEntity<Map<String, Object>> cashFlow(@RequestParam("from_date") String fromDate,
                                                        @RequestParam("to_date") String toDate,
                                                        HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.cashFlow(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/funds-flow")
    public ResponseEntity<Map<String, Object>> fundsFlow(@RequestParam("from_date") String fromDate,
                                                         @RequestParam("to_date") String toDate,
                                                         HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.fundsFlow(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/sales-register")
    public ResponseEntity<Map<String, Object>> salesRegister(@RequestParam("from_date") String fromDate,
                                                             @RequestParam("to_date") String toDate,
                                                             HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.salesRegister(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/sales-trend")
    public ResponseEntity<Map<String, Object>> salesTrend(@RequestParam("from_date") String fromDate,
                                                          @RequestParam("to_date") String toDate,
                                                          HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.salesTrend(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/purchase-register")
    public ResponseEntity<Map<String, Object>> purchaseRegister(@RequestParam("from_date") String fromDate,
                                                                @RequestParam("to_date") String toDate,
                                                                HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.purchaseRegister(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/journal-register")
    public ResponseEntity<Map<String, Object>> journalRegister(@RequestParam("from_date") String fromDate,
                                                               @RequestParam("to_date") String toDate,
                                                               HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.journalRegister(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/receipt-register")
    public ResponseEntity<Map<String, Object>> receiptRegister(@RequestParam("from_date") String fromDate,
                                                               @RequestParam("to_date") String toDate,
                                                               HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.receiptRegister(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/payment-register")
    public ResponseEntity<Map<String, Object>> paymentRegister(@RequestParam("from_date") String fromDate,
                                                               @RequestParam("to_date") String toDate,
                                                               HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.paymentRegister(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/gstr-1")
    public ResponseEntity<Map<String, Object>> gstr1(@RequestParam("from_date") String fromDate,
                                                     @RequestParam("to_date") String toDate,
                                                     HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.gstr1(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/gstr-2")
    public ResponseEntity<Map<String, Object>> gstr2(@RequestParam("from_date") String fromDate,
                                                     @RequestParam("to_date") String toDate,
                                                     HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.gstr2(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/gstr-3b")
    public ResponseEntity<Map<String, Object>> gstr3b(@RequestParam("from_date") String fromDate,
                                                      @RequestParam("to_date") String toDate,
                                                      HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.gstr3b(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/stock-ageing-analysis")
    public ResponseEntity<Map<String, Object>> stockAgeingAnalysis(@RequestParam("from_date") String fromDate,
                                                                   @RequestParam("to_date") String toDate,
                                                                   HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.stockAgeingAnalysis(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/movement-analysis")
    public ResponseEntity<Map<String, Object>> movementAnalysis(@RequestParam("from_date") String fromDate,
                                                                @RequestParam("to_date") String toDate,
                                                                HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.movementAnalysis(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/reorder-status")
    public ResponseEntity<Map<String, Object>> reorderStatus(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.reorderStatus(resolveCompany(request)));
    }

    @GetMapping("/reports/form-26q")
    public ResponseEntity<Map<String, Object>> form26q(@RequestParam("from_date") String fromDate,
                                                       @RequestParam("to_date") String toDate,
                                                       HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.form26q(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/form-24q")
    public ResponseEntity<Map<String, Object>> form24q(@RequestParam("from_date") String fromDate,
                                                       @RequestParam("to_date") String toDate,
                                                       HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.form24q(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/form-27eq")
    public ResponseEntity<Map<String, Object>> form27eq(@RequestParam("from_date") String fromDate,
                                                        @RequestParam("to_date") String toDate,
                                                        HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.form27eq(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/tds-outstandings")
    public ResponseEntity<Map<String, Object>> tdsOutstandings(@RequestParam("from_date") String fromDate,
                                                               @RequestParam("to_date") String toDate,
                                                               HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.tdsOutstandings(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/cost-centre-breakup")
    public ResponseEntity<Map<String, Object>> costCentreBreakup(@RequestParam("from_date") String fromDate,
                                                                 @RequestParam("to_date") String toDate,
                                                                 HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.costCentreBreakup(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/reports/ratio-analysis")
    public ResponseEntity<Map<String, Object>> ratioAnalysis(@RequestParam("from_date") String fromDate,
                                                             @RequestParam("to_date") String toDate,
                                                             HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.ratioAnalysis(fromDate, toDate, resolveCompany(request)));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        return ResponseEntity.ok(tallyCacheReadService.status(resolveCompany(request)));
    }

    @GetMapping("/runs")
    public ResponseEntity<Map<String, Object>> runs() {
        return ResponseEntity.ok(tallyCacheReadService.status());
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncNow() {
        return ResponseEntity.ok(tallyCacheReadService.triggerSync());
    }

    private String resolveCompany(HttpServletRequest request) {
        String company = request.getHeader("X-Company");
        if (company == null || company.trim().isEmpty()) {
            company = request.getParameter("company");
        }
        return company == null ? null : company.trim();
    }
}
