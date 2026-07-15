package com.tallybackend.web;

import com.tallybackend.service.ConnectorGatewayService;
import com.tallybackend.service.ConnectorRegistration;
import com.tallybackend.service.ConnectorRegistryService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/routing")
@SecurityRequirements
public class RoutingApiController {

    private final ConnectorRegistryService connectorRegistryService;
    private final ConnectorGatewayService connectorGatewayService;

    public RoutingApiController(ConnectorRegistryService connectorRegistryService,
                                ConnectorGatewayService connectorGatewayService) {
        this.connectorRegistryService = connectorRegistryService;
        this.connectorGatewayService = connectorGatewayService;
    }

    @GetMapping("/debug")
    public ResponseEntity<Map<String, Object>> debug(HttpServletRequest request) {
        return ResponseEntity.ok(connectorGatewayService.routingDebug(request));
    }

    @PostMapping("/connector")
    public ResponseEntity<?> selectConnector(@RequestBody Map<String, Object> payload,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {
        String connectorId = text(first(payload, "connector_id", "connectorId", "id"));
        String company = text(first(payload, "company"));
        String branch = text(first(payload, "branch"));
        if (connectorId == null && company == null && branch == null) {
            return ResponseEntity.badRequest().body(error("connector_id, company, or branch is required"));
        }

        ConnectorRegistration registration;
        try {
            registration = selectRegistration(connectorId, company, branch);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(error(ex.getMessage()));
        }
        if (registration == null) {
            return ResponseEntity.status(404).body(error("Connector not found or inactive for the supplied selection"));
        }

        HttpSession session = request.getSession(true);
        session.setAttribute(ConnectorGatewayService.CONNECTOR_SELECTION_COOKIE, registration.getConnectorId());

        String cookie = ConnectorGatewayService.CONNECTOR_SELECTION_COOKIE + "=" + registration.getConnectorId()
                + "; Path=/; HttpOnly; SameSite=Lax";
        response.addHeader(HttpHeaders.SET_COOKIE, cookie);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "connector_selected");
        result.put("connector", registration);
        result.put("routing", connectorGatewayService.routingDebug(request));
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/connector")
    public ResponseEntity<?> clearSelection(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ConnectorGatewayService.CONNECTOR_SELECTION_COOKIE);
        }
        response.addHeader(HttpHeaders.SET_COOKIE,
                ConnectorGatewayService.CONNECTOR_SELECTION_COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "connector_selection_cleared");
        result.put("routing", connectorGatewayService.routingDebug(request));
        return ResponseEntity.ok(result);
    }

    private Object first(Map<String, Object> payload, String... keys) {
        if (payload == null) {
            return null;
        }
        for (String key : keys) {
            if (payload.containsKey(key)) {
                Object value = payload.get(key);
                if (value != null && !String.valueOf(value).trim().isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Map<String, String> error(String message) {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("error", message);
        return response;
    }

    private ConnectorRegistration selectRegistration(String connectorId, String company, String branch) {
        if (connectorId != null) {
            ConnectorRegistration registration = connectorRegistryService.get(connectorId);
            if (registration != null && registration.isActive()) {
                return registration;
            }
        }
        if (company != null) {
            ConnectorRegistration registration = findByResolvedTarget(connectorRegistryService.resolveByCompany(company));
            if (registration != null) {
                return registration;
            }
        }
        if (branch != null) {
            ConnectorRegistration registration = findByResolvedTarget(connectorRegistryService.resolveByBranch(branch));
            if (registration != null) {
                return registration;
            }
        }
        return null;
    }

    private ConnectorRegistration findByResolvedTarget(Object resolvedTarget) {
        if (!(resolvedTarget instanceof com.tallybackend.service.ResolvedConnectorTarget)) {
            return null;
        }
        com.tallybackend.service.ResolvedConnectorTarget target = (com.tallybackend.service.ResolvedConnectorTarget) resolvedTarget;
        if (target == null || target.getConnectorId() == null) {
            return null;
        }
        ConnectorRegistration registration = connectorRegistryService.get(target.getConnectorId());
        if (registration == null || !registration.isActive()) {
            return null;
        }
        return registration;
    }
}
