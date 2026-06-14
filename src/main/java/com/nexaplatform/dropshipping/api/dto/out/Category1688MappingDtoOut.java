package com.nexaplatform.dropshipping.api.dto.out;

import java.util.UUID;

/** DROP-677: vista de un mapeo categoría 1688 → categoría interna. */
public record Category1688MappingDtoOut(UUID id, String external1688Id, String external1688Name,
        UUID categoryId, String categorySlug, String categoryName) {
}
