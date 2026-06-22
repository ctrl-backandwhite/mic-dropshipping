package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderItemEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper Order(model) <-> CustomerOrderEntity. El round-trip Model->Entity->Model solo conserva los
 * campos escalares que existen en la entidad: las relaciones gestionadas (direcciones, items) y los
 * campos solo-modelo (snapshots de dirección, enriquecimiento read-only, tracking de Cainiao, source)
 * no tienen contraparte y se ignoran. La lista de items se verifica por separado en la ruta
 * Entity->Model.
 */
class OrderEntityMapperTest {

    private final OrderEntityMapper mapper = Mappers.getMapper(OrderEntityMapper.class);

    @Test
    void roundTrip_preservesScalarFields() {
        UUID id = UUID.randomUUID();
        Order source = Order.builder()
                .id(id)
                .orderNumber("ORD-1001")
                .partnerAppId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .externalOrderId("EXT-77")
                .status(OrderStatus.PAID)
                .subtotalCents(1000)
                .shippingCents(200)
                .taxCents(80)
                .totalCents(1280)
                .currency("EUR")
                .notes("handle with care")
                .placedAt(Instant.parse("2026-01-01T10:00:00Z"))
                .forwardedAt(Instant.parse("2026-01-02T10:00:00Z"))
                .shippedAt(Instant.parse("2026-01-03T10:00:00Z"))
                .deliveredAt(Instant.parse("2026-01-04T10:00:00Z"))
                .cancelledAt(null)
                .items(new ArrayList<>())
                .build();

        CustomerOrderEntity entity = mapper.toEntity(source);
        Order result = mapper.toDomain(entity);

        assertThat(result).usingRecursiveComparison()
                .ignoringFields(
                        // auditoría resuelta por JPA / ignorada en toEntity
                        "createdAt", "updatedAt", "createdBy", "updatedBy",
                        // relaciones gestionadas / sub-entidades sin contraparte escalar
                        "items", "shippingAddressId", "billingAddressId",
                        // campos solo-modelo (sin columna en la entidad)
                        "source", "carrier", "trackingNumber", "fulfillmentRef", "trackingStatus",
                        "estimatedDeliveryAt", "lastTrackedAt",
                        "customerEmail", "shopName", "shopHandle", "supplierName",
                        "shippingFullName", "shippingPhone", "shippingEmail", "shippingLine1",
                        "shippingLine2", "shippingCity", "shippingState", "shippingPostalCode",
                        "shippingCountry",
                        "billingFullName", "billingPhone", "billingEmail", "billingLine1",
                        "billingLine2", "billingCity", "billingState", "billingPostalCode",
                        "billingCountry")
                .isEqualTo(source);
    }

    @Test
    void toEntity_ignoresAuditAndManagedRelations() {
        Order source = Order.builder()
                .id(UUID.randomUUID())
                .orderNumber("ORD-2002")
                .status(OrderStatus.PENDING)
                .currency("USD")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .createdBy("someone")
                .updatedBy("someone-else")
                .items(List.of(OrderItem.builder().id(UUID.randomUUID()).quantity(3).build()))
                .build();

        CustomerOrderEntity entity = mapper.toEntity(source);

        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getCreatedBy()).isNull();
        assertThat(entity.getUpdatedBy()).isNull();
        // items y direcciones las resuelve el repositorio: el mapper no las puebla
        assertThat(entity.getItems()).isEmpty();
        assertThat(entity.getShippingAddress()).isNull();
        assertThat(entity.getBillingAddress()).isNull();
    }

    @Test
    void toDomain_mapsItemListWithFields() {
        OrderItemEntity itemEntity = OrderItemEntity.builder()
                .id(UUID.randomUUID())
                .titleSnapshot("Widget")
                .skuSnapshot("SKU-9")
                .unitPriceCents(500)
                .costCents(200)
                .costCnyCents(1500L)
                .quantity(4)
                .lineTotalCents(2000)
                .build();

        CustomerOrderEntity entity = CustomerOrderEntity.builder()
                .orderNumber("ORD-3003")
                .status(OrderStatus.PAID)
                .items(new ArrayList<>(List.of(itemEntity)))
                .build();
        entity.setId(UUID.randomUUID());

        Order result = mapper.toDomain(entity);

        assertThat(result.getItems()).hasSize(1);
        OrderItem item = result.getItems().get(0);
        assertThat(item.getTitleSnapshot()).isEqualTo("Widget");
        assertThat(item.getQuantity()).isEqualTo(4);
        assertThat(item.getLineTotalCents()).isEqualTo(2000);
        assertThat(item.getCostCnyCents()).isEqualTo(1500L);
    }
}
