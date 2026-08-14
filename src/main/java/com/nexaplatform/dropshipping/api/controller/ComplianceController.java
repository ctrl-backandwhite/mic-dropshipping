package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.EuComplianceService;
import com.nexaplatform.dropshipping.application.service.EuComplianceService.ResponsiblePersonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Datos de cumplimiento que el comprador tiene derecho a ver.
 *
 * <p>Es público a propósito: el art. 16.3 del Reglamento (UE) 2023/988 obliga a que el operador económico
 * establecido en la Unión figure de forma accesible, y el art. 19.b lo exige en la propia oferta en línea.
 * Un dato que la ley manda publicar no puede quedar detrás del muro de cuenta.
 */
@Tag(name = "Compliance")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final EuComplianceService complianceService;

    /**
     * Operador económico de la UE, o 204 si todavía no hay uno publicable.
     *
     * <p>Se responde 204 y no 404 porque la ausencia es un estado válido de configuración, no un error de
     * ruta: el escaparate simplemente no pinta el bloque, sin ensuciar los registros con 404 en cada carga.
     */
    @Operation(summary = "EU responsible economic operator (Reg. (EU) 2023/988 art. 16.3)")
    @GetMapping("/responsible-person")
    public ResponseEntity<ResponsiblePersonView> responsiblePerson(
            @RequestParam(defaultValue = "es") String lang) {
        return complianceService.publishedResponsible(lang)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
