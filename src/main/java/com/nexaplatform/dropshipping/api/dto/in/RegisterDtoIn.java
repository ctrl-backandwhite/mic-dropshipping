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

/** Input payload to register a new account. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterDtoIn {

    @NotBlank
    @Email
    @Size(max = 254)
    @Schema(description = "Account email", example = "user@example.com")
    private String email;

    @NotBlank
    @Size(min = 12, max = 128)
    @Schema(description = "Account password (min 12 chars)")
    private String password;

    @Size(max = 120)
    @Schema(description = "Display name")
    private String displayName;

    @Size(max = 180)
    @Schema(description = "Company name")
    private String companyName;

    @Size(max = 60)
    @Schema(description = "Country")
    private String country;

    @Pattern(regexp = "^(es|en|pt)$", message = "language must be es|en|pt")
    @Schema(description = "Preferred language", example = "es")
    private String language;
}
