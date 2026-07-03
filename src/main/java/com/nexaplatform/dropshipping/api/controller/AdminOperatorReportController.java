package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.out.OperatorDtos.OperatorActionPage;
import com.nexaplatform.dropshipping.api.dto.out.OperatorDtos.OperatorReportRow;
import com.nexaplatform.dropshipping.application.service.OperatorEarningsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Reporte de operadores para el ADMIN: cuántas operaciones ha procesado con éxito cada operador y su
 * comisión acumulada en CNY, además del histórico global paginado por rango de fechas. Securizado en
 * {@code /api/admin/operators/**} → SOLO ADMIN (la ruta no cae en el matcher de OPERATOR).
 */
@Tag(name = "Admin · Operators", description = "Reporte de operaciones y comisiones por operador (solo admin)")
@RestController
@RequestMapping("/api/admin/operators")
@RequiredArgsConstructor
public class AdminOperatorReportController {

    private final OperatorEarningsService earningsService;

    @Operation(summary = "Operaciones procesadas y comisión (CNY) por operador en el rango")
    @GetMapping("/report")
    public ResponseEntity<List<OperatorReportRow>> report(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(earningsService.adminReport(from, to));
    }

    @Operation(summary = "Histórico global de operaciones, filtrable por operador y rango de fechas, paginado")
    @GetMapping("/history")
    public ResponseEntity<OperatorActionPage> history(@RequestParam(required = false) String operator,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(earningsService.history(operator, from, to, page, size));
    }

    @Operation(summary = "Reindexa todas las acciones de operador en OpenSearch (solo admin)")
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        return ResponseEntity.ok(Map.of("indexed", earningsService.reindexAll()));
    }
}
