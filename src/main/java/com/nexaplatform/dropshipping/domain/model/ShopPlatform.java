package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * Pure domain model for a supported shop platform entry (code + display label).
 * Backs the static {@code platforms} catalog the My Shops API exposes; mappers
 * translate it to the transport DtoOut.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopPlatform {

    private String code;
    private String label;
}
