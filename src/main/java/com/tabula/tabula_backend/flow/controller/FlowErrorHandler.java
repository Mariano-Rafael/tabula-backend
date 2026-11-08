package com.tabula.tabula_backend.flow.controller;

import com.tabula.tabula_backend.flow.service.FlowException;
import com.tabula.tabula_backend.identity.service.IdentityException;
import com.tabula.tabula_backend.integration.service.IntegrationException;
import com.tabula.tabula_backend.portfolio.service.PortfolioException;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackageClasses = FlowController.class)
public class FlowErrorHandler {
    @ExceptionHandler(FlowException.class)
    public ProblemDetail flow(FlowException ex) { return problem(ex.status(), ex.code(), ex.getMessage()); }
    @ExceptionHandler(IdentityException.class)
    public ProblemDetail identity(IdentityException ex) { return problem(ex.status(), ex.code(), ex.getMessage()); }
    @ExceptionHandler(PortfolioException.class)
    public ProblemDetail portfolio(PortfolioException ex) { return problem(ex.status(), ex.code(), ex.getMessage()); }
    @ExceptionHandler(IntegrationException.class)
    public ResponseEntity<ProblemDetail> integration(IntegrationException ex) {
        var response = ResponseEntity.status(ex.status());
        if (ex.status() == HttpStatus.ACCEPTED) { response.header(HttpHeaders.RETRY_AFTER, "2"); }
        return response.body(problem(ex.status(), ex.code(), ex.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalid(Exception ex) { return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Revise os campos enviados."); }
    @ExceptionHandler({DataIntegrityViolationException.class, ConcurrencyFailureException.class})
    public ProblemDetail conflict(Exception ex) { return problem(HttpStatus.CONFLICT, "FLOW_CONFLICT", "Operação em conflito. Consulte o resultado com a mesma chave."); }
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail database(DataAccessException ex) { return problem(HttpStatus.SERVICE_UNAVAILABLE, "FLOW_METADATA_UNAVAILABLE", "Metadados indisponíveis. Consulte ou reconcilie a versão reservada."); }
    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var p = ProblemDetail.forStatusAndDetail(status, detail); p.setProperty("code", code); return p;
    }
}
