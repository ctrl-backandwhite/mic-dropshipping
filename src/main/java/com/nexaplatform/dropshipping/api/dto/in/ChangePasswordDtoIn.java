package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Input payload to change the authenticated user's password. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordDtoIn {

    @NotBlank
    @Schema(description = "The user's current password")
    private String currentPassword;

    @NotBlank
    @Size(min = 8, max = 128)
    @Schema(description = "The new password (min 12 chars)")
    private String newPassword;
}
