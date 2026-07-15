package com.tallybackend.sync.exception;

import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

public class SyncApiException extends RuntimeException {
    private final HttpStatus status;
    private final List<String> errors;

    public SyncApiException(HttpStatus status, String message) {
        this(status, message, new ArrayList<String>());
    }

    public SyncApiException(HttpStatus status, String message, List<String> errors) {
        super(message);
        this.status = status == null ? HttpStatus.BAD_REQUEST : status;
        this.errors = errors == null ? new ArrayList<String>() : errors;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public List<String> getErrors() {
        return errors;
    }
}
