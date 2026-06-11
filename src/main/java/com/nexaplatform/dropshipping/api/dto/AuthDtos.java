package com.nexaplatform.dropshipping.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @Size(max = 120) String displayName,
            @Size(max = 180) String companyName,
            @Size(max = 60) String country,
            @Pattern(regexp = "^(es|en|pt)$", message = "language must be es|en|pt") String language
    ) {}

    public record ActivateRequest(@NotBlank @Size(max = 64) String code) {}

    public record PasswordResetRequest(@NotBlank @Email String email) {}

    public record PasswordResetConfirmRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 12, max = 128) String newPassword
    ) {}

    public record CreateAdminUserRequest(
            @NotBlank @Email String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @NotBlank @Pattern(regexp = "^(ADMIN|OPERATOR|USER|PARTNER)$") String role,
            String displayName
    ) {}

    public record MeView(
            UUID id,
            String email,
            String role,
            boolean active,
            String displayName,
            String companyName,
            String country,
            String language,
            // DROP-583: URL pública del avatar; null si el usuario no subió ninguno
            // (el frontend muestra iniciales como fallback). Servido vía MinIO
            // detrás de S3_PUBLIC_URL.
            String avatarUrl,
            Instant createdAt,
            Instant lastLogin,
            Set<String> authorities
    ) {}

    public record RegisterResponse(UUID userId, String message) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 12, max = 128) String newPassword
    ) {}

    public record UpdateProfileRequest(
            @Size(max = 120) String displayName,
            @Size(max = 180) String companyName,
            @Size(max = 60) String country,
            @Pattern(regexp = "^(es|en|pt|zh)$", message = "language must be es|en|pt|zh") String language
    ) {}
}
