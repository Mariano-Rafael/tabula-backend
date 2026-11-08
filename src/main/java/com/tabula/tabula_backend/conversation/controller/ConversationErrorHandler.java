package com.tabula.tabula_backend.conversation.controller;

import com.tabula.tabula_backend.conversation.service.ConversationException;
import com.tabula.tabula_backend.identity.service.IdentityException;
import com.tabula.tabula_backend.integration.service.IntegrationException;
import com.tabula.tabula_backend.portfolio.service.PortfolioException;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = {"com.tabula.tabula_backend.conversation.controller", "com.tabula.tabula_backend.history.controller"})
public class ConversationErrorHandler {
    @ExceptionHandler(ConversationException.class)
    public ProblemDetail conversation(ConversationException ex) { return problem(ex.status(), ex.code(), ex.getMessage()); }
    @ExceptionHandler(IdentityException.class)
    public ProblemDetail identity(IdentityException ex) { return problem(ex.status(), ex.code(), ex.getMessage()); }
    @ExceptionHandler(PortfolioException.class)
    public ProblemDetail portfolio(PortfolioException ex) { return problem(ex.status(), ex.code(), ex.getMessage()); }
    @ExceptionHandler(IntegrationException.class)
    public ProblemDetail integration(IntegrationException ex) { return problem(ex.status(), ex.code(), ex.getMessage()); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalid(Exception ex) { return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Revise os campos enviados."); }
    @ExceptionHandler({DataIntegrityViolationException.class, ConcurrencyFailureException.class})
    public ProblemDetail conflict(Exception ex) { return problem(HttpStatus.CONFLICT, "CONVERSATION_CONFLICT", "Operação concorrente. Consulte o atendimento com a mesma chave."); }
    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail database(DataAccessException ex) { return problem(HttpStatus.SERVICE_UNAVAILABLE, "CONVERSATION_METADATA_UNAVAILABLE", "Metadados indisponíveis. Consulte ou reconcilie a tentativa antes de reenviar."); }
    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        var result = ProblemDetail.forStatusAndDetail(status, detail); result.setProperty("code", code); return result;
    }
}
