package com.tallybackend.sync.dto;

public class TallyParsedResponse {
    private boolean success;
    private int created;
    private int altered;
    private int deleted;
    private int errors;
    private int cancelled;
    private String lineError;
    private String lastVchId;
    private String tallyGuid;
    private String rawResponse;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public int getCreated() {
        return created;
    }

    public void setCreated(int created) {
        this.created = created;
    }

    public int getAltered() {
        return altered;
    }

    public void setAltered(int altered) {
        this.altered = altered;
    }

    public int getDeleted() {
        return deleted;
    }

    public void setDeleted(int deleted) {
        this.deleted = deleted;
    }

    public int getErrors() {
        return errors;
    }

    public void setErrors(int errors) {
        this.errors = errors;
    }

    public int getCancelled() {
        return cancelled;
    }

    public void setCancelled(int cancelled) {
        this.cancelled = cancelled;
    }

    public String getLineError() {
        return lineError;
    }

    public void setLineError(String lineError) {
        this.lineError = lineError;
    }

    public String getLastVchId() {
        return lastVchId;
    }

    public void setLastVchId(String lastVchId) {
        this.lastVchId = lastVchId;
    }

    public String getTallyGuid() {
        return tallyGuid;
    }

    public void setTallyGuid(String tallyGuid) {
        this.tallyGuid = tallyGuid;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }
}
