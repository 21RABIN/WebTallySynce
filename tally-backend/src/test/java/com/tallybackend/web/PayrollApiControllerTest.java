package com.tallybackend.web;

import com.tallybackend.service.ConnectorGatewayService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import javax.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayrollApiControllerTest {

    @Test
    void employeesRouteForwardsToConnectorEmployeesPath() {
        ConnectorGatewayService gatewayService = mock(ConnectorGatewayService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(gatewayService.forward(HttpMethod.GET, "/employees", request, null))
                .thenReturn(ResponseEntity.ok("{}"));

        PayrollApiController controller = new PayrollApiController(gatewayService);
        ResponseEntity<String> response = controller.employees(request);

        assertEquals(200, response.getStatusCodeValue());
        verify(gatewayService).forward(HttpMethod.GET, "/employees", request, null);
    }
}
