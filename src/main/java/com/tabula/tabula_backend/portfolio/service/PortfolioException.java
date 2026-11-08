package com.tabula.tabula_backend.portfolio.service;

import org.springframework.http.HttpStatus;

public class PortfolioException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public PortfolioException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
