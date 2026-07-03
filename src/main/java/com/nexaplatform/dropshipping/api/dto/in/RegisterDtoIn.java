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
    @Size(min = 8, max = 128)
    @Schema(description = "Account password (min 8 chars, must include upper/lower/digit/symbol)")
    private String password;

    @Size(max = 120)
    @Schema(description = "Display name (nombre completo; se compone de firstName + apellidos si no se envía)")
    private String displayName;

    @Size(max = 80)
    @Schema(description = "First name / nombre de pila")
    private String firstName;

    @Size(max = 80)
    @Schema(description = "First surname / primer apellido")
    private String lastName1;

    @Size(max = 80)
    @Schema(description = "Second surname / segundo apellido (optional)")
    private String lastName2;

    @Size(max = 180)
    @Schema(description = "Company name")
    private String companyName;

    @Size(max = 60)
    @Schema(description = "Country")
    private String country;

    @Pattern(regexp = "^(es|en|pt|zh|fr|de|it|nl)$", message = "language must be one of es|en|pt|zh|fr|de|it|nl")
    @Schema(description = "Preferred language", example = "es")
    private String language;
}
