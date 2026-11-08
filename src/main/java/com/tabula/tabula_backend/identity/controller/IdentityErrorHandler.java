package com.tabula.tabula_backend.identity.controller;

import com.tabula.tabula_backend.identity.service.IdentityException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses = AuthController.class)
public class IdentityErrorHandler {
    @ExceptionHandler(IdentityException.class)
    public ProblemDetail identity(IdentityException ex) {
        return problem(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalid(Exception ex) {
        // Do not include rejected values (especially passwords) or binding exception messages.
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Revise os campos enviados.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflict(DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "ACCOUNT_CONFLICT", "Os dados da conta estão em conflito.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail result = ProblemDetail.forStatusAndDetail(status, detail);
        result.setProperty("code", code);
        return result;
    }
}
