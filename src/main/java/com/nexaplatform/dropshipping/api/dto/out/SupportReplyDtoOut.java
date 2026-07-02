package com.nexaplatform.dropshipping.api.dto.out;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Mensaje de un hilo de soporte. {@code fromSupport}=true si lo escribió soporte/admin. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportReplyDtoOut {
    private UUID id;
    private boolean fromSupport;
    private String body;
    private Instant createdAt;
}
