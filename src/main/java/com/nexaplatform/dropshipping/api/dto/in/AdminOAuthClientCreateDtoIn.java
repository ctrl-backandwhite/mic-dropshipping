package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Create payload for an OAuth2 partner client. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminOAuthClientCreateDtoIn {

    @NotBlank
    private String name;

    /** Granted scopes (defaults to catalog:read when empty). */
    private List<String> scopes;
}
