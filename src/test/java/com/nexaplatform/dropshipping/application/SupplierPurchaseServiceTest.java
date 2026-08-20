package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.domain.enums.SupplierPurchaseStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierPurchaseEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierPurchaseItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierPurchaseItemRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierPurchaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierPurchaseServiceTest {

    @Mock
    SupplierPurchaseRepository purchaseRepository;
    @Mock
    SupplierPurchaseItemRepository itemRepository;
    @Mock
    ProductRepository productRepository;
    @Mock
    OrderRepository orderRepository;

    @InjectMocks
    SupplierPurchaseService service;

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID SUPPLIER_A = UUID.randomUUID();
    private static final UUID SUPPLIER_B = UUID.randomUUID();

    private void withWarehouse() {
        ReflectionTestUtils.setField(service, "defaultWarehouseCode", "CNCHASHAN");
    }

    private UUID productOf(UUID supplierId) {
        UUID productId = UUID.randomUUID();
        SupplierEntity supplier = new SupplierEntity();
        supplier.setId(supplierId);
        ProductEntity product = new ProductEntity();
        product.setId(productId);
        product.setSupplier(supplier);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        return productId;
    }

    private static OrderItem line(UUID productId, int qty) {
        return OrderItem.builder().id(UUID.randomUUID()).productId(productId).quantity(qty).build();
    }

    private static Order order(List<OrderItem> items) {
        return Order.builder().id(ORDER_ID).orderNumber("NX-1").items(items).build();
    }

    @Test
    void dosLineasDelMismoProveedorSonUnaSolaCompra() {
        withWarehouse();
        UUID product = productOf(SUPPLIER_A);
        UUID otro = productOf(SUPPLIER_A);
        when(purchaseRepository.existsByOrderId(ORDER_ID)).thenReturn(false);
        when(purchaseRepository.save(any())).thenAnswer(inv -> {
            SupplierPurchaseEntity p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        service.planPurchases(order(List.of(line(product, 1), line(otro, 2))));

        // Un solo bulto: el proveedor manda un paquete con las dos líneas.
        verify(purchaseRepository, times(1)).save(any());
        verify(itemRepository, times(2)).save(any());
    }

    @Test
    void dosProveedoresSonDosCompras() {
        withWarehouse();
        UUID deA = productOf(SUPPLIER_A);
        UUID deB = productOf(SUPPLIER_B);
        when(purchaseRepository.existsByOrderId(ORDER_ID)).thenReturn(false);
        when(purchaseRepository.save(any())).thenAnswer(inv -> {
            SupplierPurchaseEntity p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        service.planPurchases(order(List.of(line(deA, 1), line(deB, 1))));

        ArgumentCaptor<SupplierPurchaseEntity> captor = ArgumentCaptor.forClass(SupplierPurchaseEntity.class);
        verify(purchaseRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(SupplierPurchaseEntity::getSupplierId)
                .containsExactlyInAnyOrder(SUPPLIER_A, SUPPLIER_B);
        assertThat(captor.getAllValues()).allSatisfy(p -> {
            assertThat(p.getStatus()).isEqualTo(SupplierPurchaseStatus.PENDING);
            assertThat(p.getWarehouseCode()).isEqualTo("CNCHASHAN");
        });
    }

    @Test
    void unSegundoWebhookDePagoNoDuplicaLaListaDeLaCompra() {
        when(purchaseRepository.existsByOrderId(ORDER_ID)).thenReturn(true);

        service.planPurchases(order(List.of(line(UUID.randomUUID(), 1))));

        verify(purchaseRepository, never()).save(any());
        verifyNoInteractions(itemRepository);
    }

    @Test
    void unaLineaSinProveedorNoFabricaUnaCompraANadie() {
        withWarehouse();
        UUID huerfano = UUID.randomUUID();
        when(productRepository.findById(huerfano)).thenReturn(Optional.empty());
        when(purchaseRepository.existsByOrderId(ORDER_ID)).thenReturn(false);

        service.planPurchases(order(List.of(line(huerfano, 1))));

        verify(purchaseRepository, never()).save(any());
    }

    @Test
    void sinMercanciaCompradaNoSePuedeEmitirLaGuiaInternacional() {
        when(purchaseRepository.existsByOrderId(ORDER_ID)).thenReturn(true);
        when(purchaseRepository.existsByOrderIdAndStatusIn(eq(ORDER_ID), anyCollection())).thenReturn(true);

        assertThat(service.readyForInternationalShipment(ORDER_ID)).isFalse();
    }

    @Test
    void conTodosLosBultosEnCaminoSePuedeEmitirLaGuia() {
        when(purchaseRepository.existsByOrderId(ORDER_ID)).thenReturn(true);
        when(purchaseRepository.existsByOrderIdAndStatusIn(eq(ORDER_ID), anyCollection())).thenReturn(false);

        assertThat(service.readyForInternationalShipment(ORDER_ID)).isTrue();
    }

    @Test
    void losPedidosAnterioresAEstaFuncionalidadNoSeQuedanBloqueados() {
        // Sin compras registradas su mercancía se gestionó fuera del sistema: bloquearlos ahora
        // congelaría envíos que ya estaban en marcha.
        when(purchaseRepository.existsByOrderId(ORDER_ID)).thenReturn(false);

        assertThat(service.readyForInternationalShipment(ORDER_ID)).isTrue();
        verify(purchaseRepository, never()).existsByOrderIdAndStatusIn(any(), anyCollection());
    }

    @Test
    void registrarLaCompraGuardaElCosteRealYPasaAComprada() {
        UUID id = UUID.randomUUID();
        SupplierPurchaseEntity p = SupplierPurchaseEntity.builder().id(id)
                .status(SupplierPurchaseStatus.PENDING).build();
        when(purchaseRepository.findById(id)).thenReturn(Optional.of(p));
        when(purchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SupplierPurchaseEntity out = service.markPurchased(id, "1688-ABC", 3_450L, 800L);

        assertThat(out.getStatus()).isEqualTo(SupplierPurchaseStatus.PURCHASED);
        assertThat(out.getPurchaseRef()).isEqualTo("1688-ABC");
        assertThat(out.getCostCnyCents()).isEqualTo(3_450L);
        assertThat(out.getShippingCnyCents()).isEqualTo(800L);
        assertThat(out.getPurchasedAt()).isNotNull();
    }

    @Test
    void elSeguimientoNacionalSeGuardaSinEspaciosPeroSinCambiarleLasMayusculas() {
        // El almacén empareja por coincidencia exacta: normalizar el número rompería el emparejamiento.
        UUID id = UUID.randomUUID();
        SupplierPurchaseEntity p = SupplierPurchaseEntity.builder().id(id)
                .status(SupplierPurchaseStatus.PURCHASED).build();
        when(purchaseRepository.findById(id)).thenReturn(Optional.of(p));
        when(purchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SupplierPurchaseEntity out = service.markShipped(id, "  SF1234567890ab  ", "SF");

        assertThat(out.getDomesticTracking()).isEqualTo("SF1234567890ab");
        assertThat(out.getStatus()).isEqualTo(SupplierPurchaseStatus.IN_TRANSIT);
    }

    @Test
    void avisaDeLosBultosQueLlevanDemasiadoTiempoEnElAlmacen() {
        SupplierPurchaseEntity viejo = SupplierPurchaseEntity.builder().id(UUID.randomUUID())
                .status(SupplierPurchaseStatus.AT_WAREHOUSE)
                .receivedAt(Instant.now().minus(25, ChronoUnit.DAYS)).build();
        SupplierPurchaseEntity reciente = SupplierPurchaseEntity.builder().id(UUID.randomUUID())
                .status(SupplierPurchaseStatus.AT_WAREHOUSE)
                .receivedAt(Instant.now().minus(3, ChronoUnit.DAYS)).build();
        SupplierPurchaseEntity sinFecha = SupplierPurchaseEntity.builder().id(UUID.randomUUID())
                .status(SupplierPurchaseStatus.AT_WAREHOUSE).build();
        when(purchaseRepository.findByStatusInOrderByCreatedAtAsc(anyCollection()))
                .thenReturn(List.of(viejo, reciente, sinFecha));

        List<SupplierPurchaseEntity> enRiesgo = service.atRiskOfDestruction();

        assertThat(enRiesgo).containsExactly(viejo);
    }

    @Test
    void elEstadoCanceladoNoGanaAUnoQueAvanza() {
        assertThat(SupplierPurchaseStatus.CANCELLED.progress())
                .isLessThan(SupplierPurchaseStatus.PENDING.progress());
        assertThat(SupplierPurchaseStatus.IN_TRANSIT.merchandiseOnTheMove()).isTrue();
        assertThat(SupplierPurchaseStatus.PURCHASED.merchandiseOnTheMove()).isFalse();
        assertThat(SupplierPurchaseStatus.CANCELLED.merchandiseOnTheMove()).isFalse();
    }

    @Test
    void marcarRecibidoYReempaquetadoAvanzaLosEstados() {
        UUID id = UUID.randomUUID();
        // Exportada: el número de orden que se teclea al re-empaquetar lo devuelve el OMS al importar
        // el fichero, así que antes de eso no puede existir.
        SupplierPurchaseEntity p = SupplierPurchaseEntity.builder().id(id)
                .status(SupplierPurchaseStatus.IN_TRANSIT)
                .exportedAt(java.time.Instant.parse("2026-08-01T00:00:00Z")).build();
        when(purchaseRepository.findById(id)).thenReturn(Optional.of(p));
        when(purchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.markReceived(id).getStatus()).isEqualTo(SupplierPurchaseStatus.AT_WAREHOUSE);
        SupplierPurchaseEntity packed = service.markPacked(id, "PK-99", "REPACKAGING");
        assertThat(packed.getStatus()).isEqualTo(SupplierPurchaseStatus.PACKED);
        assertThat(packed.getPackOrderNo()).isEqualTo("PK-99");
        assertThat(packed.getPackSubmittedAt()).isNotNull();
    }

    /**
     * Sin exportar no se puede re-empaquetar.
     *
     * <p>Dejarlo pasar metía el pedido en un callejón sin salida: la compra salía del tablero y, por
     * tener ya orden de re-empaquetado, la validación del fichero la rechazaba para siempre. Y el
     * almacén no recibía instrucción alguna sobre un bulto que allí sigue contando sus 30 días.
     */
    @Test
    void noSePuedeReempaquetarSinHaberDescargadoElFichero() {
        UUID id = UUID.randomUUID();
        SupplierPurchaseEntity p = SupplierPurchaseEntity.builder().id(id)
                .status(SupplierPurchaseStatus.AT_WAREHOUSE).build();
        when(purchaseRepository.findById(id)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.markPacked(id, "PK-99", "REPACKAGING"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Descarga antes el fichero");

        assertThat(p.getStatus()).isEqualTo(SupplierPurchaseStatus.AT_WAREHOUSE);
        assertThat(p.getPackOrderNo()).isNull();
        verify(purchaseRepository, never()).save(any());
    }

    /** El tablero enseña también lo ya re-empaquetado; la cola de exportación, no. */
    @Test
    void elTableroSigueMostrandoLoReempaquetado() {
        service.openQueue();

        ArgumentCaptor<Set<SupplierPurchaseStatus>> captor = ArgumentCaptor.forClass(Set.class);
        verify(purchaseRepository).findByStatusInOrderByCreatedAtAsc(captor.capture());
        assertThat(captor.getValue()).contains(SupplierPurchaseStatus.PACKED);
    }

    @Test
    void laColaDeExportacionNoRepiteLoYaReempaquetado() {
        service.exportQueue();

        ArgumentCaptor<Set<SupplierPurchaseStatus>> captor = ArgumentCaptor.forClass(Set.class);
        verify(purchaseRepository).findByStatusInAndExportedAtIsNullOrderByCreatedAtAsc(captor.capture());
        assertThat(captor.getValue()).doesNotContain(SupplierPurchaseStatus.PACKED);
    }

    @Test
    void unPedidoYaPagadoSinComprasPuedeGenerarlasMasTarde() {
        // Los pedidos cobrados antes de esta funcionalidad se quedaron sin lista de la compra, y sin
        // ella no aparecen en la cola ni se pueden reempaquetar nunca.
        withWarehouse();
        UUID product = productOf(SUPPLIER_A);
        Order o = order(List.of(line(product, 1)));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(o));
        when(purchaseRepository.existsByOrderId(ORDER_ID)).thenReturn(false);
        when(purchaseRepository.save(any())).thenAnswer(inv -> {
            SupplierPurchaseEntity p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });
        when(purchaseRepository.findByOrderId(ORDER_ID)).thenReturn(List.of());

        service.planForExistingOrder(ORDER_ID);

        verify(purchaseRepository).save(any());
    }

    @Test
    void generarLasComprasDeUnPedidoQueNoExisteEsUn404() {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.planForExistingOrder(ORDER_ID))
                .isInstanceOf(NotFoundException.class);
    }

    /* ============================ exportación (anti-duplicado del .xls) ============================ */

    /** La cola de exportación excluye lo ya volcado a un fichero (exported_at IS NULL). */
    @Test
    void laColaDeExportacionSoloTraeLoNoExportado() {
        SupplierPurchaseEntity pend = SupplierPurchaseEntity.builder().id(UUID.randomUUID())
                .orderId(ORDER_ID).status(SupplierPurchaseStatus.AT_WAREHOUSE).build();
        when(purchaseRepository.findByStatusInAndExportedAtIsNullOrderByCreatedAtAsc(any()))
                .thenReturn(List.of(pend));

        assertThat(service.exportQueue()).containsExactly(pend);
        verify(purchaseRepository).findByStatusInAndExportedAtIsNullOrderByCreatedAtAsc(any());
    }

    /** Al descargar se marcan como exportadas las compras del pedido, una sola vez (idempotente). */
    @Test
    void marcarExportadoSellaLaFechaSoloUnaVez() {
        SupplierPurchaseEntity sinExportar = SupplierPurchaseEntity.builder().id(UUID.randomUUID())
                .orderId(ORDER_ID).status(SupplierPurchaseStatus.AT_WAREHOUSE).build();
        SupplierPurchaseEntity yaExportada = SupplierPurchaseEntity.builder().id(UUID.randomUUID())
                .orderId(ORDER_ID).status(SupplierPurchaseStatus.AT_WAREHOUSE)
                .exportedAt(java.time.Instant.parse("2026-08-01T00:00:00Z")).build();
        when(purchaseRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(sinExportar, yaExportada));

        service.markExported(List.of(ORDER_ID));

        assertThat(sinExportar.getExportedAt()).isNotNull();          // se sella
        assertThat(yaExportada.getExportedAt())                        // no se pisa la fecha previa
                .isEqualTo(java.time.Instant.parse("2026-08-01T00:00:00Z"));
        verify(purchaseRepository).save(sinExportar);
        verify(purchaseRepository, org.mockito.Mockito.never()).save(yaExportada);
    }

    /** «Volver a exportar» limpia la fecha para que la compra reentre en el próximo fichero. */
    @Test
    void volverAExportarLimpiaLaFecha() {
        SupplierPurchaseEntity p = SupplierPurchaseEntity.builder().id(UUID.randomUUID())
                .orderId(ORDER_ID).status(SupplierPurchaseStatus.AT_WAREHOUSE)
                .exportedAt(java.time.Instant.parse("2026-08-01T00:00:00Z")).build();
        when(purchaseRepository.findById(p.getId())).thenReturn(java.util.Optional.of(p));
        when(purchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SupplierPurchaseEntity out = service.requestReexport(p.getId());

        assertThat(out.getExportedAt()).isNull();
    }

    // ------- cancelar el pedido retira del tablero SOLO lo que todavía no se ha comprado -------

    @Test
    void cancelarElPedidoRetiraLasComprasQueAunNoSeHabianComprado() {
        // Un pedido cancelado dejaba sus compras en PENDING y el tablero las seguía pintando: el admin
        // acababa comprando en 1688 género de una venta que ya no existe, y ese dinero no se recupera.
        SupplierPurchaseEntity porComprar = SupplierPurchaseEntity.builder().id(UUID.randomUUID())
                .orderId(ORDER_ID).status(SupplierPurchaseStatus.PENDING).build();
        when(purchaseRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(porComprar));
        when(purchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int retiradas = service.cancelUnbought(ORDER_ID, "pedido cancelado");

        assertThat(retiradas).isEqualTo(1);
        assertThat(porComprar.getStatus()).isEqualTo(SupplierPurchaseStatus.CANCELLED);
        assertThat(porComprar.getNotes()).isEqualTo("pedido cancelado");
    }

    @Test
    void cancelarElPedidoNoRetiraLaMercanciaYaCompradaAlProveedor() {
        // Aquí hay género real pagado y en camino. Esconderlo del tablero es perder el rastro de un
        // bulto que el almacén DESTRUYE sin compensación a los 30 días si nadie da instrucciones.
        SupplierPurchaseEntity yaComprada = SupplierPurchaseEntity.builder().id(UUID.randomUUID())
                .orderId(ORDER_ID).status(SupplierPurchaseStatus.PURCHASED).build();
        SupplierPurchaseEntity enCamino = SupplierPurchaseEntity.builder().id(UUID.randomUUID())
                .orderId(ORDER_ID).status(SupplierPurchaseStatus.IN_TRANSIT).build();
        when(purchaseRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(yaComprada, enCamino));

        int retiradas = service.cancelUnbought(ORDER_ID, "pedido cancelado");

        assertThat(retiradas).isZero();
        assertThat(yaComprada.getStatus()).isEqualTo(SupplierPurchaseStatus.PURCHASED);
        assertThat(enCamino.getStatus()).isEqualTo(SupplierPurchaseStatus.IN_TRANSIT);
        verify(purchaseRepository, never()).save(any());
    }

    @Test
    void cancelarDosVecesElMismoPedidoNoVuelveAContarLoYaRetirado() {
        // El admin puede cancelar un pedido ya cancelado (la operación es idempotente): no debe volver
        // a tocar lo que ya estaba retirado ni inflar el recuento.
        SupplierPurchaseEntity yaRetirada = SupplierPurchaseEntity.builder().id(UUID.randomUUID())
                .orderId(ORDER_ID).status(SupplierPurchaseStatus.CANCELLED).build();
        when(purchaseRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(yaRetirada));

        assertThat(service.cancelUnbought(ORDER_ID, "pedido cancelado")).isZero();
        verify(purchaseRepository, never()).save(any());
    }
}
