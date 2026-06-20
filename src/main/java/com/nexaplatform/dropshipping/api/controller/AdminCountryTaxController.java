package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryTaxRateEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
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
 * Admin de impuestos por país. Configura la tasa (IVA/sales tax) que se aplica al crear pedidos con destino
 * en ese país; el impuesto se incluye en el total, el cobro y la factura.
 */
@Tag(name = "Admin · Country tax")
@RestController
@RequestMapping("/api/admin/tax-rates")
@RequiredArgsConstructor
public class AdminCountryTaxController {

    private final CountryTaxService countryTaxService;

    /** Vista de salida (la tasa en % además de bps para la UI). */
    public record CountryTaxDtoOut(String countryCode, String label, int rateBps, double ratePercent, boolean active) {
        static CountryTaxDtoOut from(CountryTaxRateEntity e) {
            return new CountryTaxDtoOut(e.getCountryCode(), e.getLabel(), e.getRateBps(), e.getRateBps() / 100.0,
                    e.isActive());
        }
    }

    public record UpsertTaxDtoIn(@NotBlank String label, int rateBps, boolean active) {
    }

    @Operation(summary = "List configured country tax rates")
    @GetMapping
    public ResponseEntity<List<CountryTaxDtoOut>> list() {
        return ResponseEntity.ok(countryTaxService.listAll().stream().map(CountryTaxDtoOut::from).toList());
    }

    @Operation(summary = "Create/update the tax rate for a country")
    @PutMapping("/{country}")
    public ResponseEntity<CountryTaxDtoOut> upsert(@PathVariable String country, @RequestBody UpsertTaxDtoIn req) {
        return ResponseEntity
                .ok(CountryTaxDtoOut.from(countryTaxService.upsert(country, req.label(), req.rateBps(), req.active())));
    }

    @Operation(summary = "Delete a country tax rate")
    @DeleteMapping("/{country}")
    public ResponseEntity<Void> delete(@PathVariable String country) {
        countryTaxService.delete(country);
        return ResponseEntity.noContent().build();
    }
}
