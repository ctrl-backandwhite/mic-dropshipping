package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.CountryTaxService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryRegionEntity;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin de regiones (estado/provincia) por país. Solo ADMIN (bajo /api/admin/**). Permite alta/edición/
 * baja y activar/desactivar regiones, y fijar una tasa propia ({@code rateBps}); si es null usa la nacional.
 */
@Tag(name = "Admin · Country regions")
@RestController
@RequestMapping("/api/admin/regions")
@RequiredArgsConstructor
public class AdminCountryRegionController {

    private final CountryTaxService countryTaxService;

    /** Vista de salida (tasa en % además de bps; ratePercent null = sin override → usa la nacional). */
    public record RegionDtoOut(String countryCode, String regionCode, String regionName, Integer rateBps,
            Double ratePercent, boolean active, int position) {
        static RegionDtoOut from(CountryRegionEntity e) {
            return new RegionDtoOut(e.getCountryCode(), e.getRegionCode(), e.getRegionName(), e.getRateBps(),
                    e.getRateBps() == null ? null : e.getRateBps() / 100.0, e.isActive(), e.getPosition());
        }
    }

    public record UpsertRegionDtoIn(@NotBlank String name, Integer rateBps, boolean active, Integer position) {
    }

    @Operation(summary = "List regions of a country (admin, includes inactive)")
    @GetMapping
    public ResponseEntity<List<RegionDtoOut>> list(@RequestParam String country) {
        return ResponseEntity.ok(countryTaxService.listRegionsAdmin(country).stream().map(RegionDtoOut::from).toList());
    }

    @Operation(summary = "Create/update a region of a country")
    @PutMapping("/{country}/{code}")
    public ResponseEntity<RegionDtoOut> upsert(@PathVariable String country, @PathVariable String code,
            @RequestBody UpsertRegionDtoIn req) {
        return ResponseEntity.ok(RegionDtoOut.from(countryTaxService.regionUpsert(country, code, req.name(),
                req.rateBps(), req.active(), req.position())));
    }

    @Operation(summary = "Delete a region of a country")
    @DeleteMapping("/{country}/{code}")
    public ResponseEntity<Void> delete(@PathVariable String country, @PathVariable String code) {
        countryTaxService.regionDelete(country, code);
        return ResponseEntity.noContent().build();
    }
}
