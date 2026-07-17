package com.tallybackend.sync.controller;

import com.tallybackend.sync.dto.CompanySelectionRequest;
import com.tallybackend.sync.service.TallyCompanyContextService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/tally/company")
@Tag(name = "Tally Company Context")
public class TallyCompanyContextController {

    private final TallyCompanyContextService tallyCompanyContextService;

    public TallyCompanyContextController(TallyCompanyContextService tallyCompanyContextService) {
        this.tallyCompanyContextService = tallyCompanyContextService;
    }

    @GetMapping("/active")
    public Map<String, Object> active(HttpServletRequest request) {
        return tallyCompanyContextService.activeCompany(request);
    }

    @PostMapping("/select")
    public Map<String, Object> select(@RequestBody CompanySelectionRequest requestBody,
                                      HttpServletRequest request) {
        return tallyCompanyContextService.selectCompany(requestBody, request);
    }

    @GetMapping("/features")
    public Map<String, Object> features(HttpServletRequest request) {
        return tallyCompanyContextService.getCompanyFeatures(request);
    }

    @GetMapping("/gst-details")
    public Map<String, Object> gstDetails(HttpServletRequest request) {
        return tallyCompanyContextService.getGstDetails(request);
    }
}
