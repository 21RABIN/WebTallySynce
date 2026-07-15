package com.tallybackend.web;

import com.tallybackend.cache.TallyPostWriteRefreshService;
import com.tallybackend.service.ConnectorGatewayService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/companies")
public class CompaniesApiController extends AbstractConnectorController {

    public CompaniesApiController(ConnectorGatewayService connectorGatewayService,
                                  TallyPostWriteRefreshService tallyPostWriteRefreshService) {
        super(connectorGatewayService, tallyPostWriteRefreshService);
    }

    @GetMapping
    public ResponseEntity<String> companies(HttpServletRequest request) {
        return get("/companies", request);
    }

    @PostMapping
    public ResponseEntity<String> companiesUpsert(HttpServletRequest request,
                                                  @RequestBody(required = false) String body) {
        return postAndRefresh("/companies", request, body);
    }

    @PostMapping("/open")
    public ResponseEntity<String> openCompany(HttpServletRequest request,
                                              @RequestBody(required = false) String body) {
        return postAndRefresh("/companies/open", request, body);
    }

    @GetMapping("/list")
    public ResponseEntity<String> listCompanies(HttpServletRequest request) {
        return get("/companies/list", request);
    }

    @PostMapping("/update")
    public ResponseEntity<String> updateCompany(HttpServletRequest request,
                                                @RequestBody(required = false) String body) {
        return postAndRefresh("/companies/update", request, body);
    }

    @PostMapping("/create")
    public ResponseEntity<String> createCompany(HttpServletRequest request,
                                                @RequestBody(required = false) String body) {
        return postAndRefresh("/companies/create", request, body);
    }

    @PostMapping("/alter")
    public ResponseEntity<String> alterCompany(HttpServletRequest request,
                                               @RequestBody(required = false) String body) {
        return postAndRefresh("/companies/alter", request, body);
    }
}
