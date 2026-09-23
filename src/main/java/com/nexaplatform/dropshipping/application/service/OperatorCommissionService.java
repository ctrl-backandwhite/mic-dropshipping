package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OperatorActionIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OperatorOrderActionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OperatorOrderActionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * Acredita al operador (soporte) la comisión por procesar una orden. Regla: al ENTREGAR (DELIVERED), el
 * operador que ejecuta la acción gana el <b>15% del precio en YUAN (CNY) antes del margen</b> de cada
 * línea × cantidad, acumulado en CNY. Cada operación se registra en {@code operator_order_action}
 * (histórico, fuente de verdad) y se indexa en OpenSearch para consulta paginada por rango de fechas.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorCommissionService {

    private static final String ACTION_DELIVERED = "DELIVERED";
    // % de comisión del operador según el ORIGEN de la orden.
    private static final BigDecimal PCT_PLATFORM = new BigDecimal("10"); // tienda propia
    private static final BigDecimal PCT_INTEGRATION = new BigDecimal("5"); // tienda integrada
    // IVA chino (13%) incluido en el precio CNY del proveedor. La comisión se calcula sobre la BASE sin IVA:
    // base = precioCNY / 1.13 (p. ej. 113 → 100). NO se calcula sobre el precio con IVA.
    private static final BigDecimal IVA_DIVISOR = new BigDecimal("1.13");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OperatorOrderActionRepository actionRepository;
    private final OperatorActionIndexer indexer;
    private final UserRepository userRepository;

    /**
     * Registra la entrega de una orden por el operador autenticado y le acredita la comisión. Idempotente
     * por (order, DELIVERED). Si no hay actor autenticado (proceso del sistema), no acredita nada.
     */
    @Transactional
    public void recordDelivery(Order order) {
        String subject = SecurityUtils.currentSubject();
        if (subject == null || order == null || order.getId() == null) {
            return;
        }
        if (actionRepository.existsByOrderIdAndAction(order.getId(), ACTION_DELIVERED)) {
            return; // ya acreditada
        }
        // % según el ORIGEN de la orden: 10% propias (PLATFORM), 5% integradas (INTEGRATION).
        boolean integration = "INTEGRATION".equalsIgnoreCase(order.getSource());
        BigDecimal pct = integration ? PCT_INTEGRATION : PCT_PLATFORM;

        // Base imponible de la comisión = suma de (precioCNY SIN IVA) × cantidad. Primero se quita el 13%
        // de IVA (base = precioCNY / 1.13), luego se aplica el % del operador.
        BigDecimal commissionCny = BigDecimal.ZERO;
        int itemCount = 0;
        if (order.getItems() != null) {
            for (OrderItem it : order.getItems()) {
                BigDecimal lineGrossCny = BigDecimal.valueOf(it.getCostCnyCents())
                        .multiply(BigDecimal.valueOf(it.getQuantity()));
                BigDecimal lineBaseCny = lineGrossCny.divide(IVA_DIVISOR, 4, RoundingMode.HALF_UP);
                commissionCny = commissionCny.add(lineBaseCny.multiply(pct).divide(HUNDRED, 4, RoundingMode.HALF_UP));
                itemCount += it.getQuantity();
            }
        }
        long commissionCnyCents = commissionCny.setScale(0, RoundingMode.HALF_UP).longValueExact();

        String email = null;
        String name = null;
        try {
            UUID uid = UUID.fromString(subject);
            UserEntity u = userRepository.findById(uid).orElse(null);
            if (u != null) {
                email = u.getEmail();
                name = u.getDisplayName();
            }
        } catch (RuntimeException notUuid) {
            email = subject;
        }

        OperatorOrderActionEntity action = OperatorOrderActionEntity.builder().operatorSubject(subject)
                .operatorEmail(email).operatorName(name).orderId(order.getId()).orderNumber(order.getOrderNumber())
                .action(ACTION_DELIVERED).commissionCnyCents(commissionCnyCents).itemCount(itemCount)
                .orderSource(integration ? "INTEGRATION" : "PLATFORM").commissionPct(pct).processedAt(Instant.now())
                .build();
        OperatorOrderActionEntity saved = actionRepository.save(action);
        indexer.index(saved);
        log.info("::> [OPERATOR] {} entregó orden {} → comisión {} CNY-cents", subject, order.getOrderNumber(),
                commissionCnyCents);
    }
}
