package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemInput;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.OperatorCommissionService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.OrderUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
    CheckoutTotalsService checkoutTotalsService;
    @Mock
    OperatorCommissionService operatorCommissionService;
    @Mock
    OrderIndexer orderIndexer;
    @Mock
    OrderSearchService orderSearchService;

    private final UUID productId = UUID.randomUUID();

    private OrderUseCaseImpl useCase() {
        return new OrderUseCaseImpl(orderRepository, orderEntityRepository, productRepository, variantRepository,
                userRepository, shopConnectionRepository, userAddressRepository, webhooks, walletUseCase,
                notificationsPublisher, pricingService, affiliateProgramService, stockService, paymentUseCase,
                orderEmailService, fulfillment, checkoutTotalsService, operatorCommissionService, orderIndexer,
                orderSearchService);
    }

    private ProductEntity product() {
        ProductEntity p = new ProductEntity();
        p.setId(productId);
        p.setSlug("reloj-hombre");
        p.setBasePrice(new BigDecimal("70.00"));
        p.setImages(new ArrayList<>());
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
        when(fulfillment.quote(anyString(), any())).thenReturn(new ShippingQuote(true, "ES", 0, "YunExpress", "Standard", 7, 15, "EU"));
        when(affiliateProgramService.referralDiscountCents(any(), anyLong())).thenReturn(0L);
        CheckoutTotalsService.CheckoutTotals totals = mock(CheckoutTotalsService.CheckoutTotals.class);
        when(totals.blocked()).thenReturn(false);
        when(totals.shippingCents()).thenReturn(0);
        when(totals.taxCents()).thenReturn(0);
        when(totals.totalCents(anyInt())).thenAnswer(i -> i.getArgument(0));
        when(checkoutTotalsService.compute(any(), any(), anyInt(), anyInt())).thenReturn(totals);
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
        assertThatThrownBy(() -> useCase().createOrder(null, null,
                new CreateOrderRequest("EXT-1", address(), null, List.of(), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least one item");
    }
}
