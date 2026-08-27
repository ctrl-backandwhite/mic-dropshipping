package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.CarrierChannelLimitService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CarrierChannelLimitEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin de los límites de bulto del transportista: peso máximo, divisor volumétrico, mínimo facturable y
 * medidas de cada canal en cada país.
 *
 * <p>Es la pantalla donde se vuelca lo que publica la cotización (sección 六、重量要求 de cada línea) sin
 * tocar código ni redesplegar. Importa que se pueda cambiar en caliente: el transportista revisa sus
 * topes por país sin avisar, y hasta ahora corregirlos significaba cambiar una variable de entorno que
 * además valía para todos los destinos a la vez.
 *
 * <p>El país {@code *} es el valor por defecto del canal. Borrar la excepción de un país no lo deja sin
 * tope: devuelve ese destino al valor del canal.
 */
@Tag(name = "Admin · Carrier limits", description = "Peso y medidas máximas por canal y país")
@RestController
@RequestMapping("/api/admin/carrier-limits")
@RequiredArgsConstructor
public class AdminCarrierLimitsController {

    private final CarrierChannelLimitService carrierChannelLimitService;

    /** Vista de salida del límite de un (canal, país). */
    public record CarrierLimitDtoOut(String channelCode, String countryCode, int maxWeightGrams,
            int volumetricDivisor, int minBillableGrams, int maxLengthMm, int maxWidthMm, int maxHeightMm,
            boolean singleParcelOnly, String notes, boolean active) {

        static CarrierLimitDtoOut from(CarrierChannelLimitEntity e) {
            return new CarrierLimitDtoOut(e.getChannelCode(), e.getCountryCode(), e.getMaxWeightGrams(),
                    e.getVolumetricDivisor(), e.getMinBillableGrams(), e.getMaxLengthMm(), e.getMaxWidthMm(),
                    e.getMaxHeightMm(), e.isSingleParcelOnly(), e.getNotes(), e.isActive());
        }
    }

    /**
     * Alta/edición del límite. Todos los importes van en gramos o milímetros, y <b>0 significa siempre
     * «sin dato»</b>: sin tope de peso no se reparte por peso, sin divisor no se aplica peso volumétrico
     * y sin mínimo no hay suelo de facturación. Es deliberado: un cero no puede convertirse en un límite
     * inventado que impida despachar.
     */
    public record UpsertCarrierLimitDtoIn(@Min(0) int maxWeightGrams, @Min(0) int volumetricDivisor,
            @Min(0) int minBillableGrams, @Min(0) int maxLengthMm, @Min(0) int maxWidthMm,
            @Min(0) int maxHeightMm, boolean singleParcelOnly, @Size(max = 200) String notes,
            boolean active) {
    }

    @Operation(summary = "Listar los límites de bulto por canal y país")
    @GetMapping
    public ResponseEntity<List<CarrierLimitDtoOut>> list() {
        return ResponseEntity.ok(carrierChannelLimitService.listAll().stream()
                .map(CarrierLimitDtoOut::from).toList());
    }

    @Operation(summary = "Crear o actualizar el límite de un canal en un país")
    @PutMapping("/{channel}/{country}")
    public ResponseEntity<CarrierLimitDtoOut> upsert(@PathVariable String channel,
            @PathVariable String country, @Valid @RequestBody UpsertCarrierLimitDtoIn req) {
        CarrierChannelLimitEntity input = CarrierChannelLimitEntity.builder()
                .channelCode(channel).countryCode(country)
                .maxWeightGrams(req.maxWeightGrams()).volumetricDivisor(req.volumetricDivisor())
                .minBillableGrams(req.minBillableGrams()).maxLengthMm(req.maxLengthMm())
                .maxWidthMm(req.maxWidthMm()).maxHeightMm(req.maxHeightMm())
                .singleParcelOnly(req.singleParcelOnly()).notes(req.notes()).active(req.active())
                .build();
        return ResponseEntity.ok(CarrierLimitDtoOut.from(carrierChannelLimitService.upsert(input)));
    }

    /**
     * Baja del límite de un (canal, país).
     *
     * <p>404 cuando no había nada que borrar, en vez de 204 a secas: si el par estaba mal escrito, un
     * «hecho» dejaría al administrador creyendo que ha retirado una excepción que sigue en pie.
     */
    @Operation(summary = "Borrar el límite de un canal en un país")
    @DeleteMapping("/{channel}/{country}")
    public ResponseEntity<Void> delete(@PathVariable String channel, @PathVariable String country) {
        return carrierChannelLimitService.delete(channel, country)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
