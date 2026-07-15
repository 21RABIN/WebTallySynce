package com.tallybackend.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tallybackend.cache.TallyCacheConnectorClient.ConnectorFetchResult;
import com.tallybackend.service.ConnectorRegistryService;
import com.tallybackend.service.ResolvedConnectorTarget;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TallyCacheSyncServiceTest {

    @Test
    void unchangedSyncRefreshesCurrentSnapshotWithoutCreatingDuplicateRows() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TallyCacheProperties properties = mock(TallyCacheProperties.class);
        TallyCacheConnectorClient connectorClient = mock(TallyCacheConnectorClient.class);
        TallyCacheNormalizer normalizer = mock(TallyCacheNormalizer.class);
        TallyCacheSnapshotRepository snapshotRepository = mock(TallyCacheSnapshotRepository.class);
        TallyCacheRowRepository rowRepository = mock(TallyCacheRowRepository.class);
        ConnectorRegistryService connectorRegistryService = mock(ConnectorRegistryService.class);

        when(properties.getCompany()).thenReturn("");
        when(properties.getStaleAfterMs()).thenReturn(60000L);
        when(properties.currentFinancialYearStart()).thenReturn("20260401");
        when(properties.currentFinancialYearEnd()).thenReturn("20270331");
        when(properties.today()).thenReturn("20260612");

        ResolvedConnectorTarget target = new ResolvedConnectorTarget("http://127.0.0.1:8082", "local-dev-key", "default", "default");
        ConnectorFetchResult fetchResult = new ConnectorFetchResult(target, objectMapper.createObjectNode());
        when(connectorClient.fetch(anyString(), anyMap(), any())).thenReturn(fetchResult);

        List<Map<String, Object>> emptyRows = Collections.emptyList();
        when(normalizer.companies(any())).thenReturn(emptyRows);
        when(normalizer.groups(any())).thenReturn(emptyRows);
        when(normalizer.ledgers(any())).thenReturn(emptyRows);
        when(normalizer.uoms(any())).thenReturn(emptyRows);
        when(normalizer.currencies(any())).thenReturn(emptyRows);
        when(normalizer.stockGroups(any())).thenReturn(emptyRows);
        when(normalizer.stockItems(any())).thenReturn(emptyRows);
        when(normalizer.companyCurrency(any())).thenReturn(emptyRows);
        when(normalizer.companyFeatures(any())).thenReturn(emptyRows);
        when(normalizer.dayBook(any())).thenReturn(emptyRows);
        when(normalizer.ledgerVouchersFromDayBook(any())).thenReturn(emptyRows);
        when(normalizer.balanceSheet(any())).thenReturn(emptyRows);
        when(normalizer.profitLoss(any())).thenReturn(emptyRows);

        TallyDatasetSnapshot currentSnapshot = new TallyDatasetSnapshot();
        currentSnapshot.setId(42L);
        currentSnapshot.setCurrent(true);
        currentSnapshot.setStatus("SUCCESS");
        currentSnapshot.setContentHash(sha256Hex(objectMapper.writeValueAsBytes(emptyRows)));
        currentSnapshot.setFetchedAt(Instant.now());
        when(snapshotRepository.findCurrent(any(TallyCacheDataset.class), anyString())).thenReturn(currentSnapshot);
        when(snapshotRepository.listDuplicateSnapshotIds(any(TallyCacheDataset.class), anyString(), anyString(), any())).thenReturn(Collections.emptyList());
        when(snapshotRepository.listObsoleteSnapshotIds(any(TallyCacheDataset.class), anyString(), any())).thenReturn(Collections.emptyList());
        when(connectorRegistryService.list()).thenReturn(Collections.emptyList());

        TallyCacheSyncService service = new TallyCacheSyncService(
                properties,
                connectorClient,
                normalizer,
                snapshotRepository,
                rowRepository,
                connectorRegistryService,
                objectMapper
        );

        Map<String, Object> response = service.runSyncNow();

        assertEquals("SUCCESS", response.get("status"));
        verify(snapshotRepository, never()).createSnapshot(any(), any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        verify(rowRepository, never()).replaceRows(any(TallyCacheDataset.class), any(), any());
        verify(snapshotRepository, atLeastOnce()).refreshCurrentSnapshot(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyLong(), any(), any());
    }

    @Test
    void emptySyncPreservesExistingNonEmptyCurrentSnapshot() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TallyCacheProperties properties = mock(TallyCacheProperties.class);
        TallyCacheConnectorClient connectorClient = mock(TallyCacheConnectorClient.class);
        TallyCacheNormalizer normalizer = mock(TallyCacheNormalizer.class);
        TallyCacheSnapshotRepository snapshotRepository = mock(TallyCacheSnapshotRepository.class);
        TallyCacheRowRepository rowRepository = mock(TallyCacheRowRepository.class);
        ConnectorRegistryService connectorRegistryService = mock(ConnectorRegistryService.class);

        when(properties.getCompany()).thenReturn("");
        when(properties.getStaleAfterMs()).thenReturn(60000L);
        when(properties.currentFinancialYearStart()).thenReturn("20260401");
        when(properties.currentFinancialYearEnd()).thenReturn("20270331");
        when(properties.today()).thenReturn("20260612");

        ResolvedConnectorTarget target = new ResolvedConnectorTarget("http://127.0.0.1:8082", "local-dev-key", "default", "default");
        ConnectorFetchResult fetchResult = new ConnectorFetchResult(target, objectMapper.createObjectNode());
        when(connectorClient.fetch(anyString(), anyMap(), any())).thenReturn(fetchResult);

        List<Map<String, Object>> emptyRows = Collections.emptyList();
        when(normalizer.companies(any())).thenReturn(emptyRows);
        when(normalizer.groups(any())).thenReturn(emptyRows);
        when(normalizer.ledgers(any())).thenReturn(emptyRows);
        when(normalizer.uoms(any())).thenReturn(emptyRows);
        when(normalizer.currencies(any())).thenReturn(emptyRows);
        when(normalizer.stockGroups(any())).thenReturn(emptyRows);
        when(normalizer.stockItems(any())).thenReturn(emptyRows);
        when(normalizer.companyCurrency(any())).thenReturn(emptyRows);
        when(normalizer.companyFeatures(any())).thenReturn(emptyRows);
        when(normalizer.dayBook(any())).thenReturn(emptyRows);
        when(normalizer.ledgerVouchersFromDayBook(any())).thenReturn(emptyRows);
        when(normalizer.balanceSheet(any())).thenReturn(emptyRows);
        when(normalizer.profitLoss(any())).thenReturn(emptyRows);

        TallyDatasetSnapshot currentSnapshot = new TallyDatasetSnapshot();
        currentSnapshot.setId(77L);
        currentSnapshot.setCurrent(true);
        currentSnapshot.setStatus("SUCCESS");
        currentSnapshot.setContentHash("existing-non-empty-hash");
        currentSnapshot.setRowCount(5);
        currentSnapshot.setFetchedAt(Instant.now());
        when(snapshotRepository.findCurrent(any(TallyCacheDataset.class), anyString())).thenReturn(currentSnapshot);
        when(snapshotRepository.listObsoleteSnapshotIds(any(TallyCacheDataset.class), anyString(), any())).thenReturn(Collections.emptyList());
        when(connectorRegistryService.list()).thenReturn(Collections.emptyList());

        TallyCacheSyncService service = new TallyCacheSyncService(
                properties,
                connectorClient,
                normalizer,
                snapshotRepository,
                rowRepository,
                connectorRegistryService,
                objectMapper
        );

        Map<String, Object> response = service.runSyncNow();

        assertEquals("SUCCESS", response.get("status"));
        verify(snapshotRepository, never()).createSnapshot(any(), any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), any());
        verify(rowRepository, never()).replaceRows(any(TallyCacheDataset.class), any(), any());
        verify(snapshotRepository, atLeastOnce()).refreshCurrentSnapshot(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyLong(), any(), any());
    }

    private String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte value : hashed) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }
}
