package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Input payload to create an admin/operator/user account from the admin panel. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAdminUserDtoIn {

    @NotBlank
    @Email
    @Schema(description = "Account email", example = "operator@example.com")
    private String email;

    @NotBlank
    @Size(min = 12, max = 128)
    @Schema(description = "Account password (min 12 chars)")
    private String password;

    @NotBlank
    @Pattern(regexp = "^(ADMIN|OPERATOR|USER|PARTNER)$")
    @Schema(description = "Role to grant", example = "OPERATOR")
    private String role;

    @Schema(description = "Display name")
    private String displayName;
}
