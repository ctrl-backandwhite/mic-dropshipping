package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/** DROP-3: view of a competing quote for a sourcing request. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourcingQuoteDtoOut {

    @Schema(description = "Quote id")
    private UUID id;

    @Schema(description = "Parent request id")
    private UUID requestId;

    @Schema(description = "Quoting agent (may be null)")
    private SourcingAgentLiteDtoOut agent;

    @Schema(description = "Quoted price in USD cents")
    private int priceUsdCents;

    @Schema(description = "Estimated delivery in days")
    private int etaDays;

    @Schema(description = "Minimum order quantity")
    private Integer moq;

    @Schema(description = "Quote notes")
    private String notes;

    @Schema(description = "Quote status")
    private String status;

    @Schema(description = "Creation timestamp")
    private Instant createdAt;
}
