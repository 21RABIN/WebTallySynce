package com.tallybackend.sync.dto;

public class TallyLedgerMappingRequest {
    private Long businessUnitId;
    private String erpLedgerType;
    private String erpLedgerName;
    private String tallyLedgerName;
    private String tallyGroupName;
    private Boolean isActive;

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

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean active) {
        isActive = active;
    }
}
