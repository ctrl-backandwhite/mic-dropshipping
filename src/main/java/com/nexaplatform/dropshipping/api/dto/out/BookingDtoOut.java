package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/** Output view of a mentor booking. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingDtoOut {

    @Schema(description = "Booking id")
    private UUID id;

    @Schema(description = "Mentor profile id")
    private UUID mentorId;

    @Schema(description = "Mentor display name")
    private String mentorName;

    @Schema(description = "Session start time")
    private Instant startsAt;

    @Schema(description = "Duration in minutes")
    private int durationMin;

    @Schema(description = "Booking status")
    private String status;

    @Schema(description = "Session topic")
    private String topic;
}
