package com.tallybackend.sync.controller;

import com.tallybackend.sync.dto.TallySyncResult;
import com.tallybackend.sync.entity.TallySyncLog;
import com.tallybackend.sync.service.TallySyncLogService;
import com.tallybackend.sync.service.TallySyncService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tally-sync")
@Tag(name = "Tally Sync Logs")
public class TallySyncLogController {

    private final TallySyncLogService tallySyncLogService;
    private final TallySyncService tallySyncService;

    public TallySyncLogController(TallySyncLogService tallySyncLogService,
                                  TallySyncService tallySyncService) {
        this.tallySyncLogService = tallySyncLogService;
        this.tallySyncService = tallySyncService;
    }

    @GetMapping("/logs")
    public List<TallySyncLog> logs() {
        return tallySyncLogService.findAll();
    }

    @GetMapping("/logs/{id}")
    public TallySyncLog log(@PathVariable Long id) {
        return tallySyncLogService.findById(id);
    }

    @GetMapping("/logs/entity/{entityType}/{entityId}")
    public List<TallySyncLog> logsByEntity(@PathVariable String entityType,
                                           @PathVariable Long entityId) {
        return tallySyncLogService.findByEntity(entityType, entityId);
    }

    @GetMapping("/failed")
    public List<TallySyncLog> failed() {
        return tallySyncLogService.findFailed();
    }

    @PostMapping("/retry/{syncLogId}")
    public TallySyncResult retry(@PathVariable Long syncLogId) {
        return tallySyncService.retrySyncLog(syncLogId);
    }

    @PostMapping("/retry-failed")
    public Map<String, Object> retryFailed() {
        List<TallySyncResult> results = tallySyncService.retryFailedSyncs();
        int success = 0;
        int failed = 0;
        for (TallySyncResult result : results) {
            if (result.isSuccess()) {
                success++;
            } else {
                failed++;
            }
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("total", results.size());
        response.put("success", success);
        response.put("failed", failed);
        response.put("results", results);
        return response;
    }

    @GetMapping("/check/{entityType}/{entityId}")
    public Map<String, Object> check(@PathVariable String entityType,
                                     @PathVariable Long entityId) {
        return tallySyncLogService.check(entityType, entityId);
    }

    @DeleteMapping("/logs/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("deleted", tallySyncLogService.delete(id));
        response.put("id", id);
        return response;
    }
}
