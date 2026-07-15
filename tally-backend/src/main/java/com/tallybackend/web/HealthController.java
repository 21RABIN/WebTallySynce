package com.tallybackend.web;

import com.tallybackend.service.ConnectorStatusService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
@SecurityRequirements
public class HealthController {

    private final ConnectorStatusService connectorStatusService;

    public HealthController(ConnectorStatusService connectorStatusService) {
        this.connectorStatusService = connectorStatusService;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> resp = new HashMap<>();
        resp.put("status", "ok");
        resp.put("version", "0.1.0");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> readiness(HttpServletRequest request) {
        return ResponseEntity.ok(connectorStatusService.connectorReadiness(request));
    }

    @GetMapping("/capabilities")
    public ResponseEntity<Map<String, Object>> capabilities(HttpServletRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("backend", health().getBody());
        response.put("connector", connectorStatusService.connectorCapabilities(request));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/implementation")
    public ResponseEntity<Map<String, Object>> implementation(HttpServletRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ok");
        response.put("backend", health().getBody());
        response.put("connector", connectorStatusService.connectorImplementationMatrix(request));
        return ResponseEntity.ok(response);
    }
}
