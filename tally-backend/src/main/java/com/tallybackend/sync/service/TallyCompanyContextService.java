package com.tallybackend.sync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tallybackend.sync.dto.CompanySelectionRequest;
import com.tallybackend.sync.exception.SyncApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TallyCompanyContextService {
    private static final String ACTIVE_COMPANY_SESSION_KEY = "ACTIVE_TALLY_COMPANY";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String connectorBaseUrl;
    private final String connectorAgentKey;

    public TallyCompanyContextService(RestTemplate restTemplate,
                                      ObjectMapper objectMapper,
                                      @Value("${ingest.base-url:http://127.0.0.1:8082}") String connectorBaseUrl,
                                      @Value("${ingest.agent-key:local-dev-key}") String connectorAgentKey) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.connectorBaseUrl = connectorBaseUrl;
        this.connectorAgentKey = connectorAgentKey;
    }

    public Map<String, Object> activeCompany(HttpServletRequest request) {
        String activeCompany = sessionCompany(request);
        Map<String, Object> features = getCompanyFeatures(request);
        if (activeCompany == null) {
            activeCompany = firstCompanyName(features);
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("companyName", activeCompany);
        response.put("source", sessionCompany(request) != null ? "session" : "connector");
        response.put("features", features);
        return response;
    }

    public Map<String, Object> selectCompany(CompanySelectionRequest requestBody, HttpServletRequest request) {
        String companyName = requestBody == null ? null : requestBody.getCompanyName();
        if (companyName == null || companyName.trim().isEmpty()) {
            throw new SyncApiException(HttpStatus.BAD_REQUEST, "companyName is required");
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("NAME", companyName.trim());
        Map<String, Object> upstream = connectorPost("/companies/open", payload, null);
        HttpSession session = request.getSession(true);
        session.setAttribute(ACTIVE_COMPANY_SESSION_KEY, companyName.trim());
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("companyName", companyName.trim());
        response.put("selected", true);
        response.put("connectorResponse", upstream);
        return response;
    }

    public Map<String, Object> getCompanyFeatures(HttpServletRequest request) {
        return connectorGet("/settings/company-features", sessionCompany(request));
    }

    public Map<String, Object> getGstDetails(HttpServletRequest request) {
        return connectorGet("/settings/gst-registration", sessionCompany(request));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> connectorGet(String path, String companyName) {
        HttpHeaders headers = baseHeaders(companyName);
        ResponseEntity<String> response = restTemplate.exchange(
                connectorBaseUrl + path,
                HttpMethod.GET,
                new HttpEntity<String>(headers),
                String.class
        );
        return readJsonMap(response.getBody());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> connectorPost(String path, Map<String, Object> payload, String companyName) {
        HttpHeaders headers = baseHeaders(companyName);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    connectorBaseUrl + path,
                    HttpMethod.POST,
                    new HttpEntity<String>(objectMapper.writeValueAsString(payload), headers),
                    String.class
            );
            return readJsonMap(response.getBody());
        } catch (Exception ex) {
            throw new SyncApiException(HttpStatus.BAD_GATEWAY, "Unable to communicate with local tally connector");
        }
    }

    private HttpHeaders baseHeaders(String companyName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("X-AGENT-KEY", connectorAgentKey);
        if (companyName != null && !companyName.trim().isEmpty()) {
            headers.set("X-Company", companyName.trim());
        }
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String body) {
        if (body == null || body.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Object parsed = objectMapper.readValue(body, Object.class);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
        } catch (Exception ignored) {
        }
        return Collections.singletonMap("message", body);
    }

    @SuppressWarnings("unchecked")
    private String firstCompanyName(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object companies = payload.get("COMPANY");
        if (companies instanceof java.util.List && !((java.util.List<?>) companies).isEmpty()) {
            Object first = ((java.util.List<?>) companies).get(0);
            if (first instanceof Map) {
                Object name = ((Map<String, Object>) first).get("NAME");
                return name == null ? null : String.valueOf(name);
            }
        }
        Object name = payload.get("NAME");
        return name == null ? null : String.valueOf(name);
    }

    private String sessionCompany(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(ACTIVE_COMPANY_SESSION_KEY);
        return value == null ? null : String.valueOf(value);
    }
}
