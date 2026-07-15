package com.tallybackend.web;

import com.tallybackend.service.ConnectorGatewayService;
import com.tallybackend.service.ConnectorRegistration;
import com.tallybackend.service.ConnectorRegistryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/connectors")
public class ConnectorsApiController {

    private final ConnectorRegistryService connectorRegistryService;
    private final ConnectorGatewayService connectorGatewayService;

    public ConnectorsApiController(ConnectorRegistryService connectorRegistryService,
                                   ConnectorGatewayService connectorGatewayService) {
        this.connectorRegistryService = connectorRegistryService;
        this.connectorGatewayService = connectorGatewayService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list() {
        return ResponseEntity.ok(summary());
    }

    @GetMapping("/{connectorId}")
    public ResponseEntity<?> get(@PathVariable String connectorId) {
        ConnectorRegistration registration = connectorRegistryService.get(connectorId);
        if (registration == null) {
            return ResponseEntity.status(404).body(error("Connector not found: " + connectorId));
        }
        return ResponseEntity.ok(registration);
    }

    @PostMapping
    public ResponseEntity<?> register(@RequestBody ConnectorRegistration registration) {
        try {
            ConnectorRegistration saved = connectorRegistryService.register(registration);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Connector registered successfully");
            response.put("connector", saved);
            response.put("registry", summary());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error(ex.getMessage()));
        }
    }

    @PostMapping("/{connectorId}/deactivate")
    public ResponseEntity<?> deactivate(@PathVariable String connectorId) {
        ConnectorRegistration registration = connectorRegistryService.deactivate(connectorId);
        if (registration == null) {
            return ResponseEntity.status(404).body(error("Connector not found: " + connectorId));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", "Connector deactivated");
        response.put("connector", registration);
        return ResponseEntity.ok(response);
    }

    private Map<String, Object> summary() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("routing", connectorGatewayService.routingSummary());
        response.put("connectors", connectorRegistryService.list());
        return response;
    }

    private Map<String, String> error(String message) {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("error", message);
        return response;
    }
}
