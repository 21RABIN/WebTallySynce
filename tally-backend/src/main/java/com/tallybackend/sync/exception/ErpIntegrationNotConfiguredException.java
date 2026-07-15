package com.tallybackend.sync.exception;

import org.springframework.http.HttpStatus;

import java.util.Collections;

public class ErpIntegrationNotConfiguredException extends SyncApiException {
    public ErpIntegrationNotConfiguredException(String message) {
        super(HttpStatus.NOT_IMPLEMENTED, message, Collections.singletonList(message));
    }
}
