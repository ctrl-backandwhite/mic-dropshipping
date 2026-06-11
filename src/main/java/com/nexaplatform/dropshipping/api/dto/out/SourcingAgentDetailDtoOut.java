package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** DROP-3: full sourcing agent profile view. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourcingAgentDetailDtoOut {

    @Schema(description = "Agent id")
    private UUID id;

    @Schema(description = "Display name")
    private String displayName;

    @Schema(description = "Agent tier")
    private String tier;

    @Schema(description = "Agent bio")
    private String bio;

    @Schema(description = "Avatar URL")
    private String avatarUrl;

    @Schema(description = "Spoken languages")
    private List<String> languages;

    @Schema(description = "Success rate")
    private BigDecimal successRate;

    @Schema(description = "Average response time in hours")
    private BigDecimal avgResponseHours;

    @Schema(description = "Satisfaction score")
    private BigDecimal satisfaction;

    @Schema(description = "Number of completed jobs")
    private int completedJobs;

    @Schema(description = "Hourly rate in USD cents")
    private Integer hourlyRateUsdCents;
}
