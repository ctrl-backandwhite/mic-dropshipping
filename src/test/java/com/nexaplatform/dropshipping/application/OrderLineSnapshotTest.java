package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemInput;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.service.CartService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.OperatorCommissionService;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.OrderUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderTrackingEventEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderTrackingEventRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.DutyParcel;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.application.service.FulfillmentRouter;
import com.nexaplatform.dropshipping.application.service.UnserviceableZoneService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Qué se congela en cada línea del pedido.
 *
 * <p>La línea guarda una FOTO del producto en el momento de la compra: título, imagen, SKU, precio de
 * venta y coste. Si mañana cambia el catálogo, el pedido vendido tiene que seguir diciendo lo que se
 * vendió —es lo que sostiene la factura, la reclamación y la guía de aduanas—.
 *
 * <p>La elección de imagen eran tres ternarios anidados y no había forma de leer en qué orden miraba;
 * al extraerla a un método con nombre quedó a la vista, y estos tests fijan ese orden.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderLineSnapshotTest {

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
    OperatorCommissionService operatorCommissionService;
    @Mock
    PromotionService promotionService;
    @Mock
    com.nexaplatform.dropshipping.application.service.SupplierPurchaseService supplierPurchaseService;
    @Mock
    OrderIndexer orderIndexer;
    @Mock
    OrderSearchService orderSearchService;
    @Mock
    OrderTrackingEventRepository trackingRepository;

    private final com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupService
            declarationGroups =
            mock(com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupService.class);

    private final UUID productId = UUID.randomUUID();

    private OrderUseCaseImpl useCase() {
        return new OrderUseCaseImpl(orderRepository, orderEntityRepository, productRepository, variantRepository,
                userRepository, shopConnectionRepository, userAddressRepository, webhooks, walletUseCase,
                notificationsPublisher,
                mock(com.nexaplatform.dropshipping.application.usecase.NotificationUseCase.class),
                pricingService, affiliateProgramService, stockService, paymentUseCase,
                orderEmailService, fulfillment, router, checkoutTotalsService, subvenciones(), new CustomsDutyLinesService(null), mock(UnserviceableZoneService.class), operatorCommissionService, promotionService, supplierPurchaseService, declarationGroups,
                trackingRepository, orderIndexer,
                orderSearchService, mock(CartService.class));
    }

    private ProductEntity product() {
        ProductEntity p = new ProductEntity();
        p.setId(productId);
        p.setSlug("reloj-hombre");
        p.setBasePrice(new BigDecimal("70.00"));
        p.setImages(new ArrayList<>());
        p.setStatus(ProductStatus.ACTIVE);
        when(productRepository.findById(productId)).thenReturn(Optional.of(p));
        return p;
    }

    private static ProductImageEntity image(String sourceUrl) {
        ProductImageEntity img = new ProductImageEntity();
        img.setSourceUrl(sourceUrl);
        return img;
    }

    private void pricedAt(String retailUsd) {
        PricingService.PricedAmount priced = new PricingService.PricedAmount(new BigDecimal("10.00"),
                retailUsd == null ? null : new BigDecimal(retailUsd), null, "USD", "$", null, null, null,
                null, null, null, null, null, null);
        when(pricingService.priceFor(any(), any())).thenReturn(priced);
    }

    private void happyTotals() {
        when(router.cotizar(anyString(), any(), anyList())).thenReturn(new ShippingQuote(true, "ES", 0, "YunExpress", "Standard", 7, 15, "EU"));
        when(affiliateProgramService.referralDiscountCents(any(), anyLong())).thenReturn(0L);
        CheckoutTotalsService.CheckoutTotals totals = mock(CheckoutTotalsService.CheckoutTotals.class);
        when(totals.blocked()).thenReturn(false);
        when(totals.shippingCents()).thenReturn(0);
        when(totals.taxCents()).thenReturn(0);
        when(totals.totalCents(anyInt())).thenAnswer(i -> i.getArgument(0));
        when(checkoutTotalsService.compute(any(), any(), anyInt(), anyInt(), anyList(), any())).thenReturn(totals);
        when(orderRepository.save(any())).thenAnswer(i -> {
            Order o = i.getArgument(0);
            o.setId(UUID.randomUUID());
            return o;
        });
    }

    private static AddressInput address() {
        return new AddressInput("Nombre Apellido", "+34600000000", "cliente@example.com", "Calle 1", null,
                "Madrid", "Madrid", "28001", "ES");
    }

    private OrderItem firstLine(UUID variantId, int qty) {
        happyTotals();
        CreateOrderRequest req = new CreateOrderRequest("EXT-1", address(), null,
                List.of(new OrderItemInput(productId, variantId, qty)), null);
        return useCase().createOrder(null, null, req).getItems().get(0);
    }


    /**
     * Sujeto bajo prueba, construido una sola vez por test. Se instancia en {@code @BeforeEach} y no
     * en la declaración del campo porque los dobles de prueba se inyectan DESPUÉS de crear la clase:
     * hacerlo antes lo dejaría con todas las dependencias a nulo. Tenerlo aparte permite además que la
     * lambda de cada aserción contenga una sola llamada capaz de lanzar, así que el fallo esperado sólo
     * puede venir del método bajo prueba.
     */
    private OrderUseCaseImpl subject;

    @BeforeEach
    void buildSubject() {
        subject = useCase();
    }

    // ---------------------------------------------------------------- imagen congelada

    @Test
    void laImagenEsLaDeLaVarianteYaEspejadaEnNuestroAlmacenamiento() {
        // La espejada es la que sobrevive: la de origen (1688) puede desaparecer o cambiar sin avisar.
        ProductEntity p = product();
        p.getImages().add(image("https://1688/producto.jpg"));
        pricedAt("100.00");
        UUID variantId = UUID.randomUUID();
        ProductVariantEntity v = new ProductVariantEntity();
        v.setId(variantId);
        v.setImageCdnUrl("https://cdn/variante-roja.jpg");
        v.setImageSourceUrl("https://1688/variante-roja.jpg");
        when(variantRepository.findById(variantId)).thenReturn(Optional.of(v));

        assertThat(firstLine(variantId, 1).getImageUrlSnapshot()).isEqualTo("https://cdn/variante-roja.jpg");
    }

    @Test
    void siLaVarianteNoEstaEspejadaSeGuardaLaDeOrigenAntesQueLaDelProducto() {
        ProductEntity p = product();
        p.getImages().add(image("https://1688/producto.jpg"));
        pricedAt("100.00");
        UUID variantId = UUID.randomUUID();
        ProductVariantEntity v = new ProductVariantEntity();
        v.setId(variantId);
        v.setImageSourceUrl("https://1688/variante-roja.jpg");
        when(variantRepository.findById(variantId)).thenReturn(Optional.of(v));

        assertThat(firstLine(variantId, 1).getImageUrlSnapshot()).isEqualTo("https://1688/variante-roja.jpg");
    }

    @Test
    void unaVarianteConLasDosImagenesEnBlancoCaeALaDelProducto() {
        // Cadena vacía no es "hay imagen": mostrarla dejaría un hueco en la factura y en el correo.
        ProductEntity p = product();
        p.getImages().add(image("https://1688/producto.jpg"));
        pricedAt("100.00");
        UUID variantId = UUID.randomUUID();
        ProductVariantEntity v = new ProductVariantEntity();
        v.setId(variantId);
        v.setImageCdnUrl("   ");
        v.setImageSourceUrl("");
        when(variantRepository.findById(variantId)).thenReturn(Optional.of(v));

        assertThat(firstLine(variantId, 1).getImageUrlSnapshot()).isEqualTo("https://1688/producto.jpg");
    }

    @Test
    void sinVarianteSeGuardaLaPrimeraImagenDelProducto() {
        ProductEntity p = product();
        p.getImages().add(image("https://1688/primera.jpg"));
        p.getImages().add(image("https://1688/segunda.jpg"));
        pricedAt("100.00");

        assertThat(firstLine(null, 1).getImageUrlSnapshot()).isEqualTo("https://1688/primera.jpg");
    }

    @Test
    void unProductoSinNingunaImagenNoInventaUna() {
        product();
        pricedAt("100.00");

        assertThat(firstLine(null, 1).getImageUrlSnapshot()).isNull();
    }

    // ---------------------------------------------------------------- importes congelados

    @Test
    void elPrecioDeLineaSeRedondeaAlCentimoMasCercanoIgualQueElCatalogo() {
        // Si el pedido redondeara distinto del catálogo, el cliente vería un precio y pagaría otro.
        product();
        pricedAt("14.905");

        OrderItem line = firstLine(null, 3);

        assertThat(line.getUnitPriceCents()).isEqualTo(1491);        // 14,905 -> 14,91
        assertThat(line.getLineTotalCents()).isEqualTo(4473);        // × 3
    }

    @Test
    void elCosteEnYuanesSeCongelaDesdeElPrecioDeLaVarianteCompradaYNoDelProducto() {
        // Es la base de la comisión del operador: tomar el del producto la calcularía sobre otro importe.
        ProductEntity p = product();
        p.setBasePrice(new BigDecimal("70.00"));
        pricedAt("100.00");
        UUID variantId = UUID.randomUUID();
        ProductVariantEntity v = new ProductVariantEntity();
        v.setId(variantId);
        v.setPrice(new BigDecimal("85.50"));
        v.setSku("SKU-ROJO-M");
        when(variantRepository.findById(variantId)).thenReturn(Optional.of(v));

        OrderItem line = firstLine(variantId, 1);

        assertThat(line.getCostCnyCents()).isEqualTo(8550L);
        assertThat(line.getSkuSnapshot()).isEqualTo("SKU-ROJO-M");
    }

    @Test
    void sinPrecioDeVarianteElCosteEnYuanesCaeAlDelProducto() {
        product();
        pricedAt("100.00");

        assertThat(firstLine(null, 1).getCostCnyCents()).isEqualTo(7000L);
    }

    // ---------------------------------------------------------------- lo que se rechaza

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 100_001, Integer.MAX_VALUE})
    void unaCantidadFueraDeRangoSeRechazaAntesDeCalcularImportes(int qty) {
        // El tope cierra el desbordamiento de enteros del cobro: sin él, una cantidad enorme daba un
        // total pequeño y el pedido se cobraba de menos.
        product();
        pricedAt("100.00");

        assertThatThrownBy(() -> firstLine(null, qty))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("entre 1 y 100000");
    }

    @Test
    void unProductoSinPrecioNoSePuedeVender() {
        product();
        pricedAt(null);

        assertThatThrownBy(() -> firstLine(null, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("has no price");
    }

    @Test
    void unCarritoVacioNoCreaPedido() {
        CreateOrderRequest sinLineas = new CreateOrderRequest("EXT-1", address(), null, List.of(), null);

        assertThatThrownBy(() -> subject.createOrder(null, null, sinLineas))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least one item");
    }

    @Test
    void elSeguimientoRecogeLosPasosQueMarcaElAdmin() {
        // El cliente ve DOS cosas: la barra de estado y el detalle del seguimiento. Marcar solo el
        // estado dejaba la barra en «Entregado» y el detalle parado en «Envío registrado».
        UUID id = UUID.randomUUID();
        Order o = Order.builder().id(id).orderNumber("NX-1").status(OrderStatus.FORWARDED)
                .shippingCountry("ES").build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(o));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        useCase().shipOrder(id);

        ArgumentCaptor<OrderTrackingEventEntity> ev = ArgumentCaptor.forClass(OrderTrackingEventEntity.class);
        verify(trackingRepository).save(ev.capture());
        assertThat(ev.getValue().getStatus()).isEqualTo(OrderStatus.SHIPPED.name());
        // ADMIN y no el carrier: el timeline debe distinguir lo anotado a mano de lo que informó YunExpress.
        assertThat(ev.getValue().getSource()).isEqualTo("ADMIN");
    }

    @Test
    void siFallaElApunteDelSeguimientoElPedidoAvanzaIgual() {
        // Perder una línea del seguimiento es un incordio; tumbar la transición sería peor.
        UUID id = UUID.randomUUID();
        Order o = Order.builder().id(id).orderNumber("NX-2").status(OrderStatus.FORWARDED)
                .shippingCountry("ES").build();
        when(orderRepository.findById(id)).thenReturn(Optional.of(o));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(trackingRepository.save(any())).thenThrow(new IllegalStateException("timeline caído"));

        assertThat(useCase().shipOrder(id).getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void seCongelaLaDescripcionConLaQueSeVaADeclarar() {
        // Si el grupo se aprueba DESPUÉS de cobrar, este pedido tiene que seguir contando por lo que se
        // declaró. Sin congelarlo, la vista previa contaría UNA línea (con la descripción del grupo) y el
        // despacho contaría DOS (con el título del producto): esos 3 EUR los pondría el comercio.
        product();
        pricedAt("100.00");
        when(declarationGroups.describeFor(any(), any())).thenReturn("Men's woven cotton trousers");

        assertThat(firstLine(null, 1).getDeclaredDescription())
                .isEqualTo("Men's woven cotton trousers");
    }

    @Test
    void seCongelaTambienElChinoConElQueSeVaADeclarar() {
        // La línea de la declaración lleva UN inglés y UN chino, y describen la misma mercancía. Si solo se
        // congelara el inglés, la guía saldría con el genérico aprobado en inglés y el título concreto del
        // primer artículo en chino: dos mercancías distintas en la misma línea, y el CName lo valida
        // YunExpress antes de emitir la guía.
        product();
        pricedAt("100.00");
        when(declarationGroups.describeFor(any(), any())).thenReturn("Men's woven cotton trousers");
        when(declarationGroups.describeZhFor(any(), any())).thenReturn("男式棉制机织长裤");

        assertThat(firstLine(null, 1).getDeclaredDescriptionZh()).isEqualTo("男式棉制机织长裤");
    }

    /** La bolsa de subvención del envío, real y con su suelo puesto (mide importes, no puede ser un cero). */
    private static com.nexaplatform.dropshipping.application.service.ProductSubsidyService subvenciones() {
        com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService divisa =
                org.mockito.Mockito.mock(com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService.class);
        org.mockito.Mockito.lenient().when(divisa.toUsd(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(new java.math.BigDecimal("5.85"));
        return new com.nexaplatform.dropshipping.application.service.ProductSubsidyService(divisa);
    }
}
