package com.tabula.tabula_backend.identity.config;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class SecurityResponses {
    private SecurityResponses() {
    }

    // All callers provide fixed messages, never request data or exception messages.
    public static void write(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":" + status
                + ",\"title\":\"Falha de acesso\",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }
}
