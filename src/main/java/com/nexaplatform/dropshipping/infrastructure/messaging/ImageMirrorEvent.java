package com.nexaplatform.dropshipping.infrastructure.messaging;

import java.util.UUID;

public record ImageMirrorEvent(
        UUID imageId,
        UUID productId,
        String sourceUrl,
        String kind,
        int position
) {}
