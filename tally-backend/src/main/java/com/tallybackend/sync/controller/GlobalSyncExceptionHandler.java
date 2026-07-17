package com.tallybackend.sync.controller;

import com.tallybackend.sync.dto.ApiErrorResponse;
import com.tallybackend.sync.exception.SyncApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;

@RestControllerAdvice
public class GlobalSyncExceptionHandler {

    @ExceptionHandler(SyncApiException.class)
    public ResponseEntity<ApiErrorResponse> handleSyncApiException(SyncApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(new ApiErrorResponse(ex.getMessage(), ex.getErrors()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("Validation failed", Collections.singletonList(ex.getMessage())));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() == null || ex.getReason().trim().isEmpty()
                ? "Request failed"
                : ex.getReason().trim();
        return ResponseEntity.status(ex.getStatus())
                .body(new ApiErrorResponse(message, Collections.singletonList(ex.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleFallback(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("Request failed", Collections.singletonList(ex.getMessage())));
    }
}
