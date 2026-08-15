package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.TrendScoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Recálculo manual de la puntuación de tendencia.
 *
 * <p>El cálculo corre solo cada madrugada, pero hace falta poder forzarlo: después de una tanda de pedidos,
 * al corregir datos del catálogo, o simplemente para comprobar que la sección se ha movido sin esperar al
 * día siguiente. Sin este botón, la única forma de verificar un cambio era esperar a las 3:20.
 */
@RestController
@RequestMapping("/api/admin/trend")
@RequiredArgsConstructor
@Tag(name = "Admin · Tendencia")
public class AdminTrendController {

    private final TrendScoreService trendScoreService;

    @Operation(summary = "Recalcular la puntuación de tendencia desde las ventas reales")
    @PostMapping("/recompute")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> recompute() {
        int conVentas = trendScoreService.recompute();
        // Se devuelve cuántos productos tienen ventas, no cuántas filas se actualizaron: lo segundo es
        // siempre el catálogo entero y no dice nada. Lo primero es exactamente cuántos pueden aparecer en
        // «Tendencia ahora», que es lo que el admin quiere saber al pulsar el botón.
        return ResponseEntity.ok(Map.of("productsWithSales", conVentas));
    }
}
