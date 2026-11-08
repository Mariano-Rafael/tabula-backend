package com.tabula.tabula_backend.identity.config;

import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.IOException;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionAccountFilter extends OncePerRequestFilter {
    private final UserRepository users;

    public SessionAccountFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal principal) {
            var user = users.findById(principal.id());
            if (user.isEmpty() || !user.get().isActive() || user.get().getAuthVersion() != principal.authVersion()) {
                SecurityContextHolder.clearContext();
                HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
                SecurityResponses.write(response, 401, "SESSION_INVALID", "Sessão inválida. Faça login novamente.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
