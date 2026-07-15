package com.tallybackend.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.tallybackend.service.ConnectorRegistryService;
import com.tallybackend.service.JsonShapeNormalizer;
import com.tallybackend.service.ResolvedConnectorTarget;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TallyCacheConnectorClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final XmlMapper xmlMapper;
    private final JsonShapeNormalizer jsonShapeNormalizer;
    private final ConnectorRegistryService connectorRegistryService;
    private final TallyCacheProperties properties;

    public TallyCacheConnectorClient(RestTemplate restTemplate,
                                     ObjectMapper objectMapper,
                                     JsonShapeNormalizer jsonShapeNormalizer,
                                     ConnectorRegistryService connectorRegistryService,
                                     TallyCacheProperties properties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.xmlMapper = new XmlMapper();
        this.jsonShapeNormalizer = jsonShapeNormalizer;
        this.connectorRegistryService = connectorRegistryService;
        this.properties = properties;
    }

    public ConnectorFetchResult fetch(String path, Map<String, String> queryParams) {
        return fetch(path, queryParams, null);
    }

    public ConnectorFetchResult fetch(String path, Map<String, String> queryParams, String company) {
        ResolvedConnectorTarget target = company == null || company.trim().isEmpty()
                ? connectorRegistryService.defaultTarget()
                : connectorRegistryService.resolveByCompany(company);
        if (target == null) {
            target = connectorRegistryService.defaultTarget();
        }
        if (target == null || target.getBaseUrl() == null || target.getBaseUrl().trim().isEmpty()) {
            throw new IllegalStateException("No default connector target is configured.");
        }

        String effectiveCompany = company == null || company.trim().isEmpty() ? properties.getCompany() : company.trim();
        String url = buildUrl(target.getBaseUrl(), path, withCompanyQueryParam(queryParams, effectiveCompany));
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (target.getAgentKey() != null && !target.getAgentKey().trim().isEmpty()) {
            headers.set("X-AGENT-KEY", target.getAgentKey().trim());
        }
        if (!effectiveCompany.isEmpty()) {
            headers.set("X-Company", effectiveCompany);
        }

        ResponseEntity<String> response = restTemplate.exchange(URI.create(url), HttpMethod.GET, new HttpEntity<String>(headers), String.class);
        return new ConnectorFetchResult(target, normalizeBody(response.getBody(), response.getHeaders().getContentType()));
    }

    private Map<String, String> withCompanyQueryParam(Map<String, String> queryParams, String company) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        if (queryParams != null) {
            params.putAll(queryParams);
        }
        if (company != null && !company.trim().isEmpty() && !params.containsKey("company")) {
            params.put("company", company.trim());
        }
        return params;
    }

    private String buildUrl(String baseUrl, String path, Map<String, String> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path(path.startsWith("/") ? path : "/" + path);
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().trim().isEmpty()) {
                builder.queryParam(entry.getKey(), entry.getValue().trim());
            }
        }
        return builder.encode(StandardCharsets.UTF_8).build().toUriString();
    }

    private JsonNode normalizeBody(String body, MediaType contentType) {
        if (body == null || body.trim().isEmpty()) {
            return objectMapper.createObjectNode();
        }
        String trimmed = body.trim();
        try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return jsonShapeNormalizer.normalizeForRead(objectMapper.readTree(trimmed));
            }
            if (trimmed.startsWith("<")
                    || (contentType != null && (MediaType.APPLICATION_XML.includes(contentType)
                    || MediaType.TEXT_XML.includes(contentType)
                    || contentType.getSubtype().toLowerCase().contains("xml")))) {
                return jsonShapeNormalizer.normalizeForRead(xmlMapper.readTree(trimmed.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {
        }
        Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
        wrapped.put("message", trimmed);
        return objectMapper.valueToTree(wrapped);
    }

    public static class ConnectorFetchResult {
        private final ResolvedConnectorTarget target;
        private final JsonNode body;

        public ConnectorFetchResult(ResolvedConnectorTarget target, JsonNode body) {
            this.target = target;
            this.body = body;
        }

        public ResolvedConnectorTarget getTarget() {
            return target;
        }

        public JsonNode getBody() {
            return body;
        }
    }
}
