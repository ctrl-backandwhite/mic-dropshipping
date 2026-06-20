package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OperatorOrderActionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OperatorOrderActionRepository extends JpaRepository<OperatorOrderActionEntity, UUID> {

    boolean existsByOrderIdAndAction(UUID orderId, String action);

    /** Histórico de UN operador, paginado y filtrado por rango de fechas (procesado). */
    Page<OperatorOrderActionEntity> findByOperatorSubjectAndProcessedAtBetween(String operatorSubject, Instant from,
            Instant to, Pageable pageable);

    /** Histórico GLOBAL (admin), paginado y filtrado por rango de fechas. */
    Page<OperatorOrderActionEntity> findByProcessedAtBetween(Instant from, Instant to, Pageable pageable);

    /** Comisión total (CNY céntimos) de un operador en un rango. */
    @Query("SELECT COALESCE(SUM(a.commissionCnyCents),0) FROM OperatorOrderActionEntity a "
            + "WHERE a.operatorSubject = :subject AND a.processedAt BETWEEN :from AND :to")
    long sumCommissionForOperator(@Param("subject") String subject, @Param("from") Instant from,
            @Param("to") Instant to);

    /** Nº de operaciones de un operador en un rango. */
    long countByOperatorSubjectAndProcessedAtBetween(String operatorSubject, Instant from, Instant to);

    /** Agregado por operador (admin): subject, email, nombre, nº operaciones, comisión total CNY. */
    @Query("SELECT a.operatorSubject, MAX(a.operatorEmail), MAX(a.operatorName), COUNT(a), "
            + "COALESCE(SUM(a.commissionCnyCents),0) FROM OperatorOrderActionEntity a "
            + "WHERE a.processedAt BETWEEN :from AND :to GROUP BY a.operatorSubject "
            + "ORDER BY COALESCE(SUM(a.commissionCnyCents),0) DESC")
    List<Object[]> aggregateByOperator(@Param("from") Instant from, @Param("to") Instant to);
}
