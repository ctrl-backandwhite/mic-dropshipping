package com.nexaplatform.dropshipping.application;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
        SupplierPurchaseEntity p = SupplierPurchaseEntity.builder().id(id)
                .status(SupplierPurchaseStatus.IN_TRANSIT).build();
        when(purchaseRepository.findById(id)).thenReturn(Optional.of(p));
        when(purchaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.markReceived(id).getStatus()).isEqualTo(SupplierPurchaseStatus.AT_WAREHOUSE);
        SupplierPurchaseEntity packed = service.markPacked(id, "PK-99", "REPACKAGING");
        assertThat(packed.getStatus()).isEqualTo(SupplierPurchaseStatus.PACKED);
        assertThat(packed.getPackOrderNo()).isEqualTo("PK-99");
        assertThat(packed.getPackSubmittedAt()).isNotNull();
    }
}
