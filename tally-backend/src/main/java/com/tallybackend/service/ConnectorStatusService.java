package com.tallybackend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ConnectorStatusService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ConnectorGatewayService connectorGatewayService;

    public ConnectorStatusService(RestTemplate restTemplate,
                                  ObjectMapper objectMapper,
                                  ConnectorGatewayService connectorGatewayService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.connectorGatewayService = connectorGatewayService;
    }

    public Map<String, Object> connectorCapabilities(HttpServletRequest request) {
        ResolvedConnectorTarget target = connectorGatewayService.resolveTargetForRequest(request);
        Map<String, Object> payload = fetchJson(target, "/capabilities");
        payload.put("connector_target", target);
        return payload;
    }

    public Map<String, Object> connectorReadiness(HttpServletRequest request) {
        ResolvedConnectorTarget target = connectorGatewayService.resolveTargetForRequest(request);
        Map<String, Object> readiness = fetchJson(target, "/health/readiness");
        Map<String, Object> connectorHealth = fetchJson(target, "/health");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", readiness.get("status"));
        response.put("ready", readiness.get("ready"));
        response.put("connector_target", target);
        response.put("connector_health", connectorHealth);
        response.put("connector_readiness", readiness);
        return response;
    }

    public Map<String, Object> connectorImplementationMatrix(HttpServletRequest request) {
        ResolvedConnectorTarget target = connectorGatewayService.resolveTargetForRequest(request);
        Map<String, Object> payload = fetchJson(target, "/implementation-matrix");
        payload.put("connector_target", target);
        return payload;
    }

    private Map<String, Object> fetchJson(ResolvedConnectorTarget target, String path) {
        String url = normalizeUrl(target.getBaseUrl(), path);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        if (target.getAgentKey() != null && !target.getAgentKey().trim().isEmpty()) {
            headers.set("X-AGENT-KEY", target.getAgentKey().trim());
        }
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            Map<String, Object> payload = parseJson(response.getBody());
            payload.put("_http_status", response.getStatusCodeValue());
            return payload;
        } catch (ResourceAccessException ex) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "error");
            error.put("reachable", false);
            error.put("detail", "connector_unreachable");
            error.put("reason", ex.getMessage());
            error.put("_url", url);
            return error;
        }
    }

    private Map<String, Object> parseJson(String body) {
        if (body == null || body.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("status", "error");
            wrapped.put("detail", "invalid_json");
            wrapped.put("raw_body", body);
            return wrapped;
        }
    }

    private String normalizeUrl(String baseUrl, String path) {
        String normalizedBase = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return normalizedBase + normalizedPath;
    }
}
