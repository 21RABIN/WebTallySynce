package com.tallybackend.service;

public class DuplicateVoucherNumberException extends RuntimeException {

    private final String company;
    private final String connectorPath;
    private final String voucherNumber;
    private final Long existingQueueId;
    private final String existingStatus;
    private final String existingReviewState;

    public DuplicateVoucherNumberException(String company,
                                           String connectorPath,
                                           String voucherNumber,
                                           Long existingQueueId,
                                           String existingStatus,
                                           String existingReviewState) {
        super("Voucher number already exists: " + voucherNumber);
        this.company = company;
        this.connectorPath = connectorPath;
        this.voucherNumber = voucherNumber;
        this.existingQueueId = existingQueueId;
        this.existingStatus = existingStatus;
        this.existingReviewState = existingReviewState;
    }

    public String getCompany() {
        return company;
    }

    public String getConnectorPath() {
        return connectorPath;
    }

    public String getVoucherNumber() {
        return voucherNumber;
    }

    public Long getExistingQueueId() {
        return existingQueueId;
    }

    public String getExistingStatus() {
        return existingStatus;
    }

    public String getExistingReviewState() {
        return existingReviewState;
    }
}
