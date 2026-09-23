package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.out.OperatorDtos.OperatorAction;
import com.nexaplatform.dropshipping.api.dto.out.OperatorDtos.OperatorActionPage;
import com.nexaplatform.dropshipping.api.dto.out.OperatorDtos.OperatorEarningsSummary;
import com.nexaplatform.dropshipping.api.dto.out.OperatorDtos.OperatorReportRow;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OperatorActionIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OperatorOrderActionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OperatorOrderActionRepository;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lectura del histórico/ganancias de operadores. El histórico se indexa en OpenSearch y la fuente de
 * verdad es Postgres; las consultas son paginadas y filtrables por rango de fechas. La comisión se
 * acumula y muestra en YUAN (CNY).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorEarningsService {

    private static final String CNY = "CNY";

    private final OperatorOrderActionRepository repository;
    private final OperatorActionIndexer indexer;

    /** Resumen del operador autenticado (operaciones + comisión total CNY) en el rango. */
    @Transactional(readOnly = true)
    public OperatorEarningsSummary mySummary(String fromDate, String toDate) {
        Instant from = startOf(fromDate, 90);
        Instant to = endOf(toDate);
        String subject = SecurityUtils.currentSubject();
        long ops = subject == null ? 0 : repository.countByOperatorSubjectAndProcessedAtBetween(subject, from, to);
        long total = subject == null ? 0 : repository.sumCommissionForOperator(subject, from, to);
        return new OperatorEarningsSummary(subject, null, null, ops, total, CNY, from, to);
    }

    /** Histórico paginado del operador autenticado (intenta OpenSearch; si falla, Postgres). */
    @Transactional(readOnly = true)
    public OperatorActionPage myHistory(String fromDate, String toDate, int page, int size) {
        return historyPage(SecurityUtils.currentSubject(), fromDate, toDate, page, size);
    }

    /** Reindexa en OpenSearch todas las acciones de operador (botón admin "Reindexar"). Devuelve el nº indexado. */
    @Transactional(readOnly = true)
    public int reindexAll() {
        int[] n = {0};
        repository.findAll().forEach(a -> {
            indexer.index(a);
            n[0]++;
        });
        log.info("::> [REINDEX] reindexed {} operator actions", n[0]);
        return n[0];
    }

    /** Histórico paginado (admin): de un operador concreto o de todos. */
    @Transactional(readOnly = true)
    public OperatorActionPage history(String operatorSubject, String fromDate, String toDate, int page, int size) {
        return historyPage(operatorSubject, fromDate, toDate, page, size);
    }

    /**
     * Consulta real del histórico, sin anotación transaccional, para que {@link #myHistory} la reutilice
     * sin llamarse a sí misma: la autoinvocación no pasa por el proxy, así que el {@code @Transactional}
     * del método invocado no se aplicaba. La transacción la abre el método público de entrada.
     */
    private OperatorActionPage historyPage(String operatorSubject, String fromDate, String toDate, int page, int size) {
        Instant from = startOf(fromDate, 90);
        Instant to = endOf(toDate);
        int pageSize = Math.clamp(size, 1, 200);
        // Consulta preferente desde OpenSearch (indexado); si no responde, fallback a Postgres.
        try {
            Map<String, Object> res = indexer.search(operatorSubject, from, to, page, pageSize);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> hits = (List<Map<String, Object>>) res.get("items");
            if (hits != null) {
                List<OperatorAction> items = new ArrayList<>();
                for (Map<String, Object> m : hits) {
                    items.add(new OperatorAction(str(m, "operatorSubject"), str(m, "operatorEmail"),
                            str(m, "operatorName"), str(m, "orderId"), str(m, "orderNumber"), str(m, "action"),
                            lng(m, "commissionCnyCents"), (int) lng(m, "itemCount"), inst(m.get("processedAt"))));
                }
                long total = res.get("total") instanceof Number n ? n.longValue() : items.size();
                return new OperatorActionPage(items, total, page, pageSize, CNY);
            }
        } catch (Exception openSearchDown) {
            log.debug("OpenSearch no disponible, fallback a Postgres: {}", openSearchDown.getMessage());
        }
        Page<OperatorOrderActionEntity> p = operatorSubject == null || operatorSubject.isBlank()
                ? repository.findByProcessedAtBetween(from, to, PageRequest.of(page, pageSize))
                : repository.findByOperatorSubjectAndProcessedAtBetween(operatorSubject, from, to,
                        PageRequest.of(page, pageSize));
        List<OperatorAction> items = p.getContent().stream().map(this::toDto).toList();
        return new OperatorActionPage(items, p.getTotalElements(), page, pageSize, CNY);
    }

    /** Reporte admin: agregado por operador (nº operaciones + comisión total CNY) en el rango. */
    @Transactional(readOnly = true)
    public List<OperatorReportRow> adminReport(String fromDate, String toDate) {
        Instant from = startOf(fromDate, 90);
        Instant to = endOf(toDate);
        List<OperatorReportRow> rows = new ArrayList<>();
        for (Object[] r : repository.aggregateByOperator(from, to)) {
            rows.add(new OperatorReportRow((String) r[0], (String) r[1], (String) r[2], ((Number) r[3]).longValue(),
                    ((Number) r[4]).longValue(), CNY));
        }
        return rows;
    }

    /* ---------------- helpers ---------------- */

    private OperatorAction toDto(OperatorOrderActionEntity a) {
        return new OperatorAction(a.getOperatorSubject(), a.getOperatorEmail(), a.getOperatorName(),
                a.getOrderId() != null ? a.getOrderId().toString() : null, a.getOrderNumber(), a.getAction(),
                a.getCommissionCnyCents(), a.getItemCount(), a.getProcessedAt());
    }

    private static Instant startOf(String date, int defaultDaysBack) {
        if (date != null && !date.isBlank()) {
            return LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return LocalDate.now(ZoneOffset.UTC).minusDays(defaultDaysBack).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant endOf(String date) {
        if (date != null && !date.isBlank()) {
            return LocalDate.parse(date).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return Instant.now();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static long lng(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static Instant inst(Object v) {
        try {
            return v == null ? null : Instant.parse(v.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
