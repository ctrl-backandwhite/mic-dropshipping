package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Admin invite payload: email + optional role (defaults to USER). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserInviteDtoIn {

    @NotBlank
    @Email
    private String email;

    private String role;
}
