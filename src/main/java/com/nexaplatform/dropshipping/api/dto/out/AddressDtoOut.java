package com.nexaplatform.dropshipping.api.dto.out;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/** Output view of a user shipping address. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddressDtoOut {

    @Schema(description = "Address id")
    private UUID id;

    @Schema(description = "Optional address label", example = "Home")
    private String label;

    @Schema(description = "Recipient full name", example = "Ada Lovelace")
    private String fullName;

    @Schema(description = "Contact phone")
    private String phone;

    @Schema(description = "Address line 1", example = "1 Babbage Way")
    private String line1;

    @Schema(description = "Address line 2")
    private String line2;

    @Schema(description = "City", example = "London")
    private String city;

    @Schema(description = "State / region / province")
    private String state;

    @Schema(description = "Postal / ZIP code", example = "EC1A1")
    private String postalCode;

    @Schema(description = "ISO country code", example = "GB")
    private String country;

    // Preserve the legacy JSON key "default" so the frontend keeps working.
    @JsonProperty("default")
    @Schema(description = "Whether this is the default address", example = "true")
    private boolean isDefault;

    @Schema(description = "Creation timestamp")
    private Instant createdAt;
}
