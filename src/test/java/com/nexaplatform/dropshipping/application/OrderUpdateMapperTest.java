package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.mapper.OrderUpdateMapper;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La actualización parcial de un pedido copia los campos editables (estado, importes, divisa, notas,
 * tracking) y preserva identidad/auditoría.
 */
class OrderUpdateMapperTest {

    private final OrderUpdateMapper mapper = Mappers.getMapper(OrderUpdateMapper.class);

    @Test
    void updateFromModel_copiesEditableFieldsAndPreservesIdentityAndAudit() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2024-01-01T00:00:00Z");

        Order target = Order.builder().id(id).orderNumber("NX-001").status(OrderStatus.PENDING).totalCents(1000)
                .currency("EUR").notes("old").trackingNumber("OLDTRACK").trackingStatus("PENDING").createdAt(created)
                .createdBy("creator").build();

        Order source = Order.builder().orderNumber("NX-999").status(OrderStatus.SHIPPED).totalCents(2500)
                .currency("USD").notes("new").trackingNumber("NEWTRACK").trackingStatus("IN_TRANSIT").carrier("Cainiao")
                .build();

        mapper.updateFromModel(source, target);

        // Editables
        assertThat(target.getOrderNumber()).isEqualTo("NX-999");
        assertThat(target.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(target.getTotalCents()).isEqualTo(2500);
        assertThat(target.getCurrency()).isEqualTo("USD");
        assertThat(target.getNotes()).isEqualTo("new");
        assertThat(target.getTrackingNumber()).isEqualTo("NEWTRACK");
        assertThat(target.getTrackingStatus()).isEqualTo("IN_TRANSIT");
        assertThat(target.getCarrier()).isEqualTo("Cainiao");

        // Preservados
        assertThat(target.getId()).isEqualTo(id);
        assertThat(target.getCreatedAt()).isEqualTo(created);
        assertThat(target.getCreatedBy()).isEqualTo("creator");
    }
}
