package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * Small domain result of a currency rate synchronization command. Carries the
 * number of updated rates and a human-readable message; the API maps it to the
 * transport {@code CurrencySyncResultDtoOut}.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrencySyncResult {

    private int updated;
    private String message;
}
