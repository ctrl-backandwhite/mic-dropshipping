package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload to change a user's role from the admin panel.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserRoleUpdateDtoIn {

    @NotBlank
    private String role;
}
