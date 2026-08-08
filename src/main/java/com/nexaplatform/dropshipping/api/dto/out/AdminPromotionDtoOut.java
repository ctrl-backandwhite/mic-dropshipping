package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Una promoción tal y como la ve el admin. */
@Value
@Builder(toBuilder = true)
public class AdminPromotionDtoOut {

    UUID id;
    String name;
    String code;
    String kind;
    String scope;
    BigDecimal percentOff;
    Integer amountOffCents;
    Instant startsAt;
    Instant endsAt;
    boolean active;
    /** Vigente AHORA: activa, dentro de fechas y sin agotar. Es lo que decide si rebaja de verdad. */
    boolean live;
    int priority;
    Integer maxUses;
    int usedCount;
    Integer minOrderCents;
    List<UUID> categoryIds;
    List<UUID> productIds;
    Instant createdAt;
}
