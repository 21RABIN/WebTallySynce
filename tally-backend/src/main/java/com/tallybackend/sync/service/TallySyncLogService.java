package com.tallybackend.sync.service;

import com.tallybackend.sync.dto.TallyParsedResponse;
import com.tallybackend.sync.entity.TallySyncLog;
import com.tallybackend.sync.entity.TallySyncStatus;
import com.tallybackend.sync.exception.SyncApiException;
import com.tallybackend.sync.repository.TallySyncLogRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TallySyncLogService {

    private final TallySyncLogRepository repository;

    public TallySyncLogService(TallySyncLogRepository repository) {
        this.repository = repository;
    }

    public List<TallySyncLog> findAll() {
        return repository.findAll();
    }

    public TallySyncLog findById(Long id) {
        return repository.findById(id).orElseThrow(() ->
                new SyncApiException(HttpStatus.NOT_FOUND, "Sync log not found"));
    }

    public List<TallySyncLog> findByEntity(String entityType, Long entityId) {
        return repository.findByEntityTypeAndEntityId(normalize(entityType), entityId);
    }

    public List<TallySyncLog> findFailed() {
        return repository.findByStatus(TallySyncStatus.FAILED.name());
    }

    public boolean hasSuccessfulSync(String entityType, Long entityId) {
        return repository.existsByEntityTypeAndEntityIdAndStatus(normalize(entityType), entityId, TallySyncStatus.SUCCESS.name());
    }

    public TallySyncLog createPending(String entityType, Long entityId, String tallyType, String requestXml) {
        return saveNew(entityType, entityId, tallyType, requestXml, TallySyncStatus.PENDING, null);
    }

    public TallySyncLog createDuplicate(String entityType, Long entityId, String tallyType, String message) {
        TallySyncLog log = saveNew(entityType, entityId, tallyType, null, TallySyncStatus.DUPLICATE, message);
        log.setErrorMessage(message);
        repository.update(log);
        return log;
    }

    public TallySyncLog createFailed(String entityType, Long entityId, String tallyType, String requestXml, String errorMessage) {
        TallySyncLog log = saveNew(entityType, entityId, tallyType, requestXml, TallySyncStatus.FAILED, errorMessage);
        log.setErrorMessage(errorMessage);
        repository.update(log);
        return log;
    }

    public TallySyncLog markRetrying(TallySyncLog log) {
        log.setStatus(TallySyncStatus.RETRYING.name());
        log.setRetryCount((log.getRetryCount() == null ? 0 : log.getRetryCount()) + 1);
        log.setUpdatedAt(LocalDateTime.now());
        repository.update(log);
        return log;
    }

    public TallySyncLog markSuccess(TallySyncLog log, TallyParsedResponse response) {
        log.setStatus(TallySyncStatus.SUCCESS.name());
        log.setResponseXml(response.getRawResponse());
        log.setErrorMessage(response.getLineError());
        log.setTallyGuid(response.getTallyGuid());
        log.setUpdatedAt(LocalDateTime.now());
        repository.update(log);
        return log;
    }

    public TallySyncLog markFailed(TallySyncLog log, String errorMessage, String responseXml) {
        log.setStatus(TallySyncStatus.FAILED.name());
        log.setResponseXml(responseXml);
        log.setErrorMessage(errorMessage);
        log.setUpdatedAt(LocalDateTime.now());
        repository.update(log);
        return log;
    }

    public boolean delete(Long id) {
        return repository.deleteById(id);
    }

    public Map<String, Object> check(String entityType, Long entityId) {
        List<TallySyncLog> logs = findByEntity(entityType, entityId);
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("entityType", normalize(entityType));
        response.put("entityId", entityId);
        response.put("synced", hasSuccessfulSync(entityType, entityId));
        response.put("logs", logs);
        response.put("latestStatus", logs.isEmpty() ? null : logs.get(0).getStatus());
        return response;
    }

    private TallySyncLog saveNew(String entityType, Long entityId, String tallyType, String requestXml, TallySyncStatus status, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        TallySyncLog log = new TallySyncLog();
        log.setEntityType(normalize(entityType));
        log.setEntityId(entityId);
        log.setTallyType(tallyType);
        log.setRequestXml(requestXml);
        log.setStatus(status.name());
        log.setErrorMessage(errorMessage);
        log.setRetryCount(0);
        log.setCreatedAt(now);
        log.setUpdatedAt(now);
        Long id = repository.insert(log);
        return findById(id);
    }

    private String normalize(String entityType) {
        return entityType == null ? null : entityType.trim().toUpperCase();
    }
}
