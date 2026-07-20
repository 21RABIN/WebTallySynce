package com.tallybackend.config;

import com.tallybackend.service.AuthTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

@Component
public class AuthFilter extends OncePerRequestFilter {

    private final AuthTokenService authTokenService;

    public AuthFilter(AuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = pathWithinApplication(request);
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || path == null
                || path.startsWith("/api/health")
                || path.startsWith("/api/auth/token")
                || isConnectorAgentEndpoint(request, path)
                || ("GET".equalsIgnoreCase(request.getMethod()) && path.startsWith("/api/cache/masters"))
                || ("GET".equalsIgnoreCase(request.getMethod()) && path.startsWith("/api/cache/status"))
                || ("GET".equalsIgnoreCase(request.getMethod()) && path.startsWith("/api/voucher-queue"))
                || path.startsWith("/v3/api-docs")
                || path.equals("/swagger")
                || path.equals("/swagger/")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/swagger-ui.html");
    }

    private boolean isConnectorAgentEndpoint(HttpServletRequest request, String path) {
        if (path == null) {
            return false;
        }
        String method = request.getMethod();
        return ("POST".equalsIgnoreCase(method) && path.equals("/api/connectors/register"))
                || ("POST".equalsIgnoreCase(method) && path.equals("/api/connectors/heartbeat"))
                || ("GET".equalsIgnoreCase(method) && path.equals("/api/connector/jobs"))
                || ("POST".equalsIgnoreCase(method) && path.startsWith("/api/connector/jobs/") && path.endsWith("/result"))
                || ("POST".equalsIgnoreCase(method) && path.equals("/api/connector/snapshots"));
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }

        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            String trimmed = path.substring(contextPath.length());
            return trimmed.isEmpty() ? "/" : trimmed;
        }
        return path;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authHeader = trimToNull(request.getHeader("Authorization"));
        if (authHeader == null) {
            String cookieToken = trimToNull(cookieValue(request, "TALLY_AUTH_TOKEN"));
            if (cookieToken != null) {
                authHeader = "Bearer " + cookieToken;
            }
        }
        if (authHeader != null && authHeader.toLowerCase().startsWith("bearer ")) {
            String token = authHeader.substring(7).trim();
            try {
                Map<String, Object> claims = authTokenService.verifyToken(token, "tally-api");
                request.setAttribute("auth.claims", claims);
                request.setAttribute("auth.subject", claims.get("sub"));
                request.setAttribute("auth.roles", claims.get("roles"));
                filterChain.doFilter(request, response);
                return;
            } catch (IllegalArgumentException ex) {
                unauthorized(response, "unauthorized: " + ex.getMessage());
                return;
            }
        }

        String staticKey = trimToNull(request.getHeader("X-AGENT-KEY"));
        if (authTokenService.acceptsStaticAgentKey(staticKey)) {
            filterChain.doFilter(request, response);
            return;
        }

        unauthorized(response, "unauthorized");
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"detail\":\"" + message.replace("\"", "\\\"") + "\"}");
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookie != null && name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
