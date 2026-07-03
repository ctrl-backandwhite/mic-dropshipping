package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Input payload to confirm the authenticated user's account deletion with the emailed code. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeleteAccountConfirmDtoIn {

    @NotBlank
    @Size(min = 4, max = 64)
    @Schema(description = "The account-deletion confirmation code sent to the user's email")
    private String code;
}
