package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Output view of an academy course enrollment. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnrollmentDtoOut {

    @Schema(description = "Enrollment id")
    private UUID id;

    @Schema(description = "Course id")
    private UUID courseId;

    @Schema(description = "Course slug")
    private String courseSlug;

    @Schema(description = "Course title")
    private String courseTitle;

    @Schema(description = "Progress percentage")
    private BigDecimal progressPct;

    @Schema(description = "Completion timestamp; null when not completed")
    private Instant completedAt;
}
