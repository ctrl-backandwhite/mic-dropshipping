package com.nexaplatform.dropshipping.api.dto.in;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * DROP-690: bulk order import payload. Each row is processed independently so a single bad row does
 * not abort the whole batch — the controller reports per-row failures in {@code AdminImportResultDtoOut}.
 */
public record AdminImportOrdersDtoIn(@NotEmpty List<@Valid AdminCreateOrderDtoIn> orders) {
}
