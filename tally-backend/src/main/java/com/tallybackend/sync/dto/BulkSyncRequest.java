package com.tallybackend.sync.dto;

import java.time.LocalDate;

public class BulkSyncRequest {
    private LocalDate fromDate;
    private LocalDate toDate;
    private Long businessUnitId;
    private boolean skipAlreadySynced = true;
    private boolean forceSync;

    public LocalDate getFromDate() {
        return fromDate;
    }

    public void setFromDate(LocalDate fromDate) {
        this.fromDate = fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public void setToDate(LocalDate toDate) {
        this.toDate = toDate;
    }

    public Long getBusinessUnitId() {
        return businessUnitId;
    }

    public void setBusinessUnitId(Long businessUnitId) {
        this.businessUnitId = businessUnitId;
    }

    public boolean isSkipAlreadySynced() {
        return skipAlreadySynced;
    }

    public void setSkipAlreadySynced(boolean skipAlreadySynced) {
        this.skipAlreadySynced = skipAlreadySynced;
    }

    public boolean isForceSync() {
        return forceSync;
    }

    public void setForceSync(boolean forceSync) {
        this.forceSync = forceSync;
    }
}
