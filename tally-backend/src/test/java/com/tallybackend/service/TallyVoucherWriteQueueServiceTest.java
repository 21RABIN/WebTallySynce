package com.tallybackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tallybackend.cache.TallyCacheSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TallyVoucherWriteQueueServiceTest {

    private TallyVoucherWriteQueueRepository repository;
    private TallyVoucherWriteQueueService service;

    @BeforeEach
    void setUp() {
        repository = mock(TallyVoucherWriteQueueRepository.class);
        service = createService(repository);
    }

    @Test
    void cleanupDefaultsToFailedStatus() {
        when(repository.deleteMatching(eq(Collections.singletonList("FAILED")), eq("Ridsys"), isNull(), isNull(), isNull()))
                .thenReturn(1);

        Map<String, Object> response = service.cleanup("Ridsys", null, null, null, null);

        assertEquals(1, response.get("deleted"));
        assertEquals(Collections.singletonList("FAILED"), response.get("statuses"));
        verify(repository).deleteMatching(Collections.singletonList("FAILED"), "Ridsys", null, null, null);
    }

    @Test
    void cleanupAllowsFailedAndRetryStatusesWithAgeFilter() {
        when(repository.deleteMatching(eq(Arrays.asList("FAILED", "RETRY")), eq("Ridsys"), eq("/vouchers/purchase-orders"), eq("http://127.0.0.1:65530"), eq(null)))
                .thenReturn(0);

        service.cleanup("Ridsys", "/vouchers/purchase-orders", "http://127.0.0.1:65530", "failed,retry", 120);

        verify(repository).deleteMatching(
                eq(Arrays.asList("FAILED", "RETRY")),
                eq("Ridsys"),
                eq("/vouchers/purchase-orders"),
                eq("http://127.0.0.1:65530"),
                org.mockito.ArgumentMatchers.any(Instant.class)
        );
    }

    @Test
    void cleanupRejectsAppliedStatus() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.cleanup(null, null, null, "APPLIED", null));

        assertEquals("Cleanup only supports FAILED and RETRY statuses.", error.getMessage());
    }

    @Test
    void queueFromRequestMarksEntriesPendingReview() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("action")).thenReturn("Create");
        when(request.getParameter("company")).thenReturn("Ridsys");
        when(request.getHeader("X-Company")).thenReturn(null);
        when(request.getAttribute("auth.subject")).thenReturn("tester");
        when(request.getContentType()).thenReturn("application/json");
        when(request.getParameterMap()).thenReturn(Collections.singletonMap("company", new String[]{"Ridsys"}));
        when(repository.insert(any(TallyVoucherWriteQueueEntry.class))).thenReturn(101L);

        service.queueFromRequest(
                HttpMethod.POST,
                "/ledgers",
                request,
                "{\"NAME\":\"Demo Ledger\"}",
                new ResolvedConnectorTarget("http://127.0.0.1:8082", null, "default", "test"),
                "offline",
                HttpStatus.BAD_GATEWAY,
                "{\"error_type\":\"tally_connection_unavailable\"}"
        );

        ArgumentCaptor<TallyVoucherWriteQueueEntry> captor = ArgumentCaptor.forClass(TallyVoucherWriteQueueEntry.class);
        verify(repository).insert(captor.capture());
        TallyVoucherWriteQueueEntry entry = captor.getValue();
        assertEquals("QUEUED", entry.getStatus());
        assertEquals("PENDING_REVIEW", entry.getReviewState());
        assertEquals("NONE", entry.getConflictState());
        assertNotNull(entry.getNextAttemptAt());
    }

    @Test
    void queueFromRequestKeepsBusinessVoucherNumberInQueuedPayload() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("action")).thenReturn("Create");
        when(request.getParameter("company")).thenReturn("MR TECH");
        when(request.getHeader("X-Company")).thenReturn(null);
        when(request.getAttribute("auth.subject")).thenReturn("tester");
        when(request.getContentType()).thenReturn("application/json");
        when(request.getParameterMap()).thenReturn(Collections.singletonMap("company", new String[]{"MR TECH"}));
        when(repository.insert(any(TallyVoucherWriteQueueEntry.class))).thenReturn(102L);

        service.queueFromRequest(
                HttpMethod.POST,
                "/vouchers/sales",
                request,
                "{\"VOUCHER\":[{\"COMPANY\":\"MR TECH\",\"DATE\":\"20260402\",\"VOUCHERTYPENAME\":\"Sales\",\"VOUCHERNUMBER\":\"102\",\"REFERENCE\":\"102\"}]}",
                new ResolvedConnectorTarget("http://127.0.0.1:8082", null, "default", "test"),
                "offline",
                HttpStatus.BAD_GATEWAY,
                "{\"error_type\":\"tally_connection_unavailable\"}"
        );

        ArgumentCaptor<TallyVoucherWriteQueueEntry> captor = ArgumentCaptor.forClass(TallyVoucherWriteQueueEntry.class);
        verify(repository).insert(captor.capture());
        TallyVoucherWriteQueueEntry entry = captor.getValue();
        Map<?, ?> payload = new ObjectMapper().readValue(entry.getRequestBody(), Map.class);
        Map<?, ?> voucher = (Map<?, ?>) ((java.util.List<?>) payload.get("VOUCHER")).get(0);
        assertEquals("102", voucher.get("VOUCHERNUMBER"));
        assertEquals("102", entry.getOriginalVoucherNumber());
        assertNotNull(entry.getOfflineVoucherNumber());
        assertTrue(String.valueOf(entry.getOfflineVoucherNumber()).startsWith("OFF-"));
        assertFalse(String.valueOf(voucher.get("VOUCHERNUMBER")).startsWith("OFF-"));
    }

    @Test
    void assertVoucherNumberAvailableRejectsDuplicateVoucherNumber() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Company")).thenReturn("MR TECH");
        when(request.getParameter("company")).thenReturn("MR TECH");

        TallyVoucherWriteQueueEntry existing = new TallyVoucherWriteQueueEntry();
        existing.setId(34L);
        existing.setStatus("APPLIED");
        existing.setReviewState("SYNCED");
        when(repository.findVoucherNumberDuplicate("MR TECH", "/vouchers/sales", "101"))
                .thenReturn(existing);

        DuplicateVoucherNumberException error = assertThrows(
                DuplicateVoucherNumberException.class,
                () -> service.assertVoucherNumberAvailable(
                        HttpMethod.POST,
                        "/vouchers/sales",
                        request,
                        "{\"VOUCHER\":[{\"COMPANY\":\"MR TECH\",\"DATE\":\"20260402\",\"VOUCHERTYPENAME\":\"Sales\",\"VOUCHERNUMBER\":\"101\",\"REFERENCE\":\"101\"}]}"
                )
        );

        assertEquals("MR TECH", error.getCompany());
        assertEquals("/vouchers/sales", error.getConnectorPath());
        assertEquals("101", error.getVoucherNumber());
        assertEquals(34L, error.getExistingQueueId());
    }

    @Test
    void recordAppliedRequestMarksEntriesSynced() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("action")).thenReturn("Create");
        when(request.getParameter("company")).thenReturn("Ridsys");
        when(request.getHeader("X-Company")).thenReturn(null);
        when(request.getAttribute("auth.subject")).thenReturn("tester");
        when(request.getContentType()).thenReturn("application/json");
        when(request.getParameterMap()).thenReturn(Collections.singletonMap("company", new String[]{"Ridsys"}));

        service.recordAppliedRequest(
                HttpMethod.POST,
                "/ledgers",
                request,
                "{\"NAME\":\"Live Ledger\"}",
                new ResolvedConnectorTarget("http://127.0.0.1:8082", null, "default", "test"),
                "{\"status\":\"ok\"}"
        );

        ArgumentCaptor<TallyVoucherWriteQueueEntry> captor = ArgumentCaptor.forClass(TallyVoucherWriteQueueEntry.class);
        verify(repository, times(1)).insert(captor.capture());
        TallyVoucherWriteQueueEntry entry = captor.getValue();
        assertEquals("APPLIED", entry.getStatus());
        assertEquals("SYNCED", entry.getReviewState());
        assertEquals("NONE", entry.getConflictState());
        assertNotNull(entry.getCompletedAt());
    }

    @Test
    void shouldQueueForNestedTallyUnavailableDetailPayload() {
        boolean result = service.shouldQueueForUpstreamFailure(
                HttpStatus.BAD_GATEWAY,
                "{\"detail\":{\"error_type\":\"tally_connection_unavailable\",\"reason\":\"TallyPrime is not accepting XML requests on http://127.0.0.1:9000 right now.\"}}"
        );

        assertTrue(result);
    }

    private TallyVoucherWriteQueueService createService(TallyVoucherWriteQueueRepository repository) {
        @SuppressWarnings("unchecked")
        ObjectProvider<TallyCacheSyncService> cacheProvider = mock(ObjectProvider.class);
        return new TallyVoucherWriteQueueService(
                mock(RestTemplate.class),
                new ObjectMapper(),
                mock(WritePayloadTransformer.class),
                repository,
                mock(TallyQueueEntityMetadataService.class),
                cacheProvider,
                true,
                false,
                60000L,
                180L,
                10,
                -1
        );
    }
}
