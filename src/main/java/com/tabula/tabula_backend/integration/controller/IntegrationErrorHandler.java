package com.tabula.tabula_backend.integration.controller;

import com.tabula.tabula_backend.identity.service.IdentityException;
import com.tabula.tabula_backend.portfolio.service.PortfolioException;
import com.tabula.tabula_backend.integration.service.IntegrationException;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses = IntegrationController.class)
public class IntegrationErrorHandler {
    @ExceptionHandler(com.tabula.tabula_backend.flow.service.FlowException.class)
    public ProblemDetail flow(com.tabula.tabula_backend.flow.service.FlowException ex) {
        return problem(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler(IntegrationException.class)
    public ProblemDetail integration(IntegrationException ex) {
        return problem(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler(PortfolioException.class)
    public ProblemDetail portfolio(PortfolioException ex) {
        return problem(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler(IdentityException.class)
    public ProblemDetail identity(IdentityException ex) {
        return problem(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ProblemDetail invalid(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Revise os campos enviados.");
    }

    @ExceptionHandler({DataIntegrityViolationException.class, ConcurrencyFailureException.class})
    public ProblemDetail conflict(Exception ex) {
        return problem(HttpStatus.CONFLICT, "INTEGRATION_CONFLICT", "Operação em conflito. Atualize os dados e tente novamente.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String message) {
        var result = ProblemDetail.forStatusAndDetail(status, message);
        result.setProperty("code", code);
        return result;
    }
}
