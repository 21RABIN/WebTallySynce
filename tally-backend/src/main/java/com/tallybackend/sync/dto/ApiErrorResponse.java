package com.tallybackend.sync.dto;

import java.util.ArrayList;
import java.util.List;

public class ApiErrorResponse {
    private boolean success = false;
    private String message;
    private List<String> errors = new ArrayList<>();

    public ApiErrorResponse() {
    }

    public ApiErrorResponse(String message, List<String> errors) {
        this.message = message;
        this.errors = errors == null ? new ArrayList<String>() : errors;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors == null ? new ArrayList<String>() : errors;
    }
}
