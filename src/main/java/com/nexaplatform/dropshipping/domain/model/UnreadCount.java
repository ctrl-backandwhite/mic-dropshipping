package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/**
 * Small domain result wrapping the unread-notification counter. The API maps it
 * to the transport {@code UnreadCountDtoOut} (field name {@code count} preserved).
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnreadCount {

    private long count;
}
