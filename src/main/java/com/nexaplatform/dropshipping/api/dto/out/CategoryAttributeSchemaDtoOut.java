package com.nexaplatform.dropshipping.api.dto.out;

import java.util.UUID;

/** DROP-670: vista de un atributo esperado por una categoría. */
public record CategoryAttributeSchemaDtoOut(UUID id, UUID categoryId, String attrKey, String label,
        boolean required, int position) {
}
