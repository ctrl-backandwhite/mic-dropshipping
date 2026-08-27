package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    @Size(max = 60)
    @Schema(description = "Optional address label", example = "Home")
    private String label;

    @NotBlank
    @Size(max = 120)
    @Schema(description = "Recipient full name", example = "Ada Lovelace")
    private String fullName;

    @Size(max = 40)
    @Schema(description = "Contact phone")
    private String phone;

    @NotBlank
    @Size(max = 200)
    @Schema(description = "Address line 1", example = "1 Babbage Way")
    private String line1;

    @Size(max = 200)
    @Schema(description = "Address line 2")
    private String line2;

    @NotBlank
    @Size(max = 100)
    @Schema(description = "City", example = "London")
    private String city;

    @Size(max = 100)
    @Schema(description = "State / region / province")
    private String state;

    @Size(max = 20)
    @Schema(description = "Postal / ZIP code", example = "EC1A1")
    private String postalCode;

    @NotBlank
    @Size(max = 2)
    @Schema(description = "ISO country code", example = "GB")
    private String country;

    @Schema(description = "Whether this address should be the default", example = "true")
    private Boolean isDefault;
}
