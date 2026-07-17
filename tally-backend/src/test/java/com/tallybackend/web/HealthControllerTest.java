package com.tallybackend.web;

import com.tallybackend.service.ConnectorStatusService;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {

    @Test
    void capabilitiesWrapsBackendAndConnectorPayloads() {
        ConnectorStatusService connectorStatusService = mock(ConnectorStatusService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(connectorStatusService.connectorCapabilities(request)).thenReturn(Collections.singletonMap("status", "ok"));

        HealthController controller = new HealthController(connectorStatusService);
        Map<String, Object> body = controller.capabilities(request).getBody();

        assertNotNull(body);
        assertEquals("ok", body.get("status"));
        assertTrue(body.containsKey("backend"));
        assertTrue(body.containsKey("connector"));
    }
}
