package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/** DROP-3: view of a sourcing request, including its quotes count. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourcingRequestDtoOut {

    @Schema(description = "Request id")
    private UUID id;

    @Schema(description = "Original source URL")
    private String sourceUrl;

    @Schema(description = "Detected source marketplace")
    private String source;

    @Schema(description = "External id at the source")
    private String externalId;

    @Schema(description = "Request status")
    private String status;

    @Schema(description = "Title hint provided by the user")
    private String titleHint;

    @Schema(description = "Free-form notes")
    private String notes;

    @Schema(description = "Selected winning quote id")
    private UUID selectedQuoteId;

    @Schema(description = "Creation timestamp")
    private Instant createdAt;

    @Schema(description = "Number of quotes received")
    private int quotesCount;
}
