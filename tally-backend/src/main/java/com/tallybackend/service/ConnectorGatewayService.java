package com.tallybackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConnectorGatewayService {
    public static final String CONNECTOR_SELECTION_COOKIE = "TALLY_CONNECTOR_ID";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final XmlMapper xmlMapper;
    private final JsonShapeNormalizer jsonShapeNormalizer;
    private final WritePayloadTransformer writePayloadTransformer;
    private final ConnectorRegistryService connectorRegistryService;
    private final TallyVoucherWriteQueueService tallyVoucherWriteQueueService;
    private final boolean autoRouteByClientIp;
    private final int autoRoutePort;
    private final boolean allowDirectBaseUrlOverride;

    public ConnectorGatewayService(RestTemplate restTemplate,
                                   ObjectMapper objectMapper,
                                   JsonShapeNormalizer jsonShapeNormalizer,
                                   WritePayloadTransformer writePayloadTransformer,
                                   ConnectorRegistryService connectorRegistryService,
                                   TallyVoucherWriteQueueService tallyVoucherWriteQueueService,
                                   @Value("${connector.auto-route.by-client-ip:true}") boolean autoRouteByClientIp,
                                   @Value("${connector.auto-route.port:8082}") int autoRoutePort,
                                   @Value("${connector.allow-direct-base-url-override:false}") boolean allowDirectBaseUrlOverride,
                                   @Value("${ingest.base-url:http://127.0.0.1:8082}") String connectorBaseUrl,
                                   @Value("${ingest.agent-key:local-dev-key}") String agentKey) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.xmlMapper = new XmlMapper();
        this.jsonShapeNormalizer = jsonShapeNormalizer;
        this.writePayloadTransformer = writePayloadTransformer;
        this.connectorRegistryService = connectorRegistryService;
        this.tallyVoucherWriteQueueService = tallyVoucherWriteQueueService;
        this.autoRouteByClientIp = autoRouteByClientIp;
        this.autoRoutePort = autoRoutePort;
        this.allowDirectBaseUrlOverride = allowDirectBaseUrlOverride;
    }

    public ResponseEntity<String> forward(HttpMethod method,
                                          String connectorPath,
                                          HttpServletRequest request,
                                          String body) {
        try {
            ResolvedConnectorTarget target = resolveTarget(request);
            String url = buildUrl(target.getBaseUrl(), connectorPath, request);
            HttpHeaders headers = buildHeaders(request, method, target);
            String transformedBody = writePayloadTransformer.transform(connectorPath, method, body);
            HttpEntity<String> entity = transformedBody == null ? new HttpEntity<>(headers) : new HttpEntity<>(transformedBody, headers);
            ResponseEntity<String> upstream = restTemplate.exchange(url, method, entity, String.class);
            if (tallyVoucherWriteQueueService.supportsQueue(method, connectorPath, request)
                    && tallyVoucherWriteQueueService.shouldQueueForUpstreamFailure(upstream.getStatusCode(), upstream.getBody())) {
                return tallyVoucherWriteQueueService.queueFromRequest(
                        method,
                        connectorPath,
                        request,
                        body,
                        target,
                        "TallyPrime is not available right now. The voucher was saved for automatic retry.",
                        upstream.getStatusCode(),
                        upstream.getBody()
                );
            }
            if (upstream.getStatusCode().is2xxSuccessful()) {
                tallyVoucherWriteQueueService.recordAppliedRequest(
                        method,
                        connectorPath,
                        request,
                        body,
                        target,
                        upstream.getBody()
                );
            }
            return normalize(upstream, method);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, "invalid_connector_target", e.getMessage(), null, null);
        } catch (RestClientResponseException e) {
            ResolvedConnectorTarget target = safeResolveTarget(request);
            HttpStatus status = HttpStatus.resolve(e.getRawStatusCode());
            String responseBody = e.getResponseBodyAsString();
            if (status != null
                    && tallyVoucherWriteQueueService.supportsQueue(method, connectorPath, request)
                    && tallyVoucherWriteQueueService.shouldQueueForUpstreamFailure(status, responseBody)) {
                return tallyVoucherWriteQueueService.queueFromRequest(
                        method,
                        connectorPath,
                        request,
                        body,
                        target,
                        "TallyPrime is not available right now. The voucher was saved for sync review.",
                        status,
                        responseBody
                );
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return new ResponseEntity<>(
                    normalizeBody(responseBody, e.getResponseHeaders() == null ? null : e.getResponseHeaders().getContentType(), method),
                    headers,
                    status == null ? HttpStatus.BAD_GATEWAY : status
            );
        } catch (ResourceAccessException e) {
            ResolvedConnectorTarget target = safeResolveTarget(request);
            if (tallyVoucherWriteQueueService.supportsQueue(method, connectorPath, request)) {
                return tallyVoucherWriteQueueService.queueFromRequest(
                        method,
                        connectorPath,
                        request,
                        body,
                        target,
                        "Backend could not reach Tally right now. The voucher was saved for automatic retry.",
                        HttpStatus.BAD_GATEWAY,
                        e.getMessage()
                );
            }
            String targetBaseUrl = target == null ? null : target.getBaseUrl();
            String resolutionSource = target == null ? null : target.getResolutionSource();
            return errorResponse(
                    HttpStatus.BAD_GATEWAY,
                    "connector_unreachable",
                    "Backend could not connect to the routed connector instance.",
                    targetBaseUrl,
                    resolutionSource
            );
        }
    }

    public Map<String, Object> routingSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("registry_file", connectorRegistryService.getRegistryFilePath());
        summary.put("connectors", connectorRegistryService.list());
        summary.put("default_target", connectorRegistryService.defaultTarget());
        summary.put("auto_route_by_client_ip", autoRouteByClientIp);
        summary.put("auto_route_port", autoRoutePort);
        return summary;
    }

    public Map<String, Object> routingDebug(HttpServletRequest request) {
        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("client_ip", resolveClientIp(request));
        debug.put("selected_connector_cookie", selectedConnectorId(request));
        debug.put("requested_connector_id", firstNonBlank(
                trimToNull(request.getHeader("X-Connector-Id")),
                trimToNull(request.getParameter("connector_id"))
        ));
        debug.put("requested_company", firstNonBlank(
                trimToNull(request.getHeader("X-Company")),
                trimToNull(request.getParameter("company"))
        ));
        debug.put("auto_route_by_client_ip", autoRouteByClientIp);
        debug.put("auto_route_port", autoRoutePort);
        debug.put("resolution_candidates", resolutionCandidates(request));
        ResolvedConnectorTarget resolved = safeResolveTarget(request);
        if (resolved != null) {
            debug.put("resolved_target", resolved);
        }
        return debug;
    }

    public ResolvedConnectorTarget resolveTargetForRequest(HttpServletRequest request) {
        return resolveTarget(request);
    }

    private ResolvedConnectorTarget resolveTarget(HttpServletRequest request) {
        String directBaseUrl = trimToNull(request.getHeader("X-Connector-Base-Url"));
        if (directBaseUrl != null) {
            if (!allowDirectBaseUrlOverride && !hasAdminRole(request)) {
                throw new IllegalArgumentException("Direct connector base URL override is restricted to admin/debug usage.");
            }
            return new ResolvedConnectorTarget(
                    directBaseUrl,
                    trimToNull(request.getHeader("X-Connector-Agent-Key")),
                    trimToNull(request.getHeader("X-Connector-Id")),
                    "header:base-url"
            );
        }

        String connectorId = trimToNull(request.getHeader("X-Connector-Id"));
        if (connectorId == null) {
            connectorId = trimToNull(request.getParameter("connector_id"));
        }
        if (connectorId != null) {
            ResolvedConnectorTarget resolved = connectorRegistryService.resolveById(connectorId);
            if (resolved == null) {
                throw new IllegalArgumentException("Unknown or inactive connector_id: " + connectorId);
            }
            return resolved;
        }

        String selectedConnectorId = selectedConnectorId(request);
        if (selectedConnectorId != null) {
            ResolvedConnectorTarget resolved = connectorRegistryService.resolveById(selectedConnectorId);
            if (resolved == null) {
                throw new IllegalArgumentException("Selected connector is unknown or inactive: " + selectedConnectorId);
            }
            return resolved;
        }

        String company = trimToNull(request.getHeader("X-Company"));
        if (company == null) {
            company = trimToNull(request.getParameter("company"));
        }
        if (company != null) {
            ResolvedConnectorTarget resolved = connectorRegistryService.resolveByCompany(company);
            if (resolved != null) {
                return resolved;
            }
        }

        if (autoRouteByClientIp) {
            String clientIp = resolveClientIp(request);
            if (clientIp != null && !isLoopback(clientIp)) {
                return new ResolvedConnectorTarget(
                        "http://" + clientIp + ":" + autoRoutePort,
                        connectorRegistryService.defaultTarget().getAgentKey(),
                        clientIp,
                        "auto:client-ip"
                );
            }
        }

        return connectorRegistryService.defaultTarget();
    }

    private String buildUrl(String baseUrl, String connectorPath, HttpServletRequest request) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl)
                .path(connectorPath.startsWith("/") ? connectorPath : "/" + connectorPath);

        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if ("connector_id".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            if ("company".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                queryParams.add(entry.getKey(), value);
            }
        }
        builder.queryParams(queryParams);
        return builder.build().encode(StandardCharsets.UTF_8).toUriString();
    }

    private HttpHeaders buildHeaders(HttpServletRequest request, HttpMethod method, ResolvedConnectorTarget target) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        String inboundAuthorization = resolveInboundAuthorization(request);
        boolean forwardedBearer = false;
        if (inboundAuthorization != null && inboundAuthorization.toLowerCase().startsWith("bearer ")) {
            headers.set("Authorization", inboundAuthorization);
            forwardedBearer = true;
        }
        if (!forwardedBearer && target.getAgentKey() != null && !target.getAgentKey().trim().isEmpty()) {
            headers.set("X-AGENT-KEY", target.getAgentKey().trim());
        }

        String xCompany = request.getHeader("X-Company");
        if (xCompany == null || xCompany.trim().isEmpty()) {
            xCompany = request.getParameter("company");
        }
        if (xCompany != null && !xCompany.trim().isEmpty()) {
            headers.set("X-Company", xCompany.trim());
        }

        String contentType = request.getContentType();
        if (contentType != null && !contentType.trim().isEmpty()) {
            headers.setContentType(MediaType.parseMediaType(contentType));
        } else if (allowsBody(method)) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }

        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            if (headerName == null) {
                continue;
            }
            String normalized = headerName.trim();
            if ("host".equalsIgnoreCase(normalized)
                    || "content-length".equalsIgnoreCase(normalized)
                    || "x-agent-key".equalsIgnoreCase(normalized)
                    || "authorization".equalsIgnoreCase(normalized)
                    || "x-company".equalsIgnoreCase(normalized)
                    || "x-connector-id".equalsIgnoreCase(normalized)
                    || "x-connector-base-url".equalsIgnoreCase(normalized)
                    || "x-connector-agent-key".equalsIgnoreCase(normalized)
                    || "content-type".equalsIgnoreCase(normalized)
                    || "accept".equalsIgnoreCase(normalized)) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(headerName);
            while (values.hasMoreElements()) {
                headers.add(headerName, values.nextElement());
            }
        }

        return headers;
    }

    private boolean allowsBody(HttpMethod method) {
        return HttpMethod.POST.equals(method)
                || HttpMethod.PUT.equals(method)
                || HttpMethod.PATCH.equals(method)
                || HttpMethod.DELETE.equals(method);
    }

    private ResponseEntity<String> normalize(ResponseEntity<String> upstream, HttpMethod method) {
        HttpStatus status = upstream.getStatusCode();
        String responseBody = upstream.getBody();
        String normalized = normalizeBody(responseBody, upstream.getHeaders().getContentType(), method);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(normalized, headers, status);
    }

    private String normalizeBody(String body, MediaType contentType, HttpMethod method) {
        if (body == null || body.trim().isEmpty()) {
            return "{}";
        }

        String trimmed = body.trim();
        try {
            if (looksLikeJson(trimmed)) {
                JsonNode jsonNode = objectMapper.readTree(trimmed);
                return objectMapper.writeValueAsString(selectNormalizer(method, jsonNode));
            }
            if (looksLikeXml(trimmed, contentType)) {
                JsonNode xmlNode = xmlMapper.readTree(trimmed.getBytes(StandardCharsets.UTF_8));
                return objectMapper.writeValueAsString(selectNormalizer(method, xmlNode));
            }
        } catch (Exception ignored) {
            // Fall through to string wrapping below so the UI still gets JSON.
        }

        try {
            return objectMapper.writeValueAsString(Collections.singletonMap("message", trimmed));
        } catch (Exception e) {
            return "{\"message\":\"upstream response could not be normalized\"}";
        }
    }

    private boolean looksLikeJson(String body) {
        return body.startsWith("{") || body.startsWith("[");
    }

    private boolean looksLikeXml(String body, MediaType contentType) {
        return body.startsWith("<")
                || (contentType != null
                && (MediaType.APPLICATION_XML.includes(contentType)
                || MediaType.TEXT_XML.includes(contentType)
                || contentType.getSubtype().toLowerCase().contains("xml")));
    }

    private JsonNode selectNormalizer(HttpMethod method, JsonNode node) {
        if (HttpMethod.GET.equals(method)) {
            return jsonShapeNormalizer.normalizeForRead(node);
        }
        return jsonShapeNormalizer.normalizeForWriteResponse(node);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = trimToNull(request.getHeader("X-Forwarded-For"));
        if (forwardedFor != null) {
            String[] parts = forwardedFor.split(",");
            for (String part : parts) {
                String candidate = trimToNull(part);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        String realIp = trimToNull(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            return realIp;
        }
        return trimToNull(request.getRemoteAddr());
    }

    private boolean isLoopback(String ip) {
        return "127.0.0.1".equals(ip)
                || "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip)
                || "localhost".equalsIgnoreCase(ip);
    }

    private String resolveInboundAuthorization(HttpServletRequest request) {
        String authHeader = trimToNull(request.getHeader("Authorization"));
        if (authHeader != null) {
            return authHeader;
        }
        javax.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (javax.servlet.http.Cookie cookie : cookies) {
            if (cookie != null && "TALLY_AUTH_TOKEN".equals(cookie.getName())) {
                String token = trimToNull(cookie.getValue());
                if (token != null) {
                    return "Bearer " + token;
                }
            }
        }
        return null;
    }

    private String selectedConnectorId(HttpServletRequest request) {
        javax.servlet.http.Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return selectedConnectorIdFromSession(request);
        }
        for (javax.servlet.http.Cookie cookie : cookies) {
            if (cookie != null && CONNECTOR_SELECTION_COOKIE.equals(cookie.getName())) {
                String value = trimToNull(cookie.getValue());
                if (value != null) {
                    return value;
                }
            }
        }
        return selectedConnectorIdFromSession(request);
    }

    private String selectedConnectorIdFromSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(CONNECTOR_SELECTION_COOKIE);
        return value == null ? null : trimToNull(String.valueOf(value));
    }

    private List<Map<String, Object>> resolutionCandidates(HttpServletRequest request) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        addCandidate(candidates, "header:base-url", trimToNull(request.getHeader("X-Connector-Base-Url")));
        addCandidate(candidates, "header/query:connector-id", firstNonBlank(
                trimToNull(request.getHeader("X-Connector-Id")),
                trimToNull(request.getParameter("connector_id"))
        ));
        addCandidate(candidates, "cookie:selected-connector", selectedConnectorId(request));
        addCandidate(candidates, "header/query:company", firstNonBlank(
                trimToNull(request.getHeader("X-Company")),
                trimToNull(request.getParameter("company"))
        ));
        if (autoRouteByClientIp) {
            String clientIp = resolveClientIp(request);
            if (clientIp != null && !isLoopback(clientIp)) {
                addCandidate(candidates, "auto:client-ip", "http://" + clientIp + ":" + autoRoutePort);
            }
        }
        ResolvedConnectorTarget defaultTarget = connectorRegistryService.defaultTarget();
        addCandidate(candidates, "default", defaultTarget == null ? null : defaultTarget.getBaseUrl());
        return candidates;
    }

    private void addCandidate(List<Map<String, Object>> candidates, String source, String value) {
        if (value == null) {
            return;
        }
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("source", source);
        candidate.put("value", value);
        candidates.add(candidate);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String text = trimToNull(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private boolean hasAdminRole(HttpServletRequest request) {
        Object roles = request.getAttribute("auth.roles");
        if (roles instanceof Iterable) {
            for (Object role : (Iterable<?>) roles) {
                if (role != null && "ADMIN".equalsIgnoreCase(String.valueOf(role).trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private ResolvedConnectorTarget safeResolveTarget(HttpServletRequest request) {
        try {
            return resolveTarget(request);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ResponseEntity<String> errorResponse(HttpStatus status,
                                                 String detail,
                                                 String reason,
                                                 String connectorBaseUrl,
                                                 String resolutionSource) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("detail", detail);
        payload.put("reason", reason);
        if (connectorBaseUrl != null) {
            payload.put("connector_base_url", connectorBaseUrl);
        }
        if (resolutionSource != null) {
            payload.put("resolution_source", resolutionSource);
        }
        try {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"detail\":\"" + detail + "\"}");
        }
    }
}
