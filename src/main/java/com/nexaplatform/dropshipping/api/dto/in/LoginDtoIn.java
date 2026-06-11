package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Input payload to authenticate and open a session. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginDtoIn {

    @NotBlank
    @Email
    @Schema(description = "Account email", example = "user@example.com")
    private String email;

    @NotBlank
    @Schema(description = "Account password")
    private String password;
}
