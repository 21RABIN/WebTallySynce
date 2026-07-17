package com.tallybackend.sync.dto;

import java.util.ArrayList;
import java.util.List;

public class BulkSyncResponse {
    private int total;
    private int success;
    private int failed;
    private int skipped;
    private List<FailedItem> failedItems = new ArrayList<>();

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getSuccess() {
        return success;
    }

    public void setSuccess(int success) {
        this.success = success;
    }

    public int getFailed() {
        return failed;
    }

    public void setFailed(int failed) {
        this.failed = failed;
    }

    public int getSkipped() {
        return skipped;
    }

    public void setSkipped(int skipped) {
        this.skipped = skipped;
    }

    public List<FailedItem> getFailedItems() {
        return failedItems;
    }

    public void setFailedItems(List<FailedItem> failedItems) {
        this.failedItems = failedItems == null ? new ArrayList<FailedItem>() : failedItems;
    }

    public static class FailedItem {
        private Long entityId;
        private String message;

        public FailedItem() {
        }

        public FailedItem(Long entityId, String message) {
            this.entityId = entityId;
            this.message = message;
        }

        public Long getEntityId() {
            return entityId;
        }

        public void setEntityId(Long entityId) {
            this.entityId = entityId;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
