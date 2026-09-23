package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.config.PersistenceITBase;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OperatorOrderActionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence IT for {@link OperatorOrderActionRepository}: exercises the date-range pagination,
 * existence check and the COALESCE(SUM(...)) aggregation against a real Postgres. The entity has no
 * FKs to other rows; only its own NOT NULL columns must be filled.
 */
class OperatorOrderActionRepositoryIT extends PersistenceITBase {

    @Autowired
    OperatorOrderActionRepository actions;

    private static final Instant T1 = Instant.parse("2026-01-10T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-01-20T00:00:00Z");
    private static final Instant OUTSIDE = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-31T00:00:00Z");

    private OperatorOrderActionEntity action(String subject, UUID orderId, String act, long commission,
            Instant processedAt) {
        return OperatorOrderActionEntity.builder().operatorSubject(subject).operatorEmail(subject + "@nx.local")
                .operatorName("Op " + subject).orderId(orderId).orderNumber("ORD-" + orderId.toString().substring(0, 8))
                .action(act).commissionCnyCents(commission).itemCount(1).orderSource("PLATFORM")
                .processedAt(processedAt).build();
    }

    @Test
    void existsByOrderIdAndAction_trueAndFalse() {
        UUID orderId = UUID.randomUUID();
        actions.save(action("op-1", orderId, "DELIVERED", 1000, T1));
        actions.flush();

        assertThat(actions.existsByOrderIdAndAction(orderId, "DELIVERED")).isTrue();
        assertThat(actions.existsByOrderIdAndAction(orderId, "CANCELLED")).isFalse();
        assertThat(actions.existsByOrderIdAndAction(UUID.randomUUID(), "DELIVERED")).isFalse();
    }

    @Test
    void findByProcessedAtBetween_includesInRangeExcludesOutOfRange() {
        actions.save(action("op-1", UUID.randomUUID(), "DELIVERED", 1000, T1));
        actions.save(action("op-2", UUID.randomUUID(), "DELIVERED", 2000, T2));
        actions.save(action("op-3", UUID.randomUUID(), "DELIVERED", 5000, OUTSIDE));
        actions.flush();

        Page<OperatorOrderActionEntity> page = actions.findByProcessedAtBetween(FROM, TO, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(OperatorOrderActionEntity::getOperatorSubject)
                .containsExactlyInAnyOrder("op-1", "op-2");
    }

    @Test
    void findByOperatorSubjectAndProcessedAtBetween_filtersBySubjectAndRange() {
        actions.save(action("op-1", UUID.randomUUID(), "DELIVERED", 1000, T1));
        actions.save(action("op-1", UUID.randomUUID(), "DELIVERED", 3000, T2));
        actions.save(action("op-1", UUID.randomUUID(), "DELIVERED", 9000, OUTSIDE)); // out of range
        actions.save(action("op-2", UUID.randomUUID(), "DELIVERED", 7000, T1)); // other operator
        actions.flush();

        Page<OperatorOrderActionEntity> page = actions.findByOperatorSubjectAndProcessedAtBetween("op-1", FROM, TO,
                PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).allMatch(a -> a.getOperatorSubject().equals("op-1"));
    }

    @Test
    void countByOperatorSubjectAndProcessedAtBetween_countsOnlyInRange() {
        actions.save(action("op-1", UUID.randomUUID(), "DELIVERED", 1000, T1));
        actions.save(action("op-1", UUID.randomUUID(), "DELIVERED", 2000, T2));
        actions.save(action("op-1", UUID.randomUUID(), "DELIVERED", 4000, OUTSIDE));
        actions.flush();

        assertThat(actions.countByOperatorSubjectAndProcessedAtBetween("op-1", FROM, TO)).isEqualTo(2);
    }

    @Test
    void sumCommissionForOperator_aggregatesInRangeOnly() {
        actions.save(action("op-1", UUID.randomUUID(), "DELIVERED", 1500, T1));
        actions.save(action("op-1", UUID.randomUUID(), "DELIVERED", 2500, T2));
        actions.save(action("op-1", UUID.randomUUID(), "DELIVERED", 9999, OUTSIDE)); // excluded
        actions.save(action("op-2", UUID.randomUUID(), "DELIVERED", 7000, T1)); // other operator
        actions.flush();

        assertThat(actions.sumCommissionForOperator("op-1", FROM, TO)).isEqualTo(4000L);
        // COALESCE: empty aggregate returns 0, never null.
        assertThat(actions.sumCommissionForOperator("op-unknown", FROM, TO)).isZero();
    }

    @Test
    void aggregateByOperator_groupsAndOrdersByCommissionDesc() {
        actions.save(action("op-low", UUID.randomUUID(), "DELIVERED", 1000, T1));
        actions.save(action("op-high", UUID.randomUUID(), "DELIVERED", 5000, T1));
        actions.save(action("op-high", UUID.randomUUID(), "DELIVERED", 3000, T2));
        actions.save(action("op-out", UUID.randomUUID(), "DELIVERED", 9999, OUTSIDE)); // excluded
        actions.flush();

        java.util.List<Object[]> rows = actions.aggregateByOperator(FROM, TO);

        assertThat(rows).hasSize(2);
        // ordered by total CNY desc: op-high (8000) then op-low (1000)
        assertThat(rows.get(0)[0]).isEqualTo("op-high");
        assertThat(((Number) rows.get(0)[3]).longValue()).isEqualTo(2L); // count
        assertThat(((Number) rows.get(0)[4]).longValue()).isEqualTo(8000L); // sum
        assertThat(rows.get(1)[0]).isEqualTo("op-low");
        assertThat(((Number) rows.get(1)[4]).longValue()).isEqualTo(1000L);
    }
}
