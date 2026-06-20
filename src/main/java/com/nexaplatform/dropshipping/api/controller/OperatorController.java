package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.out.OperatorDtos.OperatorActionPage;
import com.nexaplatform.dropshipping.api.dto.out.OperatorDtos.OperatorEarningsSummary;
import com.nexaplatform.dropshipping.application.service.OperatorEarningsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Área del propio operador (soporte): su comisión acumulada en CNY y su histórico de operaciones,
 * paginado y filtrable por rango de fechas. Securizado en {@code /api/admin/operator/**} para
 * ADMIN y OPERATOR (cada operador ve SOLO lo suyo: el subject sale del contexto de seguridad).
 */
@Tag(name = "Operator", description = "Ganancias e histórico del operador (soporte)")
@RestController
@RequestMapping("/api/admin/operator")
@RequiredArgsConstructor
public class OperatorController {

    private final OperatorEarningsService earningsService;

    @Operation(summary = "Resumen de mis ganancias (operaciones + comisión total en CNY)")
    @GetMapping("/earnings")
    public ResponseEntity<OperatorEarningsSummary> myEarnings(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(earningsService.mySummary(from, to));
    }

    @Operation(summary = "Mi histórico de operaciones, paginado y filtrado por rango de fechas")
    @GetMapping("/history")
    public ResponseEntity<OperatorActionPage> myHistory(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(earningsService.myHistory(from, to, page, size));
    }
}
