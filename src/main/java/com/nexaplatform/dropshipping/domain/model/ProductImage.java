package com.nexaplatform.dropshipping.domain.model;

import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/** Nested sub-entity of {@link Product}: a single gallery/cover image. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductImage {

    private UUID id;
    private int position;
    private String role;
    private String sourceUrl;
    private String cdnUrl;
    private Integer width;
    private Integer height;
    private Long bytes;
    private String hash;
    private MirrorStatus mirrorStatus;
    private Instant mirroredAt;
}
