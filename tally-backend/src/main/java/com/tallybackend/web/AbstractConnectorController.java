package com.tallybackend.web;

import com.tallybackend.cache.TallyPostWriteRefreshService;
import com.tallybackend.service.ConnectorGatewayService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import javax.servlet.http.HttpServletRequest;

abstract class AbstractConnectorController {

    private final ConnectorGatewayService connectorGatewayService;
    private final TallyPostWriteRefreshService tallyPostWriteRefreshService;

    protected AbstractConnectorController(ConnectorGatewayService connectorGatewayService,
                                          TallyPostWriteRefreshService tallyPostWriteRefreshService) {
        this.connectorGatewayService = connectorGatewayService;
        this.tallyPostWriteRefreshService = tallyPostWriteRefreshService;
    }

    protected ResponseEntity<String> get(String connectorPath, HttpServletRequest request) {
        return connectorGatewayService.forward(HttpMethod.GET, connectorPath, request, null);
    }

    protected ResponseEntity<String> post(String connectorPath, HttpServletRequest request, String body) {
        return connectorGatewayService.forward(HttpMethod.POST, connectorPath, request, body);
    }

    protected ResponseEntity<String> postAndRefresh(String connectorPath, HttpServletRequest request, String body) {
        ResponseEntity<String> response = post(connectorPath, request, body);
        triggerRefreshIfSuccessful(response);
        return response;
    }

    protected ResponseEntity<String> put(String connectorPath, HttpServletRequest request, String body) {
        return connectorGatewayService.forward(HttpMethod.PUT, connectorPath, request, body);
    }

    protected ResponseEntity<String> patch(String connectorPath, HttpServletRequest request, String body) {
        return connectorGatewayService.forward(HttpMethod.PATCH, connectorPath, request, body);
    }

    protected ResponseEntity<String> delete(String connectorPath, HttpServletRequest request, String body) {
        return connectorGatewayService.forward(HttpMethod.DELETE, connectorPath, request, body);
    }

    private void triggerRefreshIfSuccessful(ResponseEntity<String> response) {
        if (response == null) {
            return;
        }
        HttpStatus status = response.getStatusCode();
        if (status.is2xxSuccessful()) {
            tallyPostWriteRefreshService.refreshCacheAsync();
        }
    }
}
