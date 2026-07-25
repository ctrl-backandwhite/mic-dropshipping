package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Registro histórico de una operación de un operador (soporte) sobre una orden. Hoy se crea cuando un
 * OPERATOR entrega (DELIVERED) una orden, acreditándole la comisión del 15% del precio en YUAN (CNY)
 * antes del margen. Es la fuente de verdad en Postgres (paginable por rango de fechas) y se replica en
 * OpenSearch para consulta/búsqueda.
 */
@Entity
@Table(name = "operator_order_action")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorOrderActionEntity extends BaseEntity {

    /** Subject del operador (id del usuario autenticado que ejecutó la acción). */
    @Column(name = "operator_subject", nullable = false, length = 80)
    private String operatorSubject;

    @Column(name = "operator_email", length = 160)
    private String operatorEmail;

    @Column(name = "operator_name", length = 160)
    private String operatorName;

    @Column(name = "order_id", nullable = false, columnDefinition = "uuid")
    private UUID orderId;

    @Column(name = "order_number", length = 40)
    private String orderNumber;

    /** Acción que genera la comisión (de momento siempre DELIVERED). */
    @Column(nullable = false, length = 20)
    private String action;

    /** Comisión acreditada al operador, en céntimos de YUAN (15% del CNY de cada línea × cantidad). */
    @Column(name = "commission_cny_cents", nullable = false)
    private long commissionCnyCents;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    /** Origen de la orden (PLATFORM/INTEGRATION) — determina el % aplicado. */
    @Column(name = "order_source", length = 20)
    private String orderSource;

    /** % de comisión aplicado (10 propias, 5 integradas), para trazabilidad. */
    @Column(name = "commission_pct", precision = 5, scale = 2)
    private BigDecimal commissionPct;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
