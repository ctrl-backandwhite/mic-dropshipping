package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

/**
 * Unread notification counter. Field name {@code count} preserves the previous
 * Map key.
 */
@Value
@Builder
public class UnreadCountDtoOut {

    long count;
}
