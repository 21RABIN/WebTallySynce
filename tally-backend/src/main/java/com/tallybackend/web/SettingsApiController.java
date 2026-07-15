package com.tallybackend.web;

import com.tallybackend.cache.TallyPostWriteRefreshService;
import com.tallybackend.service.ConnectorGatewayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/settings")
public class SettingsApiController extends AbstractConnectorController {

    public SettingsApiController(ConnectorGatewayService connectorGatewayService,
                                 TallyPostWriteRefreshService tallyPostWriteRefreshService) {
        super(connectorGatewayService, tallyPostWriteRefreshService);
    }

    @GetMapping("/company-features")
    public ResponseEntity<String> companyFeatures(HttpServletRequest request) { return get("/settings/company-features", request); }
    @PostMapping("/company-features")
    public ResponseEntity<String> companyFeaturesUpdate(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/settings/company-features", request, body); }

    @GetMapping("/gst-registration")
    public ResponseEntity<String> gstRegistration(HttpServletRequest request) { return get("/settings/gst-registration", request); }
    @PostMapping("/gst-registration")
    public ResponseEntity<String> gstRegistrationUpdate(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/settings/gst-registration", request, body); }

    @GetMapping("/company-currency")
    public ResponseEntity<String> companyCurrency(HttpServletRequest request) { return get("/settings/company-currency", request); }
    @PostMapping("/company-currency")
    public ResponseEntity<String> companyCurrencyUpdate(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/settings/company-currency", request, body); }

    @GetMapping("/numbering-rules")
    public ResponseEntity<String> numberingRules(HttpServletRequest request) { return get("/settings/numbering-rules", request); }
    @PostMapping("/numbering-rules")
    public ResponseEntity<String> numberingRulesUpdate(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/settings/numbering-rules", request, body); }

    @GetMapping("/tax-rate-tables")
    public ResponseEntity<String> taxRateTables(HttpServletRequest request) { return get("/settings/tax-rate-tables", request); }

    @GetMapping("/price-structures")
    public ResponseEntity<String> priceStructures(HttpServletRequest request) { return get("/settings/price-structures", request); }
    @PostMapping("/price-structures")
    public ResponseEntity<String> priceStructuresUpdate(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/settings/price-structures", request, body); }

    @GetMapping("/stock-controls")
    public ResponseEntity<String> stockControls(HttpServletRequest request) { return get("/settings/stock-controls", request); }
    @PostMapping("/stock-controls")
    public ResponseEntity<String> stockControlsUpdate(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/settings/stock-controls", request, body); }

    @GetMapping("/security-roles")
    public ResponseEntity<String> securityRoles(HttpServletRequest request) { return get("/settings/security-roles", request); }
    @PostMapping("/security-roles")
    public ResponseEntity<String> securityRolesUpdate(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/settings/security-roles", request, body); }

    @GetMapping("/uqc-mappings")
    public ResponseEntity<String> uqcMappings(HttpServletRequest request) { return get("/settings/uqc-mappings", request); }

    @GetMapping("/einvoice")
    public ResponseEntity<String> einvoice(HttpServletRequest request) { return get("/settings/einvoice", request); }
    @PostMapping("/einvoice")
    public ResponseEntity<String> einvoiceUpdate(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/settings/einvoice", request, body); }

    @GetMapping("/ewaybill")
    public ResponseEntity<String> ewaybill(HttpServletRequest request) { return get("/settings/ewaybill", request); }
    @PostMapping("/ewaybill")
    public ResponseEntity<String> ewaybillUpdate(HttpServletRequest request, @RequestBody(required = false) String body) { return postAndRefresh("/settings/ewaybill", request, body); }
}
