package com.nexaplatform.dropshipping.infrastructure.messaging;

import java.util.UUID;

public record ProductIngestedEvent(UUID productId, String slug, String source, String externalId) {
}
