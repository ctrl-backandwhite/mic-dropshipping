package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryCustomsRuleEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Admin de reglas de despacho aduanero por país: modo fiscal (DDP/DDU), umbral de minimis del régimen
 * simplificado y recargos del transportista por gestionar el despacho.
 *
 * <p>Es la pantalla donde se vuelcan los datos que confirma el transportista (tarifa de handling DDP,
 * recargo por despacho formal) sin necesidad de tocar código ni redesplegar.
 */
@Tag(name = "Admin · Country customs", description = "Umbrales de importación y recargos DDP por país")
@RestController
@RequestMapping("/api/admin/customs-rules")
@RequiredArgsConstructor
public class AdminCountryCustomsController {

    private final CustomsValuationService customsValuationService;

    /** Vista de salida de la regla de un país. */
    public record CustomsRuleDtoOut(String countryCode, String taxMode, BigDecimal deMinimisAmount,
            String deMinimisCurrency, boolean deMinimisApplies, String overThresholdPolicy,
            int handlingFeeCents, int handlingPercentBps,
            int overThresholdSurchargeCents, int dutyRateBps, int vatPrepayPercentBps,
            BigDecimal perArticleFeeAmount, String perArticleFeeCurrency, boolean active) {

        static CustomsRuleDtoOut from(CountryCustomsRuleEntity e) {
            return new CustomsRuleDtoOut(e.getCountryCode(), e.getTaxMode(), e.getDeMinimisAmount(),
                    e.getDeMinimisCurrency(), e.isDeMinimisApplies(), e.getOverThresholdPolicy(),
                    e.getHandlingFeeCents(),
                    e.getHandlingPercentBps(), e.getOverThresholdSurchargeCents(), e.getDutyRateBps(),
                    e.getVatPrepayPercentBps(), e.getPerArticleFeeAmount(), e.getPerArticleFeeCurrency(),
                    e.isActive());
        }
    }

    /**
     * Alta/edición de la regla. {@code deMinimisAmount} va en su divisa legal ({@code deMinimisCurrency});
     * 0 = umbral no configurado, no se evalúa. {@code taxMode} DDP|DDU;
     * {@code overThresholdPolicy} SURCHARGE|ALLOW|BLOCK. Los campos de comisión de prepago
     * ({@code vatPrepayPercentBps}), arancel por artículo ({@code perArticleFee*}) y existencia de
     * franquicia ({@code deMinimisApplies}) son OPCIONALES: si no se envían, se conserva el valor actual
     * (no se pisan los sembrados para la UE).
     *
     * <p>{@code deMinimisApplies = false} marca un destino SIN franquicia —hoy solo Estados Unidos y Puerto
     * Rico—: cualquier importe cuenta como superado y se le aplica {@code overThresholdPolicy}, que en su
     * caso es {@code BLOCK}. Enviarlo a {@code true} reabre el destino. Es {@link Boolean} y no primitivo
     * para que un formulario que lo ignore no bloquee un país mandando {@code false} sin querer.
     */
    public record UpsertCustomsRuleDtoIn(@NotBlank String taxMode,
            @PositiveOrZero BigDecimal deMinimisAmount, @NotBlank String deMinimisCurrency,
            @NotBlank String overThresholdPolicy, @Min(0) int handlingFeeCents, @Min(0) int handlingPercentBps,
            @Min(0) int overThresholdSurchargeCents, @Min(0) int dutyRateBps,
            Integer vatPrepayPercentBps, BigDecimal perArticleFeeAmount, String perArticleFeeCurrency,
            Boolean deMinimisApplies, boolean active) {
    }

    @Operation(summary = "Listar las reglas aduaneras configuradas por país")
    @GetMapping
    public ResponseEntity<List<CustomsRuleDtoOut>> list() {
        return ResponseEntity.ok(customsValuationService.listAll().stream().map(CustomsRuleDtoOut::from).toList());
    }

    @Operation(summary = "Crear o actualizar la regla aduanera de un país")
    @PutMapping("/{country}")
    public ResponseEntity<CustomsRuleDtoOut> upsert(@PathVariable String country,
            @Valid @RequestBody UpsertCustomsRuleDtoIn req) {
        CountryCustomsRuleEntity input = CountryCustomsRuleEntity.builder().countryCode(country)
                .taxMode(req.taxMode()).deMinimisAmount(req.deMinimisAmount())
                .deMinimisCurrency(req.deMinimisCurrency()).overThresholdPolicy(req.overThresholdPolicy())
                .handlingFeeCents(req.handlingFeeCents()).handlingPercentBps(req.handlingPercentBps())
                .overThresholdSurchargeCents(req.overThresholdSurchargeCents()).dutyRateBps(req.dutyRateBps())
                .active(req.active()).build();
        // Los opcionales se pasan tal cual (null = conservar); la decisión la toma el servicio.
        return ResponseEntity.ok(CustomsRuleDtoOut.from(customsValuationService.upsert(input,
                req.vatPrepayPercentBps(), req.perArticleFeeAmount(), req.perArticleFeeCurrency(),
                req.deMinimisApplies())));
    }
}
