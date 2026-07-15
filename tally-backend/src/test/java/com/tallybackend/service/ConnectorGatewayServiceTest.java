package com.tallybackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorGatewayServiceTest {

    @Test
    void queuesConnectorBadGatewayWhenTallyIsUnavailable() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        JsonShapeNormalizer normalizer = mock(JsonShapeNormalizer.class);
        WritePayloadTransformer transformer = mock(WritePayloadTransformer.class);
        ConnectorRegistryService registryService = mock(ConnectorRegistryService.class);
        TallyVoucherWriteQueueService queueService = mock(TallyVoucherWriteQueueService.class);

        ResolvedConnectorTarget target = new ResolvedConnectorTarget("http://127.0.0.1:8082", null, "default", "default");
        when(registryService.defaultTarget()).thenReturn(target);
        when(transformer.transform(eq("/vouchers/sales"), eq(HttpMethod.POST), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(queueService.supportsQueue(eq(HttpMethod.POST), eq("/vouchers/sales"), any())).thenReturn(true);
        when(queueService.shouldQueueForUpstreamFailure(eq(HttpStatus.BAD_GATEWAY), any())).thenReturn(true);
        when(queueService.queueFromRequest(eq(HttpMethod.POST), eq("/vouchers/sales"), any(), any(), eq(target), any(), eq(HttpStatus.BAD_GATEWAY), any()))
                .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("{\"queued\":true}"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String upstreamBody = "{\"detail\":{\"error_type\":\"tally_connection_unavailable\"}}";
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway", headers, upstreamBody.getBytes(), null));

        ConnectorGatewayService service = new ConnectorGatewayService(
                restTemplate,
                new ObjectMapper(),
                normalizer,
                transformer,
                registryService,
                queueService,
                false,
                8082,
                false,
                "http://127.0.0.1:8082",
                "local-dev-key"
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setParameter("action", "Create");
        request.setParameter("company", "Ridsys");

        ResponseEntity<String> response = service.forward(HttpMethod.POST, "/vouchers/sales", request, "{\"VOUCHER\":[]}");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertTrue(response.getBody() != null && response.getBody().contains("\"queued\":true"));
        verify(queueService).queueFromRequest(eq(HttpMethod.POST), eq("/vouchers/sales"), any(), any(), eq(target), any(), eq(HttpStatus.BAD_GATEWAY), eq(upstreamBody));
    }
}
