package com.nexaplatform.dropshipping.api.dto.out;

import java.time.Instant;
import java.util.List;

/** DTOs de salida del área de operadores (soporte): comisión acumulada en CNY e histórico de operaciones. */
public final class OperatorDtos {

    private OperatorDtos() {
    }

    /** Resumen de ganancias de un operador en un rango: nº operaciones + comisión total (CNY). */
    public record OperatorEarningsSummary(String operatorSubject, String operatorEmail, String operatorName,
            long operations, long totalCommissionCnyCents, String currency, Instant from, Instant to) {
    }

    /** Una operación del histórico (entrega de una orden con su comisión en CNY). */
    public record OperatorAction(String operatorSubject, String operatorEmail, String operatorName, String orderId,
            String orderNumber, String action, long commissionCnyCents, int itemCount, Instant processedAt) {
    }

    /** Página del histórico de operaciones. */
    public record OperatorActionPage(List<OperatorAction> items, long total, int page, int size, String currency) {
    }

    /** Fila del reporte admin: agregados por operador. */
    public record OperatorReportRow(String operatorSubject, String operatorEmail, String operatorName, long operations,
            long totalCommissionCnyCents, String currency) {
    }
}
