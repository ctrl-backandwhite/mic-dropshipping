package com.nexaplatform.dropshipping.api.dto.out;

import java.util.List;

/**
 * DROP-690: result of a bulk order import. {@code created} lists the order numbers successfully
 * created; {@code errors} carries a human-readable message per failed row (1-based index).
 */
public record AdminImportResultDtoOut(int imported, int failed, List<String> created, List<String> errors) {
}
