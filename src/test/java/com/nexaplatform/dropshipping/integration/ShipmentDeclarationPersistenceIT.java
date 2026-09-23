package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.config.PersistenceITBase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderShipmentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La declaración que se le transmite al transportista sobrevive al viaje de ida y vuelta a Postgres.
 *
 * <p>Es una columna {@code jsonb} nueva (v139) mapeada con {@code @JdbcTypeCode(SqlTypes.JSON)}: un
 * nombre de columna equivocado o un contenido que Hibernate no sepa escribir no se ve en un test
 * unitario con el repositorio simulado — se vería en producción, al despachar un pedido de verdad.
 * Aquí se escribe y se relee contra el motor real.
 *
 * <p>Se fija también el caso del envío SIN declaración (los anteriores a v139): la columna admite nulos
 * a propósito y el alta del bulto no puede depender de ella.
 */
class ShipmentDeclarationPersistenceIT extends PersistenceITBase {

    @Autowired
    OrderShipmentRepository shipments;

    @Autowired
    TestEntityManager em;

    /**
     * Un pedido mínimo al que colgar el bulto. {@code order_shipment.order_id} tiene clave ajena a
     * {@code customer_order}, así que no vale un identificador inventado.
     */
    private UUID pedido(String orderNumber) {
        AddressEntity direccion = em.persist(AddressEntity.builder().fullName("Ana López").line1("Calle Mayor 1")
                .city("Zaragoza").postalCode("50001").country("ES").createdAt(Instant.now()).build());
        CustomerOrderEntity pedido = em.persist(CustomerOrderEntity.builder().orderNumber(orderNumber)
                .source("PLATFORM").shippingAddress(direccion).status(OrderStatus.FORWARDED).subtotalCents(1200)
                .shippingCents(0).taxCents(0).customsDutyCents(0).totalCents(1200).discountCents(0).currency("USD")
                .placedAt(Instant.now()).build());
        em.flush();
        return pedido.getId();
    }

    private static OrderShipmentEntity bulto(UUID orderId, String waybill, Map<String, Object> declaration) {
        return OrderShipmentEntity.builder().orderId(orderId).sequenceNo(1).carrier("Standard Shipping")
                .productCode("BPA").waybillNumber(waybill).trackingNumber(waybill).status("FORWARDED").weightGrams(500)
                .declaredValueCents(1200).declaration(declaration).createdAt(Instant.now()).build();
    }

    /** Lo que se le declara al transportista: a quién va el paquete y qué lleva. */
    private static Map<String, Object> declaracion() {
        Map<String, Object> receptor = new LinkedHashMap<>();
        receptor.put("firstName", "Ana");
        receptor.put("lastName", "López");
        receptor.put("countryCode", "ES");
        receptor.put("city", "Zaragoza");
        receptor.put("postalCode", "50001");
        receptor.put("addressLines", List.of("Calle Mayor 1"));
        Map<String, Object> linea = new LinkedHashMap<>();
        linea.put("nameEn", "Cotton T-shirt");
        linea.put("nameLocal", "棉T恤");
        linea.put("hsCode", "610910");
        linea.put("quantity", 2);
        Map<String, Object> declaracion = new LinkedHashMap<>();
        declaracion.put("receiver", receptor);
        declaracion.put("lines", List.of(linea));
        return declaracion;
    }

    @Test
    void laDeclaracionSeGuardaYSeReleeTalCualSeTransmitio() {
        shipments.saveAndFlush(bulto(pedido("NX-DECL-1"), "WB-DECL-1", declaracion()));
        em.clear(); // sin vaciar la sesión se leería el objeto en memoria, no lo que hay en la columna

        Optional<OrderShipmentEntity> releido = shipments.findByWaybillNumber("WB-DECL-1");

        assertThat(releido).isPresent();
        Map<String, Object> archivada = releido.get().getDeclaration();
        assertThat(archivada).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> receptor = (Map<String, Object>) archivada.get("receiver");
        assertThat(receptor).containsEntry("city", "Zaragoza").containsEntry("countryCode", "ES");
        assertThat(receptor.get("addressLines")).isEqualTo(List.of("Calle Mayor 1"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lineas = (List<Map<String, Object>>) archivada.get("lines");
        // Los ideogramas del CName tienen que llegar intactos: es el campo por el que el transportista
        // rechaza la guía si viene mal.
        assertThat(lineas).singleElement().satisfies(l -> {
            assertThat(l).containsEntry("hsCode", "610910").containsEntry("nameLocal", "棉T恤");
            assertThat(l).containsEntry("quantity", 2);
        });
    }

    @Test
    void unEnvioSinDeclaracionSeGuardaIgualYSeReleeVacio() {
        shipments.saveAndFlush(bulto(pedido("NX-DECL-2"), "WB-DECL-2", null));
        em.clear();

        Optional<OrderShipmentEntity> releido = shipments.findByWaybillNumber("WB-DECL-2");

        assertThat(releido).isPresent();
        assertThat(releido.get().getDeclaration()).isNull();
    }
}
