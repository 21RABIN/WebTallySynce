package com.tallybackend.sync.dto;

import java.util.ArrayList;
import java.util.List;

public class TallySyncResult {
    private boolean success;
    private String status;
    private String message;
    private String entityType;
    private Long entityId;
    private Long syncLogId;
    private String tallyGuid;
    private List<String> errors = new ArrayList<>();

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public Long getEntityId() {
        return entityId;
    }

    public void setEntityId(Long entityId) {
        this.entityId = entityId;
    }

    public Long getSyncLogId() {
        return syncLogId;
    }

    public void setSyncLogId(Long syncLogId) {
        this.syncLogId = syncLogId;
    }

    public String getTallyGuid() {
        return tallyGuid;
    }

    public void setTallyGuid(String tallyGuid) {
        this.tallyGuid = tallyGuid;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors == null ? new ArrayList<String>() : errors;
    }
}
