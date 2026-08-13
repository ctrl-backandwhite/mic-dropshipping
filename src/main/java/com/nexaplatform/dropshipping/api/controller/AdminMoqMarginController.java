package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.MarginService.MoqMarginView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Ajuste de margen para productos con pedido mínimo (MOQ &gt; 1). No es una regla de {@code price_rule}
 * (no define un margen propio, sino un modificador que reduce a {@code factorPercent}% el margen que
 * corresponda), por eso vive en su propio endpoint y la UI lo muestra como una tarjeta aparte en
 * "Reglas de margen".
 */
@Tag(name = "Admin Pricing")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/pricing/moq-rule")
public class AdminMoqMarginController {

    private final MarginService marginService;

    /** Cuerpo de actualización del ajuste MOQ. */
    public record MoqMarginDtoIn(
            @NotNull Boolean enabled,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal factorPercent) {
    }

    @Operation(summary = "Get the MOQ>1 margin adjustment (enabled + factor percent)")
    @GetMapping
    public ResponseEntity<MoqMarginView> get() {
        return ResponseEntity.ok(marginService.getMoqMargin());
    }

    @Operation(summary = "Update the MOQ>1 margin adjustment (enabled + factor percent 0..100)")
    @PutMapping
    public ResponseEntity<MoqMarginView> update(@jakarta.validation.Valid @RequestBody MoqMarginDtoIn req) {
        return ResponseEntity.ok(marginService.updateMoqMargin(req.enabled(), req.factorPercent()));
    }
}
