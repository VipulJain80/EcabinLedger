package com.ecabin.ledger.api;

import static com.ecabin.ledger.api.ApiModels.*;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorBody> invalidRequest() {
        return ResponseEntity.badRequest().body(new ErrorBody(new ErrorDetail("validation_error", "Check the submitted fields.")));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorBody> invalidValue(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorBody(new ErrorDetail("invalid_value", ex.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorBody> invalidTransition(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody(new ErrorDetail("invalid_transition", ex.getMessage())));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorBody> status(ResponseStatusException ex) {
        var status = HttpStatus.valueOf(ex.getStatusCode().value());
        return ResponseEntity.status(status).body(new ErrorBody(new ErrorDetail(status == HttpStatus.NOT_FOUND ? "not_found" : "request_error", ex.getReason() == null ? status.getReasonPhrase() : ex.getReason())));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorBody> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorBody(new ErrorDetail("conflict", "This value conflicts with an existing record or is referenced by operational data.")));
    }
}
