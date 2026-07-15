package com.tallybackend.sync.entity;

import java.time.LocalDateTime;

public class TallyLedgerMapping {
    private Long id;
    private Long businessUnitId;
    private String erpLedgerType;
    private String erpLedgerName;
    private String tallyLedgerName;
    private String tallyGroupName;
    private Boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBusinessUnitId() {
        return businessUnitId;
    }

    public void setBusinessUnitId(Long businessUnitId) {
        this.businessUnitId = businessUnitId;
    }

    public String getErpLedgerType() {
        return erpLedgerType;
    }

    public void setErpLedgerType(String erpLedgerType) {
        this.erpLedgerType = erpLedgerType;
    }

    public String getErpLedgerName() {
        return erpLedgerName;
    }

    public void setErpLedgerName(String erpLedgerName) {
        this.erpLedgerName = erpLedgerName;
    }

    public String getTallyLedgerName() {
        return tallyLedgerName;
    }

    public void setTallyLedgerName(String tallyLedgerName) {
        this.tallyLedgerName = tallyLedgerName;
    }

    public String getTallyGroupName() {
        return tallyGroupName;
    }

    public void setTallyGroupName(String tallyGroupName) {
        this.tallyGroupName = tallyGroupName;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
