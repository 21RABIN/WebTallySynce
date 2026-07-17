package com.tallybackend.web;

import com.tallybackend.service.ConnectorGatewayService;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/tally")
public class ConnectorProxyController {

    private final ConnectorGatewayService connectorGatewayService;

    public ConnectorProxyController(ConnectorGatewayService connectorGatewayService) {
        this.connectorGatewayService = connectorGatewayService;
    }

    @RequestMapping(
            value = {"", "/", "/**"},
            method = {
                    org.springframework.web.bind.annotation.RequestMethod.GET,
                    org.springframework.web.bind.annotation.RequestMethod.POST,
                    org.springframework.web.bind.annotation.RequestMethod.PUT,
                    org.springframework.web.bind.annotation.RequestMethod.PATCH,
                    org.springframework.web.bind.annotation.RequestMethod.DELETE
            }
    )
    public ResponseEntity<String> forward(HttpServletRequest request,
                                          @RequestBody(required = false) String body) {
        HttpMethod method = HttpMethod.resolve(request.getMethod());
        if (method == null) {
            return ResponseEntity.status(405).body("{\"message\":\"unsupported method\"}");
        }
        return connectorGatewayService.forward(method, extractConnectorPath(request), request, body);
    }

    private String extractConnectorPath(HttpServletRequest request) {
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String requestUri = request.getRequestURI();
        String prefix = contextPath + "/api/tally";
        if (!requestUri.startsWith(prefix)) {
            return "/";
        }
        String connectorPath = requestUri.substring(prefix.length());
        return connectorPath.isEmpty() ? "/" : connectorPath;
    }
}
