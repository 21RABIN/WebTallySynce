package com.tallybackend.sync.dto;

import java.util.ArrayList;
import java.util.List;

public class TallyValidationResult {
    private boolean valid = true;
    private List<String> errors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors == null ? new ArrayList<String>() : errors;
        this.valid = this.errors.isEmpty();
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings == null ? new ArrayList<String>() : warnings;
    }

    public void addError(String error) {
        if (error != null && !error.trim().isEmpty()) {
            this.errors.add(error);
            this.valid = false;
        }
    }

    public void addWarning(String warning) {
        if (warning != null && !warning.trim().isEmpty()) {
            this.warnings.add(warning);
        }
    }
}
