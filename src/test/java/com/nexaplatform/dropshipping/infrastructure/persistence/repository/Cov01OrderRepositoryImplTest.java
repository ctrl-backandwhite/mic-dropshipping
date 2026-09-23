package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.OrderEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl.OrderRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del adaptador JPA de pedidos: qué se resuelve del modelo plano (direcciones y líneas) y qué
 * NO se debe tocar al actualizar un pedido ya persistido.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov01OrderRepositoryImplTest {

    private static final UUID ORDER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID PRODUCT_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID VARIANT_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final UUID ADDRESS_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000004");

    @Mock
    OrderEntityMapper orderEntityMapper;
    @Mock
    OrderJpaRepositoryAdapter orderJpaRepositoryAdapter;
    @Mock
    AddressRepository addressRepository;
    @Mock
    ProductRepository productRepository;
    @Mock
    ProductVariantRepository variantRepository;

    @InjectMocks
    OrderRepositoryImpl repository;

    private ProductEntity product;
    private ProductVariantEntity variant;

    @BeforeEach
    void setUp() {
        product = ProductEntity.builder().build();
        product.setId(PRODUCT_ID);
        variant = ProductVariantEntity.builder().build();
        variant.setId(VARIANT_ID);
        // El save devuelve la entidad tal cual y el mapper un modelo mínimo: aquí interesa QUÉ entidad se
        // manda a guardar, no la conversión de vuelta (que es de MapStruct).
        when(orderJpaRepositoryAdapter.save(any(CustomerOrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderEntityMapper.toDomain(any(CustomerOrderEntity.class))).thenReturn(Order.builder().build());
        when(addressRepository.save(any(AddressEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Order.OrderBuilder newOrderWithShipping() {
        return Order.builder().shippingFullName("Ana Gómez").shippingPhone("+34600000000").shippingEmail("ana@test.com")
                .shippingLine1("Calle Mayor 1").shippingCity("Madrid").shippingPostalCode("28001")
                .shippingCountry("ES");
    }

    private CustomerOrderEntity capturedSaved() {
        ArgumentCaptor<CustomerOrderEntity> captor = ArgumentCaptor.forClass(CustomerOrderEntity.class);
        verify(orderJpaRepositoryAdapter).save(captor.capture());
        return captor.getValue();
    }

    /* ============ alta ============ */

    @Test
    void unPedidoNuevoPersisteLaDireccionDeEnvioDesdeLosCamposPlanos() {
        // El modelo de dominio no conoce la tabla de direcciones: el adaptador es quien la materializa.
        repository.save(newOrderWithShipping().build());

        CustomerOrderEntity saved = capturedSaved();
        assertThat(saved.getShippingAddress()).isNotNull();
        assertThat(saved.getShippingAddress().getFullName()).isEqualTo("Ana Gómez");
        assertThat(saved.getShippingAddress().getCity()).isEqualTo("Madrid");
        assertThat(saved.getShippingAddress().getCountry()).isEqualTo("ES");
    }

    @Test
    void unPedidoSinFacturacionNoCreaUnaDireccionVacia() {
        // Una fila de dirección en blanco acabaría impresa en la factura como destinatario sin nombre.
        repository.save(newOrderWithShipping().build());

        assertThat(capturedSaved().getBillingAddress()).isNull();
        verify(addressRepository, never()).findById(any());
    }

    @Test
    void unaDireccionExistenteSeReutilizaPorIdEnVezDeDuplicarla() {
        AddressEntity existing = AddressEntity.builder().fullName("Ana Gómez").city("Madrid").build();
        when(addressRepository.findById(ADDRESS_ID)).thenReturn(Optional.of(existing));

        repository.save(Order.builder().shippingAddressId(ADDRESS_ID).build());

        assertThat(capturedSaved().getShippingAddress()).isSameAs(existing);
        verify(addressRepository, never()).save(any(AddressEntity.class));
    }

    @Test
    void unIdDeDireccionDeEnvioInexistenteRompeElAlta() {
        when(addressRepository.findById(ADDRESS_ID)).thenReturn(Optional.empty());
        Order order = Order.builder().shippingAddressId(ADDRESS_ID).build();

        assertThatThrownBy(() -> repository.save(order)).isInstanceOf(NotFoundException.class)
                .hasMessage("Address not found");
    }

    @Test
    void unIdDeDireccionDeFacturacionInexistenteRompeElAlta() {
        when(addressRepository.findById(ADDRESS_ID)).thenReturn(Optional.empty());
        Order order = newOrderWithShipping().billingAddressId(ADDRESS_ID).build();

        assertThatThrownBy(() -> repository.save(order)).isInstanceOf(NotFoundException.class)
                .hasMessage("Address not found");
    }

    @Test
    void laDireccionDeFacturacionSeCreaSiLaTraeElModelo() {
        Order order = newOrderWithShipping().billingFullName("Ana Gómez S.L.").billingLine1("Gran Vía 5")
                .billingCity("Madrid").billingCountry("ES").build();

        repository.save(order);

        AddressEntity billing = capturedSaved().getBillingAddress();
        assertThat(billing).isNotNull();
        assertThat(billing.getFullName()).isEqualTo("Ana Gómez S.L.");
        assertThat(billing.getLine1()).isEqualTo("Gran Vía 5");
    }

    @Test
    void lasLineasSeConstruyenResolviendoProductoYVarianteGestionados() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(variantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(variant));
        OrderItem line = OrderItem.builder().productId(PRODUCT_ID).variantId(VARIANT_ID).titleSnapshot("Camiseta")
                .skuSnapshot("SKU-1").unitPriceCents(1990).costCents(800).costCnyCents(6200L).quantity(3)
                .lineTotalCents(5970).imageUrlSnapshot("https://img.test/1.jpg").build();

        repository.save(newOrderWithShipping().items(List.of(line)).build());

        CustomerOrderEntity saved = capturedSaved();
        assertThat(saved.getItems()).hasSize(1);
        OrderItemEntity item = saved.getItems().get(0);
        assertThat(item.getProduct()).isSameAs(product);
        assertThat(item.getVariant()).isSameAs(variant);
        assertThat(item.getOrder()).isSameAs(saved);
        // El snapshot es lo que se factura: si se recalculara desde el producto, un cambio de precio
        // posterior alteraría pedidos ya cobrados.
        assertThat(item.getUnitPriceCents()).isEqualTo(1990);
        assertThat(item.getLineTotalCents()).isEqualTo(5970);
        assertThat(item.getQuantity()).isEqualTo(3);
        assertThat(item.getCostCnyCents()).isEqualTo(6200L);
    }

    @Test
    void unaLineaSinVarianteEsValidaYNoConsultaElRepositorioDeVariantes() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        OrderItem line = OrderItem.builder().productId(PRODUCT_ID).quantity(1).build();

        repository.save(newOrderWithShipping().items(List.of(line)).build());

        assertThat(capturedSaved().getItems().get(0).getVariant()).isNull();
        verify(variantRepository, never()).findById(any());
    }

    @Test
    void unProductoInexistenteEnUnaLineaRompeElAltaIdentificandoloPorId() {
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());
        Order order = newOrderWithShipping().items(List.of(OrderItem.builder().productId(PRODUCT_ID).build())).build();

        assertThatThrownBy(() -> repository.save(order)).isInstanceOf(NotFoundException.class)
                .hasMessageContaining(PRODUCT_ID.toString());
    }

    @Test
    void unaVarianteDesaparecidaSeSenalizaConCodigoPropioYSuIdParaLimpiarElCarrito() {
        // Un carrito viejo apunta a una variante reimportada con otro id. El checkout necesita SABER
        // cuál es la línea rota para quitarla; un 404 genérico dejaría al comprador atascado.
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(variantRepository.findById(VARIANT_ID)).thenReturn(Optional.empty());
        Order order = newOrderWithShipping()
                .items(List.of(OrderItem.builder().productId(PRODUCT_ID).variantId(VARIANT_ID).build())).build();

        assertThatThrownBy(() -> repository.save(order)).isInstanceOfSatisfying(NotFoundException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo("CART_ITEM_UNAVAILABLE");
            assertThat(ex.getDetail()).containsExactly(VARIANT_ID.toString());
        });
    }

    /* ============ actualización ============ */

    @Test
    void actualizarUnPedidoCargaLaEntidadGestionadaEnVezDeInsertarOtra() {
        CustomerOrderEntity managed = CustomerOrderEntity.builder().orderNumber("NX-100").build();
        managed.setId(ORDER_ID);
        when(orderJpaRepositoryAdapter.findById(ORDER_ID)).thenReturn(Optional.of(managed));

        repository.update(Order.builder().id(ORDER_ID).build());

        assertThat(capturedSaved()).isSameAs(managed);
    }

    @Test
    void actualizarUnPedidoInexistenteFallaEnVezDeCrearlo() {
        // Sin esta comprobación un id obsoleto insertaría un pedido nuevo con datos a medias.
        when(orderJpaRepositoryAdapter.findById(ORDER_ID)).thenReturn(Optional.empty());
        Order order = Order.builder().id(ORDER_ID).build();

        assertThatThrownBy(() -> repository.save(order)).isInstanceOf(NotFoundException.class)
                .hasMessage("Order not found");
    }

    @Test
    void unCambioDeEstadoNoBorraLasLineasNiRehaceLasDireccionesDelPedido() {
        // Las transiciones (enviar, entregar) viajan en un modelo SIN líneas: reconstruir la colección
        // dejaría el pedido sin artículos y sin dirección de envío.
        AddressEntity shipping = AddressEntity.builder().fullName("Ana Gómez").build();
        CustomerOrderEntity managed = CustomerOrderEntity.builder().orderNumber("NX-100").build();
        managed.setId(ORDER_ID);
        managed.setShippingAddress(shipping);
        managed.getItems().add(OrderItemEntity.builder().quantity(2).build());
        when(orderJpaRepositoryAdapter.findById(ORDER_ID)).thenReturn(Optional.of(managed));

        repository.update(Order.builder().id(ORDER_ID).build());

        CustomerOrderEntity saved = capturedSaved();
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getShippingAddress()).isSameAs(shipping);
        verify(addressRepository, never()).save(any(AddressEntity.class));
        verify(productRepository, never()).findById(any());
    }

    @Test
    void reenviarLasMismasLineasSobreUnPedidoQueYaLasTieneNoLasDuplica() {
        CustomerOrderEntity managed = CustomerOrderEntity.builder().build();
        managed.setId(ORDER_ID);
        managed.setShippingAddress(AddressEntity.builder().build());
        managed.getItems().add(OrderItemEntity.builder().quantity(1).build());
        when(orderJpaRepositoryAdapter.findById(ORDER_ID)).thenReturn(Optional.of(managed));
        Order order = Order.builder().id(ORDER_ID)
                .items(List.of(OrderItem.builder().productId(PRODUCT_ID).quantity(1).build())).build();

        repository.update(order);

        assertThat(capturedSaved().getItems()).hasSize(1);
        verify(productRepository, never()).findById(any());
    }

    /* ============ lecturas ============ */

    @Test
    void getByIdDevuelveNuloCuandoNoExisteEnVezDeReventar() {
        when(orderJpaRepositoryAdapter.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThat(repository.getById(ORDER_ID)).isNull();
    }

    @Test
    void findByIdDevuelveVacioCuandoNoExiste() {
        when(orderJpaRepositoryAdapter.findById(ORDER_ID)).thenReturn(Optional.empty());

        assertThat(repository.findById(ORDER_ID)).isEmpty();
    }

    @Test
    void lasBusquedasPorNumeroYSeguimientoDevuelvenElModeloDeDominio() {
        CustomerOrderEntity entity = CustomerOrderEntity.builder().orderNumber("NX-100").build();
        when(orderJpaRepositoryAdapter.findByOrderNumber("NX-100")).thenReturn(Optional.of(entity));
        when(orderJpaRepositoryAdapter.findByTrackingNumber("TRK-1")).thenReturn(Optional.of(entity));

        assertThat(repository.findByOrderNumber("NX-100")).isPresent();
        assertThat(repository.findByTrackingNumber("TRK-1")).isPresent();
    }

    @Test
    void listadosYExistenciaDeleganEnElAdaptadorJpa() {
        CustomerOrderEntity entity = CustomerOrderEntity.builder().build();
        UUID partnerAppId = UUID.randomUUID();
        when(orderJpaRepositoryAdapter.findAll()).thenReturn(List.of(entity, entity));
        when(orderJpaRepositoryAdapter.findByPartnerAppId(partnerAppId)).thenReturn(List.of(entity));
        when(orderJpaRepositoryAdapter.existsById(ORDER_ID)).thenReturn(true);

        assertThat(repository.findAll()).hasSize(2);
        assertThat(repository.findByPartnerAppId(partnerAppId)).hasSize(1);
        assertThat(repository.existsById(ORDER_ID)).isTrue();

        repository.delete(ORDER_ID);
        verify(orderJpaRepositoryAdapter).deleteById(ORDER_ID);
    }
}
