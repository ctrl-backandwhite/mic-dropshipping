package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Input payload to create or update a user shipping address. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddressDtoIn {

    @Schema(description = "Optional address label", example = "Home")
    private String label;

    @NotBlank
    @Schema(description = "Recipient full name", example = "Ada Lovelace")
    private String fullName;

    @Schema(description = "Contact phone")
    private String phone;

    @NotBlank
    @Schema(description = "Address line 1", example = "1 Babbage Way")
    private String line1;

    @Schema(description = "Address line 2")
    private String line2;

    @NotBlank
    @Schema(description = "City", example = "London")
    private String city;

    @Schema(description = "State / region / province")
    private String state;

    @Schema(description = "Postal / ZIP code", example = "EC1A1")
    private String postalCode;

    @NotBlank
    @Schema(description = "ISO country code", example = "GB")
    private String country;

    @Schema(description = "Whether this address should be the default", example = "true")
    private Boolean isDefault;
}
