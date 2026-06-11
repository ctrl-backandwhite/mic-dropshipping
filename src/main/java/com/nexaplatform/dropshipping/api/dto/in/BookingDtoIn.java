package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/** Input payload to book a session with a mentor. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingDtoIn {

    @NotNull
    @Schema(description = "Mentor profile id")
    private UUID mentorId;

    @Schema(description = "Requested session start time")
    private Instant startsAt;

    @Schema(description = "Requested duration in minutes; defaults to 60")
    private Integer durationMin;

    @Schema(description = "Session topic")
    private String topic;
}
