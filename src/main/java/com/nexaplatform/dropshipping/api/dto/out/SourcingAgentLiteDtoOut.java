package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.util.UUID;

/** DROP-3: lightweight sourcing agent view embedded in quotes and listings. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourcingAgentLiteDtoOut {

    @Schema(description = "Agent id")
    private UUID id;

    @Schema(description = "Display name")
    private String displayName;

    @Schema(description = "Agent tier")
    private String tier;

    @Schema(description = "Satisfaction score")
    private BigDecimal satisfaction;

    @Schema(description = "Number of completed jobs")
    private int completedJobs;

    @Schema(description = "Avatar URL")
    private String avatarUrl;
}
