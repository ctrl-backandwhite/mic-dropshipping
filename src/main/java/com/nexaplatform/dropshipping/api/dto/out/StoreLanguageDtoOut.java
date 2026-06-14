package com.nexaplatform.dropshipping.api.dto.out;

import java.util.UUID;

/** Vista de un idioma de la tienda. */
public record StoreLanguageDtoOut(UUID id, String code, String label, String flag, int position,
        boolean active, boolean isDefault) {
}
