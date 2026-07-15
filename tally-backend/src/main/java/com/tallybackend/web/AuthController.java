package com.tallybackend.web;

import com.tallybackend.service.AuthTokenService;
import com.tallybackend.service.ConnectorGatewayService;
import com.tallybackend.service.ConnectorRegistryService;
import com.tallybackend.service.ResolvedConnectorTarget;
import com.tallybackend.service.UserAccount;
import com.tallybackend.service.UserDirectoryService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
// @CrossOrigin(origins = "*" ,  maxAge = 3600) 
@RequestMapping("/api/auth")
@SecurityRequirements
public class AuthController {

    private final AuthTokenService authTokenService;
    private final ConnectorRegistryService connectorRegistryService;
    private final UserDirectoryService userDirectoryService;
    private final String cookieSecureMode;

    public AuthController(AuthTokenService authTokenService,
                          ConnectorRegistryService connectorRegistryService,
                          UserDirectoryService userDirectoryService,
                          @Value("${auth.cookie-secure-mode:auto}") String cookieSecureMode) {
        this.authTokenService = authTokenService;
        this.connectorRegistryService = connectorRegistryService;
        this.userDirectoryService = userDirectoryService;
        this.cookieSecureMode = cookieSecureMode == null ? "auto" : cookieSecureMode.trim().toLowerCase();
    }

    @PostMapping("/token")
    public ResponseEntity<?> token(@RequestBody Map<String, Object> payload,
                                   HttpServletRequest request,
                                   HttpServletResponse response) {
        String username = firstText(payload, "username", "client_id", "clientId");
        String password = firstText(payload, "password", "client_secret", "clientSecret");
        String audience = firstText(payload, "audience");
        if (audience == null) {
            audience = "tally-api";
        }

        UserAccount authenticatedUser = userDirectoryService.authenticate(username, password);
        if (authenticatedUser == null) {
            Map<String, String> error = new LinkedHashMap<>();
            error.put("detail", "invalid_credentials");
            return ResponseEntity.status(401).body(error);
        }

        ResolvedConnectorTarget resolvedConnector;
        try {
            resolvedConnector = resolveConnectorSelection(payload);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            Map<String, String> error = new LinkedHashMap<>();
            error.put("detail", ex.getMessage());
            return ResponseEntity.status(400).body(error);
        }

        Map<String, Object> extraClaims = new LinkedHashMap<>();
        extraClaims.put("name", authenticatedUser.getDisplayName());
        extraClaims.put("roles", authenticatedUser.getRoles());

        Map<String, Object> token = authTokenService.issueToken(
                authenticatedUser.getUsername(),
                audience,
                "tally-backend",
                "backend-api",
                extraClaims
        );
        String accessToken = text(token.get("access_token"));
        boolean includeToken = booleanValue(payload.get("include_token")) || booleanValue(payload.get("expose_token"));
        if (accessToken != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("TALLY_AUTH_TOKEN", accessToken, request, false));
        }
        if (resolvedConnector != null) {
            HttpSession session = request.getSession(true);
            session.setAttribute(ConnectorGatewayService.CONNECTOR_SELECTION_COOKIE, resolvedConnector.getConnectorId());
            response.addHeader(
                    HttpHeaders.SET_COOKIE,
                    buildCookie(ConnectorGatewayService.CONNECTOR_SELECTION_COOKIE, resolvedConnector.getConnectorId(), request, false)
            );
        }

        Map<String, Object> responseBody = new LinkedHashMap<>();
        responseBody.put("message", "authenticated");
        responseBody.put("token_type", token.get("token_type"));
        responseBody.put("expires_in", token.get("expires_in"));
        responseBody.put("expires_at", token.get("expires_at"));
        responseBody.put("issued_at", token.get("issued_at"));
        responseBody.put("user", publicUser(authenticatedUser));
        if (resolvedConnector != null) {
            responseBody.put("selected_connector", resolvedConnector);
        }
        if (includeToken) {
            responseBody.put("access_token", accessToken);
            responseBody.put("claims", token.get("claims"));
        }
        return ResponseEntity.ok(responseBody);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = resolveToken(request);
        if (token != null) {
            try {
                authTokenService.revokeToken(token);
            } catch (IllegalArgumentException ignored) {
                // treat malformed/expired token as already logged out
            }
        }
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("TALLY_AUTH_TOKEN", "", request, true));
        response.addHeader(HttpHeaders.SET_COOKIE,
                buildCookie(ConnectorGatewayService.CONNECTOR_SELECTION_COOKIE, "", request, true));
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ConnectorGatewayService.CONNECTOR_SELECTION_COOKIE);
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("message", "logged_out");
        return ResponseEntity.ok(result);
    }

    private String firstText(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            String value = text(payload.get(key));
            if (value != null) {
                return value;
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

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = text(value);
        if (text == null) {
            return false;
        }
        return "true".equalsIgnoreCase(text)
                || "1".equals(text)
                || "yes".equalsIgnoreCase(text)
                || "on".equalsIgnoreCase(text);
    }

    private Map<String, Object> publicUser(UserAccount user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName());
        result.put("roles", user.getRoles());
        return result;
    }

    private ResolvedConnectorTarget resolveConnectorSelection(Map<String, Object> payload) {
        String connectorId = firstText(payload, "connector_id", "connectorId");
        String company = firstText(payload, "company");
        String branch = firstText(payload, "branch");
        if (connectorId == null && company == null && branch == null) {
            return null;
        }
        ResolvedConnectorTarget resolved = connectorRegistryService.resolveBySelection(connectorId, company, branch);
        if (resolved == null) {
            throw new IllegalArgumentException("Connector selection not found for connector_id/company/branch");
        }
        return resolved;
    }

    private String buildCookie(String name, String value, HttpServletRequest request, boolean expireNow) {
        StringBuilder cookie = new StringBuilder();
        cookie.append(name).append("=").append(value == null ? "" : value);
        cookie.append("; Path=/; HttpOnly; SameSite=Lax");
        if (shouldUseSecureCookies(request)) {
            cookie.append("; Secure");
        }
        if (expireNow) {
            cookie.append("; Max-Age=0");
        }
        return cookie.toString();
    }

    private boolean shouldUseSecureCookies(HttpServletRequest request) {
        if ("true".equals(cookieSecureMode) || "always".equals(cookieSecureMode)) {
            return true;
        }
        if ("false".equals(cookieSecureMode) || "never".equals(cookieSecureMode)) {
            return false;
        }
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return forwardedProto != null && "https".equalsIgnoreCase(forwardedProto.trim());
    }

    private String resolveToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.toLowerCase().startsWith("bearer ")) {
            String token = authHeader.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie != null && "TALLY_AUTH_TOKEN".equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().trim().isEmpty()) {
                return cookie.getValue().trim();
            }
        }
        return null;
    }
}
