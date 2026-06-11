package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.UUID;

/** Output of a successful account registration. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterDtoOut {

    @Schema(description = "Newly created user id")
    private UUID userId;

    @Schema(description = "Human-readable confirmation message")
    private String message;
}
