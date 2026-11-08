package com.tabula.tabula_backend.identity.dto;

import com.tabula.tabula_backend.identity.model.AccountUser;
import jakarta.validation.constraints.*;

public final class AccountDtos {
    private AccountDtos() {
    }

    public record Login(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(max = 72) String password) {
    }

    public record Create(@NotBlank @Size(max = 120) String name,
                         @NotBlank @Email @Size(max = 254) String email,
                         @NotBlank @Size(min = 8, max = 72) String password,
                         boolean admin) {
    }

    public record Profile(@Size(min = 1, max = 120) String name,
                          @Email @Size(min = 3, max = 254) String email) {
    }

    public record Password(@NotBlank @Size(max = 72) String currentPassword,
                           @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    public record AdminUpdate(@Size(min = 1, max = 120) String name,
                              @Email @Size(min = 3, max = 254) String email,
                              Boolean admin, Boolean active) {
    }

    public record View(String id, String name, String email, boolean admin, boolean active) {
        public static View from(AccountUser user) {
            return new View(user.getId(), user.getName(), user.getEmail(), user.isAdmin(), user.isActive());
        }
    }
}
