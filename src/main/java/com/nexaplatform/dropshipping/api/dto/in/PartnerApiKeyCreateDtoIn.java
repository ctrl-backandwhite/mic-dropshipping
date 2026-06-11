package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Payload to create a new self-service OAuth2 partner API key.
 * {@code scopes} is optional; when null/empty a sensible default set is used.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartnerApiKeyCreateDtoIn {

    @NotBlank
    @Size(max = 120)
    private String name;

    private List<String> scopes;
}
