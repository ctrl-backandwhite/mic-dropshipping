package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Alta o edición de una rebaja, cupón o promoción desde el admin. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPromotionDtoIn {

    @NotBlank
    @Schema(description = "Nombre visible: «Rebajas de invierno», «Black Friday»…")
    private String name;

    @Schema(description = "Código del cupón. Vacío = rebaja automática, visible en el catálogo")
    private String code;

    @Schema(description = "SEASONAL, FLASH, CLEARANCE, COUPON o REFERRAL")
    private String kind;

    @Schema(description = "ALL, CATEGORY o PRODUCT")
    private String scope;

    @Schema(description = "Porcentaje de descuento (1..99). Excluyente con amountOffCents")
    private BigDecimal percentOff;

    @Schema(description = "Descuento fijo en céntimos. Excluyente con percentOff")
    private Integer amountOffCents;

    private Instant startsAt;
    private Instant endsAt;
    private Boolean active;
    private Integer priority;
    private Integer maxUses;
    private Integer minOrderCents;

    @Schema(description = "Categorías a las que alcanza (con sus subcategorías), si el alcance es CATEGORY")
    private List<UUID> categoryIds;

    @Schema(description = "Productos concretos, si el alcance es PRODUCT")
    private List<UUID> productIds;

    @Schema(description = "Avisar a los usuarios de que la promoción arranca")
    private Boolean notifyUsers;
}
