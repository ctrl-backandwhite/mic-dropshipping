package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/** Output view of an academy course. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseDtoOut {

    @Schema(description = "Course id")
    private UUID id;

    @Schema(description = "URL slug")
    private String slug;

    @Schema(description = "Course title")
    private String title;

    @Schema(description = "Course description")
    private String description;

    @Schema(description = "Instructor name")
    private String instructor;

    @Schema(description = "Duration in minutes")
    private Integer durationMinutes;

    @Schema(description = "Cover image URL")
    private String coverUrl;

    @Schema(description = "Video URL")
    private String videoUrl;

    @Schema(description = "Course locale")
    private String locale;

    @Schema(description = "Difficulty level")
    private String level;

    @Schema(description = "Creation timestamp")
    private Instant createdAt;
}
