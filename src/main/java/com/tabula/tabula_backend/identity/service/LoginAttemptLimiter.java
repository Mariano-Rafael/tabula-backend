package com.tabula.tabula_backend.identity.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LoginAttemptLimiter {
    private static final long WINDOW = Duration.ofMinutes(15).toMillis();
    private final Map<String, Attempts> attempts = new HashMap<>();

    private record Attempts(long start, int count) {
    }

    public synchronized void check(String email) {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(entry -> now - entry.getValue().start() >= WINDOW);
        Attempts previous = attempts.get(email);
        if ((previous != null && previous.count() >= 10) || (previous == null && attempts.size() >= 10000)) {
            throw new IdentityException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED",
                    "Muitas tentativas. Tente novamente mais tarde.");
        }
        attempts.put(email, previous == null ? new Attempts(now, 1)
                : new Attempts(previous.start(), previous.count() + 1));
    }

    public synchronized void success(String email) {
        attempts.remove(email);
    }
}
