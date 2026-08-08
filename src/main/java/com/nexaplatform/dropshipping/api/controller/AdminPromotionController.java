package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminPromotionApi;
import com.nexaplatform.dropshipping.api.dto.in.AdminPromotionDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminPromotionDtoOut;
import com.nexaplatform.dropshipping.application.service.PromotionAdminService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionTargetEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Rebajas, cupones y promociones. */
@RestController
@RequestMapping("/api/admin/promotions")
@RequiredArgsConstructor
public class AdminPromotionController implements AdminPromotionApi {

    private final PromotionAdminService promotionAdminService;

    @Override
    public ResponseEntity<List<AdminPromotionDtoOut>> list() {
        return ResponseEntity.ok(promotionAdminService.list().stream().map(this::toDto).toList());
    }

    @Override
    public ResponseEntity<AdminPromotionDtoOut> create(AdminPromotionDtoIn body) {
        return ResponseEntity.ok(toDto(promotionAdminService.create(body)));
    }

    @Override
    public ResponseEntity<AdminPromotionDtoOut> update(UUID id, AdminPromotionDtoIn body) {
        return ResponseEntity.ok(toDto(promotionAdminService.update(id, body)));
    }

    @Override
    public ResponseEntity<AdminPromotionDtoOut> toggle(UUID id) {
        return ResponseEntity.ok(toDto(promotionAdminService.toggle(id)));
    }

    @Override
    public ResponseEntity<Map<String, Object>> announce(UUID id) {
        PromotionEntity p = promotionAdminService.list().stream().filter(x -> x.getId().equals(id))
                .findFirst().orElseThrow();
        Map<String, Object> out = new HashMap<>();
        out.put("notified", promotionAdminService.announce(p));
        return ResponseEntity.ok(out);
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        promotionAdminService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private AdminPromotionDtoOut toDto(PromotionEntity p) {
        List<PromotionTargetEntity> targets = promotionAdminService.targetsOf(p.getId());
        return AdminPromotionDtoOut.builder()
                .id(p.getId()).name(p.getName()).code(p.getCode())
                .kind(p.getKind() != null ? p.getKind().name() : null)
                .scope(p.getScope() != null ? p.getScope().name() : null)
                .percentOff(p.getPercentOff()).amountOffCents(p.getAmountOffCents())
                .startsAt(p.getStartsAt()).endsAt(p.getEndsAt())
                .active(p.isActive())
                // `active` es lo que marcó el admin; `live` es si rebaja AHORA. Se distinguen porque una
                // promoción activa pero fuera de fechas no descuenta nada, y sin este dato la pantalla
                // diría que sí.
                .live(p.isLiveAt(Instant.now()))
                .priority(p.getPriority()).maxUses(p.getMaxUses()).usedCount(p.getUsedCount())
                .minOrderCents(p.getMinOrderCents())
                .categoryIds(targets.stream().map(PromotionTargetEntity::getCategoryId)
                        .filter(Objects::nonNull).toList())
                .productIds(targets.stream().map(PromotionTargetEntity::getProductId)
                        .filter(Objects::nonNull).toList())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
