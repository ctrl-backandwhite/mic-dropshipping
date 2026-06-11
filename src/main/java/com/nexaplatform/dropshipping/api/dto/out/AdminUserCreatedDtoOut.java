package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Result of creating an admin user: the identifier of the new user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserCreatedDtoOut {

    @Schema(description = "Identifier of the newly created admin user")
    private UUID id;
}
