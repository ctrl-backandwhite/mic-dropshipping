package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.out.OperatorDtos.OperatorActionPage;
import com.nexaplatform.dropshipping.api.dto.out.OperatorDtos.OperatorEarningsSummary;
import com.nexaplatform.dropshipping.api.dto.out.OperatorDtos.OperatorReportRow;
import com.nexaplatform.dropshipping.application.service.OperatorEarningsService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OperatorActionIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OperatorOrderActionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OperatorOrderActionRepository;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperatorEarningsServiceTest {

    @Mock
    OperatorOrderActionRepository repository;
    @Mock
    OperatorActionIndexer indexer;
    @InjectMocks
    OperatorEarningsService service;

    private static OperatorOrderActionEntity action(String subject, long cnyCents, int items, Instant processedAt) {
        OperatorOrderActionEntity e = OperatorOrderActionEntity.builder()
                .operatorSubject(subject)
                .operatorEmail("ops@nx036.local")
                .operatorName("Carlos")
                .orderId(UUID.randomUUID())
                .orderNumber("NX-9")
                .action("DELIVERED")
                .commissionCnyCents(cnyCents)
                .itemCount(items)
                .orderSource("PLATFORM")
                .processedAt(processedAt)
                .build();
        e.setId(UUID.randomUUID());
        return e;
    }

    /* ---------------- mySummary ---------------- */

    @Test
    void mySummary_aggregatesOpsAndCommissionForAuthenticatedSubject() {
        String subject = UUID.randomUUID().toString();
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::currentSubject).thenReturn(subject);
            when(repository.countByOperatorSubjectAndProcessedAtBetween(eq(subject), any(), any())).thenReturn(7L);
            when(repository.sumCommissionForOperator(eq(subject), any(), any())).thenReturn(123456L);

            OperatorEarningsSummary summary = service.mySummary("2026-01-01", "2026-01-31");

            assertThat(summary.operatorSubject()).isEqualTo(subject);
            assertThat(summary.operations()).isEqualTo(7L);
            assertThat(summary.totalCommissionCnyCents()).isEqualTo(123456L);
            assertThat(summary.currency()).isEqualTo("CNY");
            // rango explícito: from = inicio del día, to = día siguiente al toDate (exclusivo superior)
            assertThat(summary.from()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(summary.to()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        }
    }

    @Test
    void mySummary_returnsZeroesAndDoesNotQueryWhenNoSubject() {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::currentSubject).thenReturn(null);

            OperatorEarningsSummary summary = service.mySummary("2026-01-01", "2026-01-31");

            assertThat(summary.operatorSubject()).isNull();
            assertThat(summary.operations()).isZero();
            assertThat(summary.totalCommissionCnyCents()).isZero();
            assertThat(summary.currency()).isEqualTo("CNY");
            verifyNoInteractions(repository);
        }
    }

    /* ---------------- history (OpenSearch path) ---------------- */

    @Test
    void history_mapsOpenSearchHitsAndTotalWithoutHittingPostgres() throws Exception {
        Map<String, Object> hit = new HashMap<>();
        hit.put("operatorSubject", "sub-1");
        hit.put("operatorEmail", "ops@nx036.local");
        hit.put("operatorName", "Carlos");
        hit.put("orderId", "order-1");
        hit.put("orderNumber", "NX-9");
        hit.put("action", "DELIVERED");
        hit.put("commissionCnyCents", 2000L);
        hit.put("itemCount", 3L);
        hit.put("processedAt", "2026-01-15T10:00:00Z");
        Map<String, Object> res = new HashMap<>();
        res.put("items", List.of(hit));
        res.put("total", 42L);
        when(indexer.search(eq("sub-1"), any(), any(), eq(0), eq(20))).thenReturn(res);

        OperatorActionPage out = service.history("sub-1", "2026-01-01", "2026-01-31", 0, 20);

        assertThat(out.total()).isEqualTo(42L);
        assertThat(out.size()).isEqualTo(20);
        assertThat(out.currency()).isEqualTo("CNY");
        assertThat(out.items()).hasSize(1);
        assertThat(out.items().get(0).operatorSubject()).isEqualTo("sub-1");
        assertThat(out.items().get(0).commissionCnyCents()).isEqualTo(2000L);
        assertThat(out.items().get(0).itemCount()).isEqualTo(3);
        assertThat(out.items().get(0).processedAt()).isEqualTo(Instant.parse("2026-01-15T10:00:00Z"));
        verify(repository, never()).findByProcessedAtBetween(any(), any(), any());
        verify(repository, never()).findByOperatorSubjectAndProcessedAtBetween(any(), any(), any(), any());
    }

    @Test
    void history_clampsPageSizeTo200() throws Exception {
        when(indexer.search(any(), any(), any(), eq(0), eq(200))).thenReturn(Map.of("items", List.of(), "total", 0L));

        OperatorActionPage out = service.history("sub-1", null, null, 0, 9999);

        assertThat(out.size()).isEqualTo(200);
    }

    /* ---------------- history (Postgres fallback) ---------------- */

    @Test
    void history_fallsBackToPostgresForSpecificOperatorWhenOpenSearchFails() throws Exception {
        when(indexer.search(any(), any(), any(), anyInt(), anyInt())).thenThrow(new RuntimeException("down"));
        OperatorOrderActionEntity e = action("sub-1", 500, 1, Instant.parse("2026-01-10T00:00:00Z"));
        Page<OperatorOrderActionEntity> page = new PageImpl<>(List.of(e), PageRequest.of(0, 20), 1);
        when(repository.findByOperatorSubjectAndProcessedAtBetween(eq("sub-1"), any(), any(), any())).thenReturn(page);

        OperatorActionPage out = service.history("sub-1", "2026-01-01", "2026-01-31", 0, 20);

        assertThat(out.total()).isEqualTo(1L);
        assertThat(out.items()).hasSize(1);
        assertThat(out.items().get(0).operatorSubject()).isEqualTo("sub-1");
        assertThat(out.items().get(0).commissionCnyCents()).isEqualTo(500L);
        assertThat(out.items().get(0).orderId()).isEqualTo(e.getOrderId().toString());
        verify(repository, never()).findByProcessedAtBetween(any(), any(), any());
    }

    @Test
    void history_fallsBackToGlobalPostgresQueryWhenSubjectBlankAndOpenSearchFails() throws Exception {
        when(indexer.search(any(), any(), any(), anyInt(), anyInt())).thenThrow(new RuntimeException("down"));
        OperatorOrderActionEntity e = action("sub-x", 700, 2, Instant.parse("2026-01-10T00:00:00Z"));
        // pageSize pequeño para que PageImpl NO normalice el total a offset+contentSize (preserva 5).
        Page<OperatorOrderActionEntity> page = new PageImpl<>(List.of(e), PageRequest.of(0, 1), 5);
        when(repository.findByProcessedAtBetween(any(), any(), any())).thenReturn(page);

        OperatorActionPage out = service.history("  ", "2026-01-01", "2026-01-31", 0, 20);

        assertThat(out.total()).isEqualTo(5L);
        assertThat(out.items()).hasSize(1);
        verify(repository, never()).findByOperatorSubjectAndProcessedAtBetween(any(), any(), any(), any());
    }

    /* ---------------- myHistory delegates to history with current subject ---------------- */

    @Test
    void myHistory_usesAuthenticatedSubject() throws Exception {
        try (MockedStatic<SecurityUtils> sec = mockStatic(SecurityUtils.class)) {
            sec.when(SecurityUtils::currentSubject).thenReturn("me-1");
            when(indexer.search(eq("me-1"), any(), any(), eq(0), eq(10)))
                    .thenReturn(Map.of("items", List.of(), "total", 0L));

            OperatorActionPage out = service.myHistory(null, null, 0, 10);

            assertThat(out.total()).isZero();
            verify(indexer).search(eq("me-1"), any(), any(), eq(0), eq(10));
        }
    }

    /* ---------------- adminReport ---------------- */

    @Test
    void adminReport_mapsAggregateRowsWithTotals() {
        Object[] row = new Object[]{"sub-1", "ops@nx036.local", "Carlos", 9L, 90000L};
        when(repository.aggregateByOperator(any(), any())).thenReturn(List.<Object[]>of(row));

        List<OperatorReportRow> rows = service.adminReport("2026-01-01", "2026-01-31");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).operatorSubject()).isEqualTo("sub-1");
        assertThat(rows.get(0).operatorEmail()).isEqualTo("ops@nx036.local");
        assertThat(rows.get(0).operations()).isEqualTo(9L);
        assertThat(rows.get(0).totalCommissionCnyCents()).isEqualTo(90000L);
        assertThat(rows.get(0).currency()).isEqualTo("CNY");
    }

    @Test
    void adminReport_defaultsToNinetyDayWindowWhenDatesNull() {
        ArgumentCaptor<Instant> fromCap = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCap = ArgumentCaptor.forClass(Instant.class);
        when(repository.aggregateByOperator(fromCap.capture(), toCap.capture())).thenReturn(List.of());

        service.adminReport(null, null);

        // from por defecto ~90 días atrás, to ~ahora; ventana de al menos 89 días.
        long days = java.time.Duration.between(fromCap.getValue(), toCap.getValue()).toDays();
        assertThat(days).isBetween(89L, 91L);
    }
}
