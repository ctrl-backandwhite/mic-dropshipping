package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemInput;
import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.OperatorCommissionService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.OrderUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.OverThresholdPolicy;
import com.nexaplatform.dropshipping.domain.enums.TaxMode;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopConnectionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserAddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.DutyParcel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pedidos: compra desde la web (checkout), transiciones de estado y listados del panel. Lo que se fija
 * aquí es dinero y confianza — lo que se cobra, cuándo se puede cancelar y qué pedidos ve cada uno.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov06OrderUseCaseImplTest {

    @Mock
    OrderRepository orderRepository;
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
    CheckoutTotalsService checkoutTotalsService;
    @Mock
    OperatorCommissionService operatorCommissionService;
    @Mock
    OrderIndexer orderIndexer;
    @Mock
    OrderSearchService orderSearchService;

    @Mock
    com.nexaplatform.dropshipping.application.service.SupplierPurchaseService supplierPurchaseService;

    @org.mockito.Spy
    com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService customsDutyLinesService =
            new com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService();
    @org.mockito.Mock
    com.nexaplatform.dropshipping.application.service.UnserviceableZoneService unserviceableZoneService;


    @InjectMocks
    OrderUseCaseImpl useCase;

    /** Almacén en memoria que hace que save()/findById() se comporten como el adaptador real. */
    private final Map<UUID, Order> guardadas = new HashMap<>();

    @BeforeEach
    void preparar() {
        when(fulfillment.isSupported(anyString())).thenReturn(true);
        when(fulfillment.quote(any(), any(FulfillmentProvider.ParcelSpec.class)))
                .thenReturn(ShippingQuote.unsupported("XX"));
        when(checkoutTotalsService.compute(any(), any(), anyInt(), anyInt(), anyList()))
                .thenAnswer(inv -> totalesNeutros(inv.getArgument(3)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(UUID.randomUUID());
            }
            guardadas.put(o.getId(), o);
            return o;
        });
        when(orderRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(guardadas.get(inv.getArgument(0))));
    }

    /* ============================ checkout ============================ */

    /**
     * Pago con el saldo del monedero: se cobra el total del pedido y la orden nace ya PAGADA, con su
     * correo de confirmación. Es el único método que cobra aquí; los demás salen a la pasarela.
     */
    @Test
    void elCheckoutConMonederoCobraElTotalYDejaElPedidoPagado() {
        UUID userId = UUID.randomUUID();
        ProductEntity producto = producto("12.50");
        prepararCatalogo(producto, "12.50");
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario("comprador@x.com", "es")));

        Order pedido = useCase.checkout(userId, checkout(producto.getId(), 2, "WALLET"), "idem-1");

        assertThat(pedido.getStatus()).isEqualTo(OrderStatus.PAID);
        // La clave de idempotencia del cargo va ACOTADA AL PEDIDO ("checkout-<orderId>"), no la del cliente,
        // para que no se pueda reutilizar la misma clave entre pedidos distintos y colar cargos a cero.
        verify(walletUseCase).charge(eq(userId), eq(2500L), any(),
                org.mockito.ArgumentMatchers.startsWith("checkout-"), anyString());
        // El dinero está cobrado → la mercancía entra en la cola de compras de 1688. Sin esta llamada el
        // freno veía cero compras, trataba el pedido como antiguo y dejaba emitir la guía sin comprar.
        verify(supplierPurchaseService).planPurchases(any(Order.class));
        verify(orderEmailService).paymentConfirmed(any(Order.class), eq("comprador@x.com"), eq("es"), eq("WALLET"));
    }

    /** Tarjeta/PayPal: el pedido queda PENDIENTE y NO se toca el monedero (el cobro va por la pasarela). */
    @Test
    void elCheckoutConTarjetaNoCobraDelMonedero() {
        UUID userId = UUID.randomUUID();
        ProductEntity producto = producto("12.50");
        prepararCatalogo(producto, "12.50");
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario("comprador@x.com", "es")));

        Order pedido = useCase.checkout(userId, checkout(producto.getId(), 1, "CARD"), null);

        assertThat(pedido.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(walletUseCase, never()).charge(any(), anyLong(), any(), anyString(), anyString());
        verify(orderEmailService, never()).paymentConfirmed(any(), anyString(), anyString(), anyString());
        // El pago externo puede tardar o abandonarse: se avisa «hemos recibido tu pedido» para que el
        // cliente tenga constancia escrita antes de la factura.
        verify(orderEmailService).placedAwaitingPayment(any(Order.class), eq("comprador@x.com"), eq("es"));
        // Y NO se planifican compras todavía: aún no hay dinero cobrado.
        verify(supplierPurchaseService, never()).planPurchases(any());
    }

    /**
     * Mismo carrito reintentado con la MISMA clave de idempotencia: se reutiliza la orden sin pagar en vez
     * de crear un duplicado, no se vuelve a avisar de "pedido recibido" y no se registra otra vez la
     * comisión del afiliado. Sin esto, abandonar la pasarela y reintentar dejaba dos pedidos.
     */
    @Test
    void elMismoCarritoReintentadoReutilizaElPedidoYNoDuplicaAvisos() {
        UUID userId = UUID.randomUUID();
        UUID pedidoId = UUID.randomUUID();
        ProductEntity producto = producto("12.50");
        Order existente = Order.builder().id(pedidoId).orderNumber("NX-1").status(OrderStatus.PENDING)
                .currency("USD").subtotalCents(1250).totalCents(1250).items(new ArrayList<>()).build();
        CustomerOrderEntity entidad = new CustomerOrderEntity();
        entidad.setId(pedidoId);
        when(orderEntityRepository.findFirstByUserIdAndIdempotencyKeyAndStatusInOrderByCreatedAtDesc(eq(userId),
                eq("idem-1"), any())).thenReturn(Optional.of(entidad));
        when(orderRepository.findById(pedidoId)).thenReturn(Optional.of(existente));
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario("comprador@x.com", "es")));

        useCase.checkout(userId, checkout(producto.getId(), 1, "CARD"), "idem-1");

        verify(productRepository, never()).findById(any());
        verify(notificationsPublisher, never()).orderPlaced(any(), anyString(), anyString(), anyString(), anyString(),
                anyString());
        verify(affiliateProgramService, never()).onOrderPlaced(any(), any(), anyLong(), anyString());
    }

    /** La clave de idempotencia se sella en el pedido nuevo; si no, el reintento crearía un duplicado. */
    @Test
    void elCheckoutSellaLaClaveDeIdempotenciaEnElPedidoNuevo() {
        UUID userId = UUID.randomUUID();
        ProductEntity producto = producto("12.50");
        prepararCatalogo(producto, "12.50");
        CustomerOrderEntity entidad = new CustomerOrderEntity();
        when(orderEntityRepository.findById(any())).thenReturn(Optional.of(entidad));

        useCase.checkout(userId, checkout(producto.getId(), 1, "CARD"), "  idem-7  ");

        assertThat(entidad.getIdempotencyKey()).isEqualTo("idem-7"); // recortada
        verify(orderEntityRepository).save(entidad);
    }

    /**
     * La comisión del afiliado se calcula sobre lo que el cliente paga POR EL PRODUCTO (subtotal menos su
     * descuento de referido), nunca sobre envío ni impuestos: si no, se pagaría comisión sobre dinero que
     * la plataforma no ingresa.
     */
    @Test
    void laComisionDeAfiliadoSeCalculaSobreElProductoSinEnvioNiImpuesto() {
        UUID userId = UUID.randomUUID();
        ProductEntity producto = producto("100.00");
        prepararCatalogo(producto, "100.00");
        when(affiliateProgramService.referralDiscountCents(userId, 10000L)).thenReturn(1000L);
        when(fulfillment.quote(any(), any(FulfillmentProvider.ParcelSpec.class)))
                .thenReturn(new ShippingQuote(true, "ES", 500, "YUN", "STD", 5, 10, "EU"));

        useCase.checkout(userId, checkout(producto.getId(), 1, "CARD"), null);

        verify(affiliateProgramService).onOrderPlaced(any(), eq(userId), eq(9000L), eq("USD"));
    }

    /** Una dirección guardada de OTRO usuario se responde 404: un 403 confirmaría que existe. */
    @Test
    void unaDireccionGuardadaAjenaNoSeFiltraNiConfirma() {
        UUID userId = UUID.randomUUID();
        UUID direccionId = UUID.randomUUID();
        UserAddressEntity ajena = new UserAddressEntity();
        ajena.setUser(usuario("otro@x.com", "es"));
        ajena.setCountry("ES");
        when(userAddressRepository.findById(direccionId)).thenReturn(Optional.of(ajena));
        MeCheckoutDtoIn req = checkout(UUID.randomUUID(), 1, "CARD");
        req.setShippingAddressInline(null);
        req.setShippingAddressId(direccionId);

        assertThatThrownBy(() -> useCase.checkout(userId, req, null)).isInstanceOf(NotFoundException.class);
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void elCheckoutSinDireccionDeEnvioSeRechaza() {
        UUID userId = UUID.randomUUID();
        MeCheckoutDtoIn req = checkout(UUID.randomUUID(), 1, "CARD");
        req.setShippingAddressInline(null);

        assertThatThrownBy(() -> useCase.checkout(userId, req, null)).isInstanceOf(BusinessException.class);
    }

    /**
     * Destino sin transporte: se rechaza ANTES de crear y cobrar. Descubrirlo después dejaría un cobro
     * hecho que habría que reembolsar a mano.
     */
    @Test
    void aUnDestinoSinTransporteNoSeCreaNiSeCobraNada() {
        UUID userId = UUID.randomUUID();
        when(fulfillment.isSupported("CU")).thenReturn(false);
        MeCheckoutDtoIn req = checkout(UUID.randomUUID(), 1, "WALLET");
        req.setShippingAddressInline(direccion("CU"));

        assertThatThrownBy(() -> useCase.checkout(userId, req, null)).isInstanceOf(BusinessException.class);
        verify(orderRepository, never()).save(any(Order.class));
        verify(walletUseCase, never()).charge(any(), anyLong(), any(), anyString(), anyString());
    }

    /* ============================ creación de líneas ============================ */

    /**
     * Cota de cantidad por línea: sin ella, {@code precio * cantidad} desbordaba el entero y el pedido se
     * cobraba por una fracción de su valor. Se valida en el dominio, no solo en el DTO.
     */
    @Test
    void seRechazaUnaCantidadPorLineaFueraDeRango() {
        ProductEntity producto = producto("10.00");
        CreateOrderRequest cero = pedidoDe(producto.getId(), 0);
        CreateOrderRequest desbordante = pedidoDe(producto.getId(), 100_001);

        assertThatThrownBy(() -> useCase.createOrder(null, null, cero)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("cantidad por línea");
        assertThatThrownBy(() -> useCase.createOrder(null, null, desbordante)).isInstanceOf(BusinessException.class);
        verify(productRepository, never()).findById(any());
    }

    @Test
    void unPedidoSinLineasSeRechaza() {
        CreateOrderRequest vacio = new CreateOrderRequest("EXT", direccion("ES"), null, List.of(), null);

        assertThatThrownBy(() -> useCase.createOrder(null, null, vacio)).isInstanceOf(BusinessException.class);
    }

    /**
     * La foto congelada en la línea es la de la VARIANTE comprada (el color que se pidió), y de ella la ya
     * espejada en nuestro almacenamiento antes que la del proveedor, que caduca.
     */
    @Test
    void laLineaCongelaLaFotoDeLaVarianteAntesQueLaDelProducto() {
        ProductEntity producto = producto("10.00");
        producto.setImages(new ArrayList<>(List.of(imagen("https://1688/producto.jpg"))));
        ProductVariantEntity variante = ProductVariantEntity.builder().sku("V1").price(new BigDecimal("10.00"))
                .active(true).imageCdnUrl("https://cdn/variante.jpg").imageSourceUrl("https://1688/variante.jpg")
                .build();
        variante.setId(UUID.randomUUID());
        prepararCatalogo(producto, "10.00");
        when(variantRepository.findById(variante.getId())).thenReturn(Optional.of(variante));

        Order pedido = useCase.createOrder(null, null,
                new CreateOrderRequest("EXT", direccion("ES"), null,
                        List.of(new OrderItemInput(producto.getId(), variante.getId(), 1)), null));

        assertThat(pedido.getItems().get(0).getImageUrlSnapshot()).isEqualTo("https://cdn/variante.jpg");
    }

    /** Sin variante (o sin foto en ella) se congela la primera imagen del producto. */
    @Test
    void sinVarianteSeCongelaLaPrimeraImagenDelProducto() {
        ProductEntity producto = producto("10.00");
        producto.setImages(new ArrayList<>(List.of(imagen("https://1688/producto.jpg"))));
        prepararCatalogo(producto, "10.00");

        Order pedido = useCase.createOrder(null, null, pedidoDe(producto.getId(), 1));

        assertThat(pedido.getItems().get(0).getImageUrlSnapshot()).isEqualTo("https://1688/producto.jpg");
    }

    /**
     * El título se congela en el idioma del COMPRADOR (no en chino), para que la factura y el correo
     * salgan en un solo idioma coherente.
     */
    @Test
    void elTituloSeCongelaEnElIdiomaDelComprador() {
        UUID userId = UUID.randomUUID();
        ProductEntity producto = producto("10.00");
        producto.setTranslations(new ArrayList<>(List.of(traduccion(producto, "en", "Blue jacket"),
                traduccion(producto, "fr", "Veste bleue"))));
        prepararCatalogo(producto, "10.00");
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario("fr@x.com", "fr")));

        Order pedido = useCase.createOrder(null, userId, pedidoDe(producto.getId(), 1));

        assertThat(pedido.getItems().get(0).getTitleSnapshot()).isEqualTo("Veste bleue");
    }

    /** Sin traducción en el idioma del comprador se cae a inglés antes que al chino. */
    @Test
    void sinTraduccionEnSuIdiomaElTituloCaeAIngles() {
        UUID userId = UUID.randomUUID();
        ProductEntity producto = producto("10.00");
        producto.setTranslations(new ArrayList<>(List.of(traduccion(producto, "en", "Blue jacket"))));
        prepararCatalogo(producto, "10.00");
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario("nl@x.com", "nl")));

        Order pedido = useCase.createOrder(null, userId, pedidoDe(producto.getId(), 1));

        assertThat(pedido.getItems().get(0).getTitleSnapshot()).isEqualTo("Blue jacket");
    }

    /** Sin ninguna traducción queda el título chino: es lo único que hay, pero nunca se guarda vacío. */
    @Test
    void sinNingunaTraduccionQuedaElTituloChino() {
        ProductEntity producto = producto("10.00");
        prepararCatalogo(producto, "10.00");

        Order pedido = useCase.createOrder(null, null, pedidoDe(producto.getId(), 1));

        assertThat(pedido.getItems().get(0).getTitleSnapshot()).isEqualTo("蓝色外套");
    }

    /* ============================ transiciones de estado ============================ */

    /** "En camino" solo desde "enviado al proveedor": marcarlo antes mentiría al cliente. */
    @Test
    void soloSePuedeMarcarEnCaminoUnPedidoYaEnviadoAlProveedor() {
        UUID id = UUID.randomUUID();
        Order pagado = Order.builder().id(id).status(OrderStatus.PAID).build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(pagado));

        assertThatThrownBy(() -> useCase.shipOrder(id)).isInstanceOf(BusinessException.class);
        verify(orderRepository, never()).save(any(Order.class));
    }

    /** La entrega solo desde "en camino", y al entregar se acredita la comisión del operador. */
    @Test
    void alEntregarSeAcreditaLaComisionDelOperador() {
        UUID id = UUID.randomUUID();
        Order enCamino = Order.builder().id(id).status(OrderStatus.SHIPPED).build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(enCamino));

        useCase.deliverOrder(id);

        assertThat(enCamino.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(enCamino.getDeliveredAt()).isNotNull();
        verify(operatorCommissionService).recordDelivery(enCamino);
    }

    @Test
    void noSePuedeEntregarUnPedidoQueNoEstaEnCamino() {
        UUID id = UUID.randomUUID();
        Order reenviado = Order.builder().id(id).status(OrderStatus.FORWARDED).build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(reenviado));

        assertThatThrownBy(() -> useCase.deliverOrder(id)).isInstanceOf(BusinessException.class);
        verify(operatorCommissionService, never()).recordDelivery(any());
    }

    /** Cancelar dos veces no reembolsa dos veces: la segunda llamada es un no-op. */
    @Test
    void cancelarUnPedidoYaCanceladoNoVuelveAReembolsar() {
        UUID id = UUID.randomUUID();
        Order cancelado = Order.builder().id(id).status(OrderStatus.CANCELLED).userId(UUID.randomUUID())
                .totalCents(1000).build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(cancelado));

        useCase.cancelOrder(id);

        verify(orderRepository, never()).save(any(Order.class));
        verify(walletUseCase, never()).deposit(any(), anyLong(), any(), anyString(), anyString());
    }

    /** Reembolsar dos veces tampoco: el segundo intento no genera otro abono. */
    @Test
    void reembolsarUnPedidoYaReembolsadoEsIdempotente() {
        UUID id = UUID.randomUUID();
        Order reembolsado = Order.builder().id(id).status(OrderStatus.REFUNDED).userId(UUID.randomUUID())
                .totalCents(1000).build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(reembolsado));

        useCase.refundOrder(id);

        verify(walletUseCase, never()).deposit(any(), anyLong(), any(), anyString(), anyString());
    }

    /** Un pedido cancelado ya no se reembolsa: el dinero se devolvió al cancelarlo. */
    @Test
    void noSeReembolsaUnPedidoCancelado() {
        UUID id = UUID.randomUUID();
        Order cancelado = Order.builder().id(id).status(OrderStatus.CANCELLED).build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(cancelado));

        assertThatThrownBy(() -> useCase.refundOrder(id)).isInstanceOf(BusinessException.class);
    }

    /** Al cancelar se anula la comisión de afiliado: no se paga comisión por una venta que no existió. */
    @Test
    void cancelarAnulaLaComisionDeAfiliado() {
        UUID id = UUID.randomUUID();
        Order pendiente = Order.builder().id(id).status(OrderStatus.PENDING).userId(UUID.randomUUID())
                .totalCents(1000).build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(pendiente));

        useCase.cancelOrder(id);

        verify(affiliateProgramService).rejectForOrder(id);
    }

    /* ============================ listados del panel ============================ */

    /** El buscador del panel encuentra por número de pedido, id externo o nombre del destinatario. */
    @Test
    void elBuscadorDelPanelMiraNumeroIdExternoYDestinatario() {
        Order porNumero = pedidoDeAdmin("NX-100", null, null, OrderStatus.PAID, Instant.now());
        Order porExterno = pedidoDeAdmin("NX-200", "SHOPIFY-77", null, OrderStatus.PAID, Instant.now());
        Order porNombre = pedidoDeAdmin("NX-300", null, "Ana Pérez", OrderStatus.PAID, Instant.now());
        when(orderRepository.findAll()).thenReturn(List.of(porNumero, porExterno, porNombre));

        assertThat(useCase.listAdminOrders(null, "nx-100")).containsExactly(porNumero);
        assertThat(useCase.listAdminOrders(null, "shopify")).containsExactly(porExterno);
        assertThat(useCase.listAdminOrders(null, "ana")).containsExactly(porNombre);
    }

    /** El filtro de estado no distingue mayúsculas y el listado sale del más reciente al más antiguo. */
    @Test
    void elPanelFiltraPorEstadoYOrdenaDelMasRecienteAlMasAntiguo() {
        Instant ahora = Instant.now();
        Order antiguo = pedidoDeAdmin("NX-1", null, null, OrderStatus.PAID, ahora.minusSeconds(600));
        Order reciente = pedidoDeAdmin("NX-2", null, null, OrderStatus.PAID, ahora);
        Order otroEstado = pedidoDeAdmin("NX-3", null, null, OrderStatus.CANCELLED, ahora);
        when(orderRepository.findAll()).thenReturn(List.of(antiguo, reciente, otroEstado));

        List<Order> resultado = useCase.listAdminOrders("paid", null);

        assertThat(resultado).containsExactly(reciente, antiguo);
    }

    /** Con el índice disponible la página se sirve de sus ids, respetando su orden y su total. */
    @Test
    void laPaginaDelPanelSeSirveDelIndiceCuandoEstaDisponible() {
        UUID primero = UUID.randomUUID();
        UUID segundo = UUID.randomUUID();
        Order a = Order.builder().id(primero).status(OrderStatus.PAID).build();
        Order b = Order.builder().id(segundo).status(OrderStatus.PAID).build();
        when(orderSearchService.pageIds(null, null, 0, 2))
                .thenReturn(Optional.of(new OrderSearchService.IdPage(List.of(primero, segundo), 57)));
        when(orderRepository.findById(primero)).thenReturn(Optional.of(a));
        when(orderRepository.findById(segundo)).thenReturn(Optional.of(b));

        OrderUseCase.OrderPage pagina = useCase.pageAdminOrders(null, null, 0, 2);

        assertThat(pagina.items()).containsExactly(a, b);
        assertThat(pagina.total()).isEqualTo(57);
        verify(orderRepository, never()).findAll();
    }

    /** Un id del índice que ya no está en la base de datos se descarta en vez de romper la página. */
    @Test
    void unIdDelIndiceQueYaNoExisteSeDescarta() {
        UUID vivo = UUID.randomUUID();
        UUID borrado = UUID.randomUUID();
        Order a = Order.builder().id(vivo).status(OrderStatus.PAID).build();
        when(orderSearchService.pageIds(null, null, 0, 10))
                .thenReturn(Optional.of(new OrderSearchService.IdPage(List.of(vivo, borrado), 2)));
        when(orderRepository.findById(vivo)).thenReturn(Optional.of(a));
        when(orderRepository.findById(borrado)).thenReturn(Optional.empty());

        assertThat(useCase.pageAdminOrders(null, null, 0, 10).items()).containsExactly(a);
    }

    /** Índice caído: se pagina en memoria y una página fuera de rango devuelve vacío, no una excepción. */
    @Test
    void sinIndiceSePaginaEnMemoriaYLaPaginaFueraDeRangoVieneVacia() {
        Order uno = pedidoDeAdmin("NX-1", null, null, OrderStatus.PAID, Instant.now());
        when(orderSearchService.pageIds(any(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(orderRepository.findAll()).thenReturn(List.of(uno));

        assertThat(useCase.pageAdminOrders(null, null, 0, 10).items()).containsExactly(uno);
        assertThat(useCase.pageAdminOrders(null, null, 9, 10).items()).isEmpty();
    }

    /* ============================ detalle ============================ */

    /**
     * Dos líneas del mismo producto y mismo precio se funden en una sumando cantidades, y se conserva el
     * SKU resuelto: era el artefacto que inflaba el número de artículos del pedido en el panel.
     */
    @Test
    void elDetalleDeAdminFundeLasLineasDuplicadasDelMismoProducto() {
        UUID id = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        OrderItem sinSku = OrderItem.builder().productId(productId).unitPriceCents(1000).quantity(1)
                .lineTotalCents(1000).titleSnapshot("sin sku").build();
        OrderItem conSku = OrderItem.builder().productId(productId).unitPriceCents(1000).quantity(2)
                .lineTotalCents(2000).skuSnapshot("SKU-A").titleSnapshot("con sku").build();
        Order pedido = Order.builder().id(id).status(OrderStatus.PAID)
                .items(new ArrayList<>(List.of(sinSku, conSku))).build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(pedido));

        Order detalle = useCase.getAdminOrderDetail(id, "es");

        assertThat(detalle.getItems()).hasSize(1);
        assertThat(detalle.getItems().get(0).getQuantity()).isEqualTo(3);
        assertThat(detalle.getItems().get(0).getLineTotalCents()).isEqualTo(3000);
        assertThat(detalle.getItems().get(0).getSkuSnapshot()).isEqualTo("SKU-A");
    }

    /** Precios distintos = artículos distintos: fundirlos cambiaría el importe del pedido. */
    @Test
    void lasLineasConPrecioDistintoNoSeFunden() {
        UUID id = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        OrderItem barata = OrderItem.builder().productId(productId).unitPriceCents(1000).quantity(1).build();
        OrderItem cara = OrderItem.builder().productId(productId).unitPriceCents(1500).quantity(1).build();
        Order pedido = Order.builder().id(id).status(OrderStatus.PAID)
                .items(new ArrayList<>(List.of(barata, cara))).build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(pedido));

        assertThat(useCase.getAdminOrderDetail(id, "es").getItems()).hasSize(2);
    }

    /** El título de cada línea se resuelve al idioma pedido; si no existe, inglés y luego lo congelado. */
    @Test
    void elTituloDeLaLineaSeResuelveAlIdiomaPedidoConSuCadenaDeReserva() {
        UUID id = UUID.randomUUID();
        OrderItem enSuIdioma = OrderItem.builder().productId(UUID.randomUUID()).unitPriceCents(100)
                .productTitles(Map.of("es", "Chaqueta", "en", "Jacket")).build();
        OrderItem soloIngles = OrderItem.builder().productId(UUID.randomUUID()).unitPriceCents(200)
                .productTitles(Map.of("en", "Jacket")).build();
        OrderItem soloCongelado = OrderItem.builder().productId(UUID.randomUUID()).unitPriceCents(300)
                .titleSnapshot("Congelado").build();
        OrderItem soloChino = OrderItem.builder().productId(UUID.randomUUID()).unitPriceCents(400)
                .productTitleZh("蓝色外套").build();
        Order pedido = Order.builder().id(id).userId(UUID.randomUUID()).status(OrderStatus.PAID)
                .items(new ArrayList<>(List.of(enSuIdioma, soloIngles, soloCongelado, soloChino))).build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(pedido));

        Order detalle = useCase.getMyOrderDetail(pedido.getUserId(), id, "ES");

        assertThat(detalle.getItems()).extracting(OrderItem::getTitleSnapshot)
                .containsExactly("Chaqueta", "Jacket", "Congelado", "蓝色外套");
    }

    /** El detalle de un pedido AJENO se responde 404: no se filtra la existencia de pedidos de otros. */
    @Test
    void elDetalleDeUnPedidoAjenoEs404() {
        UUID id = UUID.randomUUID();
        Order ajeno = Order.builder().id(id).userId(UUID.randomUUID()).items(new ArrayList<>()).build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(ajeno));
        UUID intruso = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.getMyOrderDetail(intruso, id, "es")).isInstanceOf(NotFoundException.class);
    }

    /** Cada cliente solo ve SUS pedidos, del más reciente al más antiguo. */
    @Test
    void cadaClienteSoloVeSusPedidosYDelMasRecienteAlMasAntiguo() {
        UUID userId = UUID.randomUUID();
        Instant ahora = Instant.now();
        Order mio1 = Order.builder().id(UUID.randomUUID()).userId(userId).placedAt(ahora.minusSeconds(60)).build();
        Order mio2 = Order.builder().id(UUID.randomUUID()).userId(userId).placedAt(ahora).build();
        Order ajeno = Order.builder().id(UUID.randomUUID()).userId(UUID.randomUUID()).placedAt(ahora).build();
        when(orderRepository.findAll()).thenReturn(List.of(mio1, mio2, ajeno));

        assertThat(useCase.listMyOrders(userId)).containsExactly(mio2, mio1);
    }

    /* ============================ enriquecido y varios ============================ */

    /** El panel enseña el email del cliente y su tienda conectada, que viven en otros agregados. */
    @Test
    void elPedidoSeEnriqueceConElEmailDelClienteYSuTienda() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Order pedido = Order.builder().id(id).userId(userId).status(OrderStatus.PAID).build();
        ShopConnectionEntity tienda = new ShopConnectionEntity();
        tienda.setShopHandle("mi-tienda");
        when(orderRepository.findById(id)).thenReturn(Optional.of(pedido));
        when(userRepository.findById(userId)).thenReturn(Optional.of(usuario("cliente@x.com", "es")));
        when(shopConnectionRepository.findByUser_IdOrderByCreatedAtDesc(userId)).thenReturn(List.of(tienda));

        Order detalle = useCase.getAdminOrderDetail(id, "es");

        assertThat(detalle.getCustomerEmail()).isEqualTo("cliente@x.com");
        assertThat(detalle.getShopName()).isEqualTo("mi-tienda");
        assertThat(detalle.getShopHandle()).isEqualTo("mi-tienda");
    }

    /** Alta manual del admin con un email que no existe: 404 en vez de crear el pedido sin cliente. */
    @Test
    void elAltaManualConUnEmailInexistenteEs404() {
        when(userRepository.findByEmail("nadie@x.com")).thenReturn(Optional.empty());
        CreateOrderRequest req = pedidoDe(UUID.randomUUID(), 1);

        assertThatThrownBy(() -> useCase.createManualOrder("nadie@x.com", req)).isInstanceOf(NotFoundException.class);
        verify(orderRepository, never()).save(any(Order.class));
    }

    /** Los pedidos de demostración están prohibidos salvo que el entorno los habilite expresamente. */
    @Test
    void losPedidosDeDemostracionEstanProhibidosPorDefecto() {
        assertThatThrownBy(() -> useCase.createDemoOrder()).isInstanceOf(ResponseStatusException.class);
        verify(productRepository, never()).findAll();
    }

    /** Habilitados pero sin catálogo activo, se avisa de que no hay producto en vez de crear un pedido vacío. */
    @Test
    void sinProductoActivoNoSeCreaElPedidoDeDemostracion() {
        ReflectionTestUtils.setField(useCase, "demoOrdersEnabled", true);
        when(productRepository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> useCase.createDemoOrder()).isInstanceOf(NotFoundException.class);
    }

    /* ============================ helpers ============================ */

    /** Desglose neutro: sin impuesto ni recargo de despacho, para que el total sea subtotal + envío. */
    private static CheckoutTotalsService.CheckoutTotals totalesNeutros(int envioCents) {
        CustomsValuationService.CustomsValuation customs = new CustomsValuationService.CustomsValuation("XX",
                TaxMode.DDP, 0, false, OverThresholdPolicy.SURCHARGE, 0, false, "", false);
        return new CheckoutTotalsService.CheckoutTotals(envioCents, 0, envioCents, 0, 0, customs);
    }

    private static PricingService.PricedAmount precio(String retail) {
        BigDecimal r = new BigDecimal(retail);
        return new PricingService.PricedAmount(r, r, r, "USD", "$", null, null, BigDecimal.ZERO, r, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, null);
    }

    private static ProductEntity producto(String precioCny) {
        ProductEntity p = ProductEntity.builder().status(ProductStatus.ACTIVE).slug("chaqueta").titleZh("蓝色外套").moq(1)
                .basePrice(new BigDecimal(precioCny)).images(new ArrayList<>()).translations(new ArrayList<>())
                .build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private void prepararCatalogo(ProductEntity producto, String retailUsd) {
        when(productRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(pricingService.priceFor(any(), any())).thenReturn(precio(retailUsd));
    }

    private static ProductImageEntity imagen(String sourceUrl) {
        return ProductImageEntity.builder().sourceUrl(sourceUrl).position(0).build();
    }

    private static ProductTranslationEntity traduccion(ProductEntity p, String lang, String title) {
        return ProductTranslationEntity.builder().product(p).language(lang).title(title).build();
    }

    private static UserEntity usuario(String email, String language) {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setLanguage(language);
        return u;
    }

    private static AddressInput direccion(String pais) {
        return new AddressInput("Ana Pérez", "555", "ana@x.com", "Calle 1", null, "Madrid", "M", "28001", pais);
    }

    private static CreateOrderRequest pedidoDe(UUID productId, int cantidad) {
        return new CreateOrderRequest("EXT", direccion("ES"), null,
                List.of(new OrderItemInput(productId, null, cantidad)), null);
    }

    private static MeCheckoutDtoIn checkout(UUID productId, int cantidad, String metodo) {
        MeCheckoutDtoIn req = new MeCheckoutDtoIn();
        req.setShippingAddressInline(direccion("ES"));
        MeCheckoutDtoIn.Item item = new MeCheckoutDtoIn.Item();
        item.setProductId(productId);
        item.setQuantity(cantidad);
        req.setItems(List.of(item));
        req.setPaymentMethod(metodo);
        return req;
    }

    private static Order pedidoDeAdmin(String numero, String externo, String destinatario, OrderStatus estado,
            Instant fecha) {
        return Order.builder().id(UUID.randomUUID()).orderNumber(numero).externalOrderId(externo)
                .shippingFullName(destinatario).status(estado).placedAt(fecha).items(new ArrayList<>()).build();
    }
}
