package com.ecabin.ledger.api;

import org.springframework.http.HttpStatus;

/** Sanitized report API failure with a stable API error code. */
public final class ReportApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ReportApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
