package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Output view of the authenticated user's profile. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeDtoOut {

    @Schema(description = "User id")
    private UUID id;

    @Schema(description = "Account email")
    private String email;

    @Schema(description = "User role")
    private String role;

    @Schema(description = "Whether the account is active")
    private boolean active;

    @Schema(description = "Display name")
    private String displayName;

    @Schema(description = "Company name")
    private String companyName;

    @Schema(description = "Country")
    private String country;

    @Schema(description = "Preferred language")
    private String language;

    @Schema(description = "Public avatar URL; null when no avatar uploaded")
    private String avatarUrl;

    @Schema(description = "Account creation timestamp")
    private Instant createdAt;

    @Schema(description = "Last login timestamp")
    private Instant lastLogin;

    @Schema(description = "Granted authorities for the session")
    private Set<String> authorities;
}
