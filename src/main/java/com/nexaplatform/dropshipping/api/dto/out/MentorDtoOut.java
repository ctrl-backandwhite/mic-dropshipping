package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.List;
import java.util.UUID;

/** Output view of a mentor profile. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MentorDtoOut {

    @Schema(description = "Mentor profile id")
    private UUID id;

    @Schema(description = "Underlying user id")
    private UUID userId;

    @Schema(description = "Mentor display name")
    private String displayName;

    @Schema(description = "Headline")
    private String headline;

    @Schema(description = "Biography")
    private String bio;

    @Schema(description = "Areas of expertise")
    private List<String> expertise;

    @Schema(description = "Spoken languages")
    private List<String> languages;

    @Schema(description = "Hourly rate in USD cents")
    private int hourlyRateUsdCents;

    @Schema(description = "Mentor timezone")
    private String timezone;

    @Schema(description = "Whether the mentor is active")
    private boolean active;
}
