package com.tallybackend.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tallybackend.service.TallyVoucherWriteQueueEntry;
import com.tallybackend.service.TallyVoucherWriteQueueRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TallyCacheReadServiceTest {

    @Test
    void returnsFreshCachedMasterRowsWithoutLiveFallback() {
        TallyCacheProperties properties = mock(TallyCacheProperties.class);
        TallyCacheSnapshotRepository snapshotRepository = mock(TallyCacheSnapshotRepository.class);
        TallyCacheRowRepository rowRepository = mock(TallyCacheRowRepository.class);
        TallyCacheConnectorClient connectorClient = mock(TallyCacheConnectorClient.class);
        TallyCacheNormalizer normalizer = mock(TallyCacheNormalizer.class);
        TallyCacheSyncService syncService = mock(TallyCacheSyncService.class);
        TallyVoucherWriteQueueRepository queueRepository = mock(TallyVoucherWriteQueueRepository.class);

        when(properties.getCompany()).thenReturn("Demo Company");

        TallyDatasetSnapshot snapshot = new TallyDatasetSnapshot();
        snapshot.setId(7L);
        snapshot.setStatus("SUCCESS");
        snapshot.setConnectorId("local");
        snapshot.setCompany("Demo Company");
        snapshot.setFetchedAt(Instant.now());
        snapshot.setStaleAfterMs(300000L);

        List<Map<String, Object>> rows = Collections.<Map<String, Object>>singletonList(Collections.<String, Object>singletonMap("NAME", "Cash"));

        when(snapshotRepository.findCurrent(TallyCacheDataset.LEDGERS, "default")).thenReturn(snapshot);
        when(rowRepository.readRows(TallyCacheDataset.LEDGERS, 7L)).thenReturn(rows);

        TallyCacheReadService service = new TallyCacheReadService(properties, snapshotRepository, rowRepository, connectorClient, normalizer, syncService, queueRepository, new ObjectMapper());
        Map<String, Object> response = service.ledgers();

        assertEquals(rows, response.get("data"));
        assertEquals("cache", ((Map<?, ?>) response.get("meta")).get("source"));
        assertFalse(Boolean.TRUE.equals(((Map<?, ?>) response.get("meta")).get("fallback_used")));
        verify(connectorClient, never()).fetch(any(String.class), any(Map.class));
    }

    @Test
    void returnsStaleCachedDayBookWhenLiveFallbackFails() {
        TallyCacheProperties properties = mock(TallyCacheProperties.class);
        TallyCacheSnapshotRepository snapshotRepository = mock(TallyCacheSnapshotRepository.class);
        TallyCacheRowRepository rowRepository = mock(TallyCacheRowRepository.class);
        TallyCacheConnectorClient connectorClient = mock(TallyCacheConnectorClient.class);
        TallyCacheNormalizer normalizer = mock(TallyCacheNormalizer.class);
        TallyCacheSyncService syncService = mock(TallyCacheSyncService.class);
        TallyVoucherWriteQueueRepository queueRepository = mock(TallyVoucherWriteQueueRepository.class);

        when(properties.getCompany()).thenReturn("Demo Company");

        TallyDatasetSnapshot snapshot = new TallyDatasetSnapshot();
        snapshot.setId(11L);
        snapshot.setStatus("SUCCESS");
        snapshot.setConnectorId("local");
        snapshot.setCompany("Demo Company");
        snapshot.setFetchedAt(Instant.now().minusMillis(600000L));
        snapshot.setStaleAfterMs(300000L);
        snapshot.setRangeStart("20260401");
        snapshot.setRangeEnd("20260630");

        List<Map<String, Object>> rows = Arrays.<Map<String, Object>>asList(Collections.<String, Object>singletonMap("voucherType", "Sales"));

        when(snapshotRepository.findCurrent(TallyCacheDataset.DAY_BOOK, "from=20260401&to=20260611")).thenReturn(snapshot);
        when(rowRepository.readRows(TallyCacheDataset.DAY_BOOK, 11L)).thenReturn(rows);
        when(connectorClient.fetch(eq("/reports/day-book"), any(Map.class))).thenThrow(new RuntimeException("connector down"));

        TallyCacheReadService service = new TallyCacheReadService(properties, snapshotRepository, rowRepository, connectorClient, normalizer, syncService, queueRepository, new ObjectMapper());
        Map<String, Object> response = service.dayBook("20260401", "20260611");

        assertEquals(rows, response.get("data"));
        assertEquals("stale-cache", ((Map<?, ?>) response.get("meta")).get("source"));
    }

    @Test
    void returnsPreviousNonEmptySnapshotWhenCurrentCacheIsEmpty() {
        TallyCacheProperties properties = mock(TallyCacheProperties.class);
        TallyCacheSnapshotRepository snapshotRepository = mock(TallyCacheSnapshotRepository.class);
        TallyCacheRowRepository rowRepository = mock(TallyCacheRowRepository.class);
        TallyCacheConnectorClient connectorClient = mock(TallyCacheConnectorClient.class);
        TallyCacheNormalizer normalizer = mock(TallyCacheNormalizer.class);
        TallyCacheSyncService syncService = mock(TallyCacheSyncService.class);
        TallyVoucherWriteQueueRepository queueRepository = mock(TallyVoucherWriteQueueRepository.class);

        when(properties.getCompany()).thenReturn("Demo Company");

        TallyDatasetSnapshot currentSnapshot = new TallyDatasetSnapshot();
        currentSnapshot.setId(21L);
        currentSnapshot.setStatus("SUCCESS");
        currentSnapshot.setConnectorId("local");
        currentSnapshot.setCompany("Demo Company");
        currentSnapshot.setFetchedAt(Instant.now());
        currentSnapshot.setStaleAfterMs(300000L);
        currentSnapshot.setSnapshotKey("default");
        currentSnapshot.setRowCount(0);

        TallyDatasetSnapshot fallbackSnapshot = new TallyDatasetSnapshot();
        fallbackSnapshot.setId(17L);
        fallbackSnapshot.setStatus("SUCCESS");
        fallbackSnapshot.setConnectorId("local");
        fallbackSnapshot.setCompany("Demo Company");
        fallbackSnapshot.setFetchedAt(Instant.now().minusSeconds(30));
        fallbackSnapshot.setStaleAfterMs(300000L);
        fallbackSnapshot.setSnapshotKey("default");
        fallbackSnapshot.setRowCount(3);

        List<Map<String, Object>> fallbackRows = Collections.<Map<String, Object>>singletonList(Collections.<String, Object>singletonMap("NAME", "Sundry Debtors"));

        when(snapshotRepository.findCurrent(TallyCacheDataset.GROUPS, "default")).thenReturn(currentSnapshot);
        when(rowRepository.readRows(TallyCacheDataset.GROUPS, 21L)).thenReturn(Collections.<Map<String, Object>>emptyList());
        when(snapshotRepository.findLatestSuccessfulNonEmpty(TallyCacheDataset.GROUPS, "default")).thenReturn(fallbackSnapshot);
        when(rowRepository.readRows(TallyCacheDataset.GROUPS, 17L)).thenReturn(fallbackRows);

        TallyCacheReadService service = new TallyCacheReadService(properties, snapshotRepository, rowRepository, connectorClient, normalizer, syncService, queueRepository, new ObjectMapper());
        Map<String, Object> response = service.groups();

        assertEquals(fallbackRows, response.get("data"));
        assertEquals("cache", ((Map<?, ?>) response.get("meta")).get("source"));
        assertFalse(Boolean.TRUE.equals(((Map<?, ?>) response.get("meta")).get("fallback_used")));
        verify(connectorClient, never()).fetch(any(String.class), any(Map.class));
    }

    @Test
    void appendsQueuedStockItemsToCachedRegistry() {
        TallyCacheProperties properties = mock(TallyCacheProperties.class);
        TallyCacheSnapshotRepository snapshotRepository = mock(TallyCacheSnapshotRepository.class);
        TallyCacheRowRepository rowRepository = mock(TallyCacheRowRepository.class);
        TallyCacheConnectorClient connectorClient = mock(TallyCacheConnectorClient.class);
        TallyCacheNormalizer normalizer = mock(TallyCacheNormalizer.class);
        TallyCacheSyncService syncService = mock(TallyCacheSyncService.class);
        TallyVoucherWriteQueueRepository queueRepository = mock(TallyVoucherWriteQueueRepository.class);

        TallyDatasetSnapshot snapshot = new TallyDatasetSnapshot();
        snapshot.setId(31L);
        snapshot.setStatus("SUCCESS");
        snapshot.setFetchedAt(Instant.now());
        snapshot.setStaleAfterMs(300000L);

        List<Map<String, Object>> cachedRows = Collections.<Map<String, Object>>singletonList(Collections.<String, Object>singletonMap("NAME", "Apples"));
        TallyVoucherWriteQueueEntry queuedEntry = new TallyVoucherWriteQueueEntry();
        queuedEntry.setId(91L);
        queuedEntry.setStatus("QUEUED");
        queuedEntry.setRequestBody("{\"NAME\":\"Mobile\",\"PARENT\":\"ElectronicsA\",\"BASEUNITS\":\"PCS\",\"GSTAPPLICABLE\":\"Applicable\",\"QUANTITY\":2,\"RATEPER\":5000,\"OPENINGVALUE\":\"10000.00\"}");

        when(snapshotRepository.findCurrent(TallyCacheDataset.STOCK_ITEMS, "default")).thenReturn(snapshot);
        when(rowRepository.readRows(TallyCacheDataset.STOCK_ITEMS, 31L)).thenReturn(cachedRows);
        when(queueRepository.listVisibleByConnectorPath(eq("/stock-items"), eq(500), any(Instant.class))).thenReturn(Collections.singletonList(queuedEntry));

        TallyCacheReadService service = new TallyCacheReadService(properties, snapshotRepository, rowRepository, connectorClient, normalizer, syncService, queueRepository, new ObjectMapper());
        Map<String, Object> response = service.stockItems();

        List<?> responseRows = (List<?>) response.get("data");
        assertEquals(2, responseRows.size());
        Map<?, ?> queuedRow = (Map<?, ?>) responseRows.get(1);
        assertEquals("Mobile", queuedRow.get("NAME"));
        assertEquals(Boolean.TRUE, queuedRow.get("pending_sync"));
        assertEquals(1, ((Map<?, ?>) response.get("meta")).get("pending_queue_count"));
    }

    @Test
    void appendsQueuedGroupsToCachedRegistry() {
        TallyCacheProperties properties = mock(TallyCacheProperties.class);
        TallyCacheSnapshotRepository snapshotRepository = mock(TallyCacheSnapshotRepository.class);
        TallyCacheRowRepository rowRepository = mock(TallyCacheRowRepository.class);
        TallyCacheConnectorClient connectorClient = mock(TallyCacheConnectorClient.class);
        TallyCacheNormalizer normalizer = mock(TallyCacheNormalizer.class);
        TallyCacheSyncService syncService = mock(TallyCacheSyncService.class);
        TallyVoucherWriteQueueRepository queueRepository = mock(TallyVoucherWriteQueueRepository.class);

        TallyDatasetSnapshot snapshot = new TallyDatasetSnapshot();
        snapshot.setId(41L);
        snapshot.setStatus("SUCCESS");
        snapshot.setFetchedAt(Instant.now());
        snapshot.setStaleAfterMs(300000L);

        List<Map<String, Object>> cachedRows = Collections.<Map<String, Object>>singletonList(Collections.<String, Object>singletonMap("NAME", "Primary"));
        TallyVoucherWriteQueueEntry queuedEntry = new TallyVoucherWriteQueueEntry();
        queuedEntry.setId(92L);
        queuedEntry.setStatus("QUEUED");
        queuedEntry.setRequestBody("{\"NAME\":\"Retail Debtors\",\"PARENT\":\"Sundry Debtors\"}");

        when(snapshotRepository.findCurrent(TallyCacheDataset.GROUPS, "default")).thenReturn(snapshot);
        when(rowRepository.readRows(TallyCacheDataset.GROUPS, 41L)).thenReturn(cachedRows);
        when(queueRepository.listVisibleByConnectorPath(eq("/groups"), eq(500), any(Instant.class))).thenReturn(Collections.singletonList(queuedEntry));

        TallyCacheReadService service = new TallyCacheReadService(properties, snapshotRepository, rowRepository, connectorClient, normalizer, syncService, queueRepository, new ObjectMapper());
        Map<String, Object> response = service.groups();

        List<?> responseRows = (List<?>) response.get("data");
        assertEquals(2, responseRows.size());
        Map<?, ?> queuedRow = (Map<?, ?>) responseRows.get(1);
        assertEquals("Retail Debtors", queuedRow.get("NAME"));
        assertEquals("Sundry Debtors", queuedRow.get("PARENT"));
        assertEquals(Boolean.TRUE, queuedRow.get("pending_sync"));
        assertEquals(1, ((Map<?, ?>) response.get("meta")).get("pending_queue_count"));
    }

    @Test
    void overlaysLatestAppliedCompanyFeaturesImmediately() {
        TallyCacheProperties properties = mock(TallyCacheProperties.class);
        TallyCacheSnapshotRepository snapshotRepository = mock(TallyCacheSnapshotRepository.class);
        TallyCacheRowRepository rowRepository = mock(TallyCacheRowRepository.class);
        TallyCacheConnectorClient connectorClient = mock(TallyCacheConnectorClient.class);
        TallyCacheNormalizer normalizer = mock(TallyCacheNormalizer.class);
        TallyCacheSyncService syncService = mock(TallyCacheSyncService.class);
        TallyVoucherWriteQueueRepository queueRepository = mock(TallyVoucherWriteQueueRepository.class);

        when(properties.getIntervalMs()).thenReturn(60000L);

        TallyDatasetSnapshot snapshot = new TallyDatasetSnapshot();
        snapshot.setId(51L);
        snapshot.setStatus("SUCCESS");
        snapshot.setFetchedAt(Instant.now());
        snapshot.setStaleAfterMs(300000L);

        List<Map<String, Object>> cachedRows = Collections.<Map<String, Object>>singletonList(Collections.<String, Object>singletonMap("NAME", "Software Development"));
        TallyVoucherWriteQueueEntry appliedEntry = new TallyVoucherWriteQueueEntry();
        appliedEntry.setId(93L);
        appliedEntry.setStatus("QUEUED");
        appliedEntry.setRequestBody("{\"NAME\":\"Software Development\",\"EMAIL\":\"fastsync@example.com\",\"PINCODE\":\"600001\",\"COUNTRYNAME\":\"India\",\"STATENAME\":\"Tamil Nadu\",\"ISINVENTORYON\":\"Yes\",\"ISGSTON\":\"Yes\"}");

        when(snapshotRepository.findCurrent(TallyCacheDataset.COMPANY_FEATURES, "default")).thenReturn(snapshot);
        when(rowRepository.readRows(TallyCacheDataset.COMPANY_FEATURES, 51L)).thenReturn(cachedRows);
        when(queueRepository.listVisibleByConnectorPath(eq("/settings/company-features"), eq(20), any(Instant.class)))
                .thenReturn(Collections.singletonList(appliedEntry));

        TallyCacheReadService service = new TallyCacheReadService(properties, snapshotRepository, rowRepository, connectorClient, normalizer, syncService, queueRepository, new ObjectMapper());
        Map<String, Object> response = service.companyFeatures();

        List<?> responseRows = (List<?>) response.get("data");
        assertEquals(1, responseRows.size());
        Map<?, ?> overlayRow = (Map<?, ?>) responseRows.get(0);
        assertEquals("fastsync@example.com", overlayRow.get("EMAIL"));
        assertEquals("QUEUED", overlayRow.get("queue_status"));
        assertEquals(1, ((Map<?, ?>) response.get("meta")).get("pending_queue_count"));
    }

    @Test
    void queuedDayBookOverlayIncludesFullVoucherPayloadForOfflineSalesVoucher() {
        TallyCacheProperties properties = mock(TallyCacheProperties.class);
        TallyCacheSnapshotRepository snapshotRepository = mock(TallyCacheSnapshotRepository.class);
        TallyCacheRowRepository rowRepository = mock(TallyCacheRowRepository.class);
        TallyCacheConnectorClient connectorClient = mock(TallyCacheConnectorClient.class);
        TallyCacheNormalizer normalizer = mock(TallyCacheNormalizer.class);
        TallyCacheSyncService syncService = mock(TallyCacheSyncService.class);
        TallyVoucherWriteQueueRepository queueRepository = mock(TallyVoucherWriteQueueRepository.class);

        when(properties.getCompany()).thenReturn("MR TECH");
        when(properties.getStaleAfterMs()).thenReturn(300000L);

        TallyDatasetSnapshot snapshot = new TallyDatasetSnapshot();
        snapshot.setId(61L);
        snapshot.setStatus("SUCCESS");
        snapshot.setCompany("MR TECH");
        snapshot.setFetchedAt(Instant.now());
        snapshot.setStaleAfterMs(300000L);
        snapshot.setSnapshotKey("from=20260401&to=20260715");
        snapshot.setRangeStart("20260401");
        snapshot.setRangeEnd("20260715");

        when(snapshotRepository.findCurrent(TallyCacheDataset.DAY_BOOK, "from=20260401&to=20260715"))
                .thenReturn(snapshot);
        when(rowRepository.readRows(TallyCacheDataset.DAY_BOOK, 61L)).thenReturn(Collections.<Map<String, Object>>emptyList());

        TallyVoucherWriteQueueEntry queuedEntry = new TallyVoucherWriteQueueEntry();
        queuedEntry.setId(94L);
        queuedEntry.setCompany("MR TECH");
        queuedEntry.setConnectorPath("/vouchers/sales");
        queuedEntry.setStatus("QUEUED");
        queuedEntry.setOriginalVoucherNumber("9");
        queuedEntry.setOfflineVoucherNumber("OFF-SAL-20260402-569620");
        queuedEntry.setRequestBody("{\"VOUCHER\":[{\"COMPANY\":\"MR TECH\",\"DATE\":\"20260402\",\"VOUCHERTYPENAME\":\"Sales\",\"VOUCHERNUMBER\":\"OFF-SAL-20260402-569620\",\"PARTYLEDGERNAME\":\"KARUPPU Customer\",\"NARRATION\":\"Created from tally-ui\",\"ALLINVENTORYENTRIES.LIST\":[{\"STOCKITEMNAME\":\"Mobile\",\"ACTUALQTY\":\"1 PCS\",\"BILLEDQTY\":\"1 PCS\",\"RATE\":\"2000.00/PCS\",\"AMOUNT\":\"2000.00\",\"ACCOUNTINGALLOCATIONS.LIST\":[{\"LEDGERNAME\":\"Sales A/C\",\"AMOUNT\":\"2000.00\"}]}],\"ALLLEDGERENTRIES.LIST\":[{\"LEDGERNAME\":\"KARUPPU Customer\",\"AMOUNT\":\"2360.00\",\"ISPARTYLEDGER\":\"Yes\"},{\"LEDGERNAME\":\"Sales A/C\",\"AMOUNT\":\"-2000.00\",\"ISDEEMEDPOSITIVE\":\"Yes\"},{\"LEDGERNAME\":\"Output CGST/SGST\",\"AMOUNT\":\"-360.00\",\"ISDEEMEDPOSITIVE\":\"Yes\"}]}]}");

        when(queueRepository.listVisibleByConnectorPath(eq("/vouchers/sales"), eq(100), any(Instant.class)))
                .thenReturn(Collections.singletonList(queuedEntry));
        when(queueRepository.listVisibleByConnectorPath(eq("/vouchers/purchase"), eq(100), any(Instant.class)))
                .thenReturn(Collections.<TallyVoucherWriteQueueEntry>emptyList());
        when(queueRepository.listVisibleByConnectorPath(eq("/vouchers/journal"), eq(100), any(Instant.class)))
                .thenReturn(Collections.<TallyVoucherWriteQueueEntry>emptyList());
        when(queueRepository.listVisibleByConnectorPath(eq("/vouchers/payment"), eq(100), any(Instant.class)))
                .thenReturn(Collections.<TallyVoucherWriteQueueEntry>emptyList());
        when(queueRepository.listVisibleByConnectorPath(eq("/vouchers/receipt"), eq(100), any(Instant.class)))
                .thenReturn(Collections.<TallyVoucherWriteQueueEntry>emptyList());
        when(queueRepository.listVisibleByConnectorPath(eq("/vouchers/contra"), eq(100), any(Instant.class)))
                .thenReturn(Collections.<TallyVoucherWriteQueueEntry>emptyList());

        TallyCacheReadService service = new TallyCacheReadService(properties, snapshotRepository, rowRepository, connectorClient, normalizer, syncService, queueRepository, new ObjectMapper());
        Map<String, Object> response = service.dayBook("20260401", "20260715");

        List<?> rows = (List<?>) response.get("data");
        assertEquals(1, rows.size());
        Map<?, ?> queuedRow = (Map<?, ?>) rows.get(0);
        assertEquals("9", queuedRow.get("voucherNumber"));
        assertEquals("9", queuedRow.get("originalVoucherNumber"));
        assertEquals("OFF-SAL-20260402-569620", queuedRow.get("offlineVoucherNumber"));
        assertEquals("KARUPPU Customer", queuedRow.get("partyLedger"));
        assertEquals(Boolean.TRUE, queuedRow.get("pending_sync"));
        assertNotNull(queuedRow.get("payloadJson"));
        String payloadJson = String.valueOf(queuedRow.get("payloadJson"));
        assertTrue(payloadJson.contains("\"PARTYLEDGERNAME\":\"KARUPPU Customer\""));
        assertTrue(payloadJson.contains("\"LEDGERNAME\":\"Sales A/C\""));
        assertTrue(payloadJson.contains("\"LEDGERNAME\":\"Output CGST/SGST\""));
    }

    @Test
    void queuedDayBookOverlayPrefersReferenceWhenLegacyOfflineVoucherLacksOriginalNumber() {
        TallyCacheProperties properties = mock(TallyCacheProperties.class);
        TallyCacheSnapshotRepository snapshotRepository = mock(TallyCacheSnapshotRepository.class);
        TallyCacheRowRepository rowRepository = mock(TallyCacheRowRepository.class);
        TallyCacheConnectorClient connectorClient = mock(TallyCacheConnectorClient.class);
        TallyCacheNormalizer normalizer = mock(TallyCacheNormalizer.class);
        TallyCacheSyncService syncService = mock(TallyCacheSyncService.class);
        TallyVoucherWriteQueueRepository queueRepository = mock(TallyVoucherWriteQueueRepository.class);

        when(properties.getCompany()).thenReturn("MR TECH");
        when(properties.getStaleAfterMs()).thenReturn(300000L);

        TallyDatasetSnapshot snapshot = new TallyDatasetSnapshot();
        snapshot.setId(62L);
        snapshot.setStatus("SUCCESS");
        snapshot.setCompany("MR TECH");
        snapshot.setFetchedAt(Instant.now());
        snapshot.setStaleAfterMs(300000L);
        snapshot.setSnapshotKey("from=20260401&to=20260715");
        snapshot.setRangeStart("20260401");
        snapshot.setRangeEnd("20260715");

        when(snapshotRepository.findCurrent(TallyCacheDataset.DAY_BOOK, "from=20260401&to=20260715"))
                .thenReturn(snapshot);
        when(rowRepository.readRows(TallyCacheDataset.DAY_BOOK, 62L)).thenReturn(Collections.<Map<String, Object>>emptyList());

        TallyVoucherWriteQueueEntry queuedEntry = new TallyVoucherWriteQueueEntry();
        queuedEntry.setId(95L);
        queuedEntry.setCompany("MR TECH");
        queuedEntry.setConnectorPath("/vouchers/sales");
        queuedEntry.setStatus("QUEUED");
        queuedEntry.setOfflineVoucherNumber("OFF-SAL-20260402-569620");
        queuedEntry.setRequestBody("{\"VOUCHER\":[{\"COMPANY\":\"MR TECH\",\"DATE\":\"20260402\",\"VOUCHERTYPENAME\":\"Sales\",\"VOUCHERNUMBER\":\"OFF-SAL-20260402-569620\",\"REFERENCE\":\"102\",\"PARTYLEDGERNAME\":\"KARUPPU Customer\"}]}");

        when(queueRepository.listVisibleByConnectorPath(eq("/vouchers/sales"), eq(100), any(Instant.class)))
                .thenReturn(Collections.singletonList(queuedEntry));
        when(queueRepository.listVisibleByConnectorPath(eq("/vouchers/purchase"), eq(100), any(Instant.class)))
                .thenReturn(Collections.<TallyVoucherWriteQueueEntry>emptyList());
        when(queueRepository.listVisibleByConnectorPath(eq("/vouchers/journal"), eq(100), any(Instant.class)))
                .thenReturn(Collections.<TallyVoucherWriteQueueEntry>emptyList());
        when(queueRepository.listVisibleByConnectorPath(eq("/vouchers/payment"), eq(100), any(Instant.class)))
                .thenReturn(Collections.<TallyVoucherWriteQueueEntry>emptyList());
        when(queueRepository.listVisibleByConnectorPath(eq("/vouchers/receipt"), eq(100), any(Instant.class)))
                .thenReturn(Collections.<TallyVoucherWriteQueueEntry>emptyList());
        when(queueRepository.listVisibleByConnectorPath(eq("/vouchers/contra"), eq(100), any(Instant.class)))
                .thenReturn(Collections.<TallyVoucherWriteQueueEntry>emptyList());

        TallyCacheReadService service = new TallyCacheReadService(properties, snapshotRepository, rowRepository, connectorClient, normalizer, syncService, queueRepository, new ObjectMapper());
        Map<String, Object> response = service.dayBook("20260401", "20260715");

        List<?> rows = (List<?>) response.get("data");
        assertEquals(1, rows.size());
        Map<?, ?> queuedRow = (Map<?, ?>) rows.get(0);
        assertEquals("102", queuedRow.get("voucherNumber"));
        assertEquals("OFF-SAL-20260402-569620", queuedRow.get("offlineVoucherNumber"));
    }
}
