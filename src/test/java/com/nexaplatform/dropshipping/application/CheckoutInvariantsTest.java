package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.FulfillmentRouter;
import com.nexaplatform.dropshipping.application.service.OperatorCommissionService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.ProductSubsidyService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.OrderUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserAddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Invariantes del checkout del comprador.
 *
 * <p>Es el paso en el que se cobra, así que aquí se concentran los fallos caros: cobrar dos veces por
 * darle dos veces a "Pagar", cobrar sin poder enviar, o dejar la dirección de otro usuario adjunta a un
 * pedido. También se fija que el saldo sólo se descuenta cuando el método elegido es el monedero: para
 * tarjeta o PayPal el pedido queda pendiente y el cobro lo cierra la pasarela.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CheckoutInvariantsTest {

    @Mock
    com.nexaplatform.dropshipping.domain.repository.OrderRepository orderRepository;
    @Mock
    com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository orderEntityRepository;
    @Mock
    ProductRepository productRepository;
    @Mock
    ProductVariantRepository variantRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    ShopConnectionRepository shopConnectionRepository;
    @Mock
    UserAddressRepository userAddressRepository;
    @Mock
    WebhookDispatcherService webhooks;
    @Mock
    WalletUseCase walletUseCase;
    @Mock
    NotificationsPublisher notificationsPublisher;
    @Mock
    PricingService pricingService;
    /** Sin escalera de cantidades: estas pruebas miden otra cosa y un tramo la falsearía. */
    @Mock
    ProductPriceTierRepository priceTierRepository;
    @Mock
    AffiliateProgramService affiliateProgramService;
    @Mock
    StockService stockService;
    @Mock
    PaymentUseCase paymentUseCase;
    @Mock
    OrderEmailService orderEmailService;
    @Mock
    FulfillmentProvider fulfillment;
    @Mock
    FulfillmentRouter router;
    @Mock
    CheckoutTotalsService checkoutTotalsService;
    @Mock
    ProductSubsidyService productSubsidyService;
    @Mock
    OperatorCommissionService operatorCommissionService;
    @Mock
    OrderIndexer orderIndexer;
    @Mock
    OrderSearchService orderSearchService;

    @Mock
    com.nexaplatform.dropshipping.application.service.SupplierPurchaseService supplierPurchaseService;

    @org.mockito.Mock
    com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupService declarationGroups;

    @org.mockito.Spy
    com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService customsDutyLinesService = new com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService(
            null);
    @org.mockito.Mock
    com.nexaplatform.dropshipping.application.service.UnserviceableZoneService unserviceableZoneService;

    @InjectMocks
    private OrderUseCaseImpl subject;

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID productId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void destinoSoportadoPorDefecto() {
        // Sin bolsas asignadas: estas pruebas no miden la subvención, y un mock sin preparar devolvería
        // null donde el contrato dice que siempre hay dos importes.
        org.mockito.Mockito.lenient().when(productSubsidyService.bagsFor(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.nexaplatform.dropshipping.application.service.ProductSubsidyService.Bags.NONE);
        when(fulfillment.isSupported(anyString())).thenReturn(true);
    }

    private static AddressInput address(String country) {
        return new AddressInput("Nombre Apellido", "+34600000000", "cliente@example.com", "Calle 1", null, "Madrid",
                "Madrid", "28001", country);
    }

    private MeCheckoutDtoIn request(String method, UUID savedAddressId) {
        MeCheckoutDtoIn req = new MeCheckoutDtoIn();
        req.setPaymentMethod(method);
        req.setShippingAddressId(savedAddressId);
        if (savedAddressId == null) {
            req.setShippingAddressInline(address("ES"));
        }
        MeCheckoutDtoIn.Item item = new MeCheckoutDtoIn.Item();
        item.setProductId(productId);
        item.setQuantity(1);
        req.setItems(List.of(item));
        return req;
    }

    /** Deja el catálogo, el precio y los totales listos para que createOrder llegue al final. */
    private void happyPath(int totalCents) {
        ProductEntity p = new ProductEntity();
        p.setId(productId);
        p.setSlug("reloj");
        p.setBasePrice(new BigDecimal("70.00"));
        p.setImages(new ArrayList<>());
        p.setStatus(ProductStatus.ACTIVE);
        when(productRepository.findById(productId)).thenReturn(Optional.of(p));
        when(pricingService.priceFor(any(), any(), anyInt(), any()))
                .thenReturn(new PricingService.PricedAmount(new BigDecimal("10.00"), new BigDecimal("40.00"), null,
                        "USD", "$", null, null, null, null, null, null, null, null, null));
        when(router.cotizar(anyString(), any(), anyList()))
                .thenReturn(new ShippingQuote(true, "ES", 0, "YunExpress", "Standard", 7, 15, "EU"));
        when(affiliateProgramService.referralDiscountCents(any(), anyLong())).thenReturn(0L);
        CheckoutTotalsService.CheckoutTotals totals = mock(CheckoutTotalsService.CheckoutTotals.class);
        when(totals.blocked()).thenReturn(false);
        when(totals.shippingCents()).thenReturn(0);
        when(totals.taxCents()).thenReturn(0);
        when(totals.totalCents(anyInt())).thenReturn(totalCents);
        when(checkoutTotalsService.compute(any(), any(), anyInt(), anyInt(), anyList(), any())).thenReturn(totals);
        when(orderRepository.save(any())).thenAnswer(i -> {
            Order o = i.getArgument(0);
            if (o.getId() == null) {
                o.setId(UUID.randomUUID());
            }
            when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));
            return o;
        });
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setEmail("cliente@example.com");
        user.setLanguage("es");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    // ---------------------------------------------------------------- producto retirado

    @ParameterizedTest
    @EnumSource(value = ProductStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void unProductoRetiradoDelCatalogoNoSePuedeComprar(ProductStatus retirado) {
        // El carrito vive en el navegador del cliente: añade hoy, el administrador lo pausa o lo archiva
        // mañana —el proveedor lo dio de baja, se agotó, no puede venderse— y el cliente termina la
        // compra la semana que viene. Sin esta comprobación el pedido se aceptaba y se COBRABA, con el
        // escaparate sin enseñar ya el producto.
        happyPath(4000);
        ProductEntity p = productRepository.findById(productId).orElseThrow();
        p.setStatus(retirado);
        MeCheckoutDtoIn req = request("WALLET", null);
        req.setShippingAddressInline(address("ES"));

        assertThatThrownBy(() -> subject.checkout(userId, req, null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo("PRODUCT_UNAVAILABLE"));

        verify(orderRepository, never()).save(any());
        verify(walletUseCase, never()).charge(any(), anyLong(), any(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- destino

    @Test
    void noSeCobraSiNoSePuedeEnviarAEseDestino() {
        // Cobrar y luego descubrir que no hay transporte deja un reembolso y un cliente enfadado.
        when(fulfillment.isSupported("XX")).thenReturn(false);
        MeCheckoutDtoIn req = request("WALLET", null);
        req.setShippingAddressInline(address("XX"));

        assertThatThrownBy(() -> subject.checkout(userId, req, null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("No realizamos envíos");

        verify(walletUseCase, never()).charge(any(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void sinDireccionDeEnvioNoHayPedido() {
        MeCheckoutDtoIn req = request("WALLET", null);
        req.setShippingAddressInline(null);

        assertThatThrownBy(() -> subject.checkout(userId, req, null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("Shipping address");
    }

    @Test
    void laDireccionGuardadaDeOtroUsuarioNoSePuedeUsar() {
        // Devolver 404 y no 403: un 403 confirmaría al atacante que esa dirección existe.
        UUID addressId = UUID.randomUUID();
        UserEntity otro = new UserEntity();
        otro.setId(UUID.randomUUID());
        UserAddressEntity ajena = new UserAddressEntity();
        ajena.setId(addressId);
        ajena.setUser(otro);
        when(userAddressRepository.findById(addressId)).thenReturn(Optional.of(ajena));

        MeCheckoutDtoIn req = request("WALLET", addressId);

        assertThatThrownBy(() -> subject.checkout(userId, req, null)).isInstanceOf(NotFoundException.class);
        verify(walletUseCase, never()).charge(any(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    void unaDireccionGuardadaQueNoExisteNoCreaPedido() {
        UUID addressId = UUID.randomUUID();
        when(userAddressRepository.findById(addressId)).thenReturn(Optional.empty());
        MeCheckoutDtoIn req = request("WALLET", addressId);

        assertThatThrownBy(() -> subject.checkout(userId, req, null)).isInstanceOf(NotFoundException.class);
    }

    // ---------------------------------------------------------------- método de pago

    @Test
    void pagarConSaldoDescuentaElTotalYDejaElPedidoPagado() {
        happyPath(9540);

        Order o = subject.checkout(userId, request("WALLET", null), null);

        verify(walletUseCase).charge(eq(userId), eq(9540L), any(), anyString(), anyString());
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void sinIndicarMetodoSePagaConSaldo() {
        happyPath(9540);

        subject.checkout(userId, request(null, null), null);

        verify(walletUseCase).charge(eq(userId), eq(9540L), any(), anyString(), anyString());
    }

    @Test
    void conTarjetaElPedidoQuedaPendienteYNoSeTocaElSaldo() {
        // El cobro lo cierra la pasarela después; descontar aquí cobraría dos veces.
        happyPath(9540);

        Order o = subject.checkout(userId, request("CARD", null), null);

        verify(walletUseCase, never()).charge(any(), anyLong(), any(), anyString(), anyString());
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void conPaypalYConCriptoElPedidoTambienQuedaPendiente() {
        happyPath(9540);

        assertThat(subject.checkout(userId, request("PAYPAL", null), null).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(subject.checkout(userId, request("USDT", null), null).getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(walletUseCase, never()).charge(any(), anyLong(), any(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- idempotencia

    @Test
    void reintentarElMismoCarritoReutilizaElPedidoSinPagarEnLugarDeDuplicarlo() {
        // Un intento abandonado en la pasarela más un reintento no pueden dejar dos pedidos.
        happyPath(9540);
        UUID existenteId = UUID.randomUUID();
        CustomerOrderEntity existente = new CustomerOrderEntity();
        existente.setId(existenteId);
        Order pendiente = new Order();
        pendiente.setId(existenteId);
        pendiente.setOrderNumber("NX-YA-EXISTE");
        pendiente.setStatus(OrderStatus.PENDING);
        pendiente.setTotalCents(9540);
        pendiente.setSubtotalCents(9540);
        pendiente.setCurrency("USD");
        when(orderEntityRepository.findFirstByUserIdAndIdempotencyKeyAndStatusInOrderByCreatedAtDesc(eq(userId),
                eq("carrito-1"), any())).thenReturn(Optional.of(existente));
        when(orderRepository.findById(existenteId)).thenReturn(Optional.of(pendiente));

        Order o = subject.checkout(userId, request("CARD", null), "carrito-1");

        assertThat(o.getId()).isEqualTo(existenteId);
        // Ni se vuelve a avisar al comprador ni se devenga otra comisión de afiliado por el mismo pedido.
        verify(notificationsPublisher, never()).orderPlaced(any(), anyString(), anyString(), anyString(), anyString(),
                anyString());
        verify(affiliateProgramService, never()).onOrderPlaced(any(), any(), anyLong(), anyString());
    }

    @Test
    void unPedidoNuevoSiAvisaAlCompradorYDevengaComision() {
        happyPath(9540);
        when(orderEntityRepository.findFirstByUserIdAndIdempotencyKeyAndStatusInOrderByCreatedAtDesc(any(), anyString(),
                any())).thenReturn(Optional.empty());

        subject.checkout(userId, request("CARD", null), "carrito-nuevo");

        verify(notificationsPublisher).orderPlaced(any(), anyString(), anyString(), anyString(), anyString(),
                anyString());
        verify(affiliateProgramService).onOrderPlaced(any(), eq(userId), anyLong(), anyString());
    }

    @Test
    void sinClaveDeCarritoCadaIntentoCreaSuPedido() {
        // Sin clave no hay forma de saber que es el mismo carrito; se crea uno nuevo.
        happyPath(9540);

        subject.checkout(userId, request("CARD", null), null);

        verify(orderEntityRepository, never()).findFirstByUserIdAndIdempotencyKeyAndStatusInOrderByCreatedAtDesc(any(),
                anyString(), any());
    }

    // ---------------------------------------------------------------- comisión de afiliado

    @Test
    void laComisionSeCalculaSobreElImporteDeProductoSinEnvioNiImpuesto() {
        // Pagar comisión sobre el envío y el IVA sería pagar por dinero que no es margen.
        happyPath(9540);

        subject.checkout(userId, request("CARD", null), null);

        // subtotal 40,00 (1 × 40,00) − descuento 0 = 4000
        verify(affiliateProgramService).onOrderPlaced(any(), eq(userId), eq(4000L), anyString());
    }
}
