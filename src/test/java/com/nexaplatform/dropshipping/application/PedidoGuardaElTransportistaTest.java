package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.application.service.FulfillmentRouter;
import com.nexaplatform.dropshipping.application.service.OperatorCommissionService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.ProductSubsidyService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.service.UnserviceableZoneService;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.OrderUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.ShippingOption;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El pedido recuerda POR QUIÉN se cobró el envío.
 *
 * <p>Con un solo transportista bastaba con guardar el código de la línea. Con dos no: un {@code FZZXR}
 * de YunExpress y un {@code 1868922929754472449} de SEGUNDO no se distinguen mirándolos, y despachar por el
 * que no era significa cobrar un porte y pagar otro.
 *
 * <p>Y hay un fallo más silencioso todavía. Al cobrar, la forma de envío elegida se <b>revalida contra
 * una cotización nueva</b> —el código llega del navegador y aceptarlo a ciegas dejaría pagar el precio
 * de un canal más barato—. Si esa cotización solo le pregunta a un transportista, la opción de SEGUNDO que el
 * cliente eligió no aparece, el resolutor la da por inválida y cae a la más barata de YunExpress: el
 * cliente elige una cosa y se le cobra y se le envía otra. Es la misma familia de fallo que el descuadre
 * de esta mañana, por otra puerta, así que aquí se fija que el cobro cotiza con el mismo enrutador que
 * la vista previa.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PedidoGuardaElTransportistaTest {

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
    ProductSubsidyService productSubsidyService;
    @Mock
    OperatorCommissionService operatorCommissionService;
    @Mock
    OrderIndexer orderIndexer;
    @Mock
    OrderSearchService orderSearchService;
    @Mock
    SupplierPurchaseService supplierPurchaseService;

    @org.mockito.Mock
    com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupService declarationGroups;
    @Mock
    UnserviceableZoneService unserviceableZoneService;
    @Spy
    CustomsDutyLinesService customsDutyLinesService = new CustomsDutyLinesService(null);
    /** Sin escalera de cantidades: estas pruebas miden otra cosa y un tramo la falsearía. */
    @Mock
    ProductPriceTierRepository priceTierRepository;

    @InjectMocks
    private OrderUseCaseImpl subject;

    private static final UUID USUARIO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRODUCTO = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** Las dos opciones que ve el cliente: la de SEGUNDO es más barata que la de YunExpress. */
    private static final ShippingOption DE_SEGUNDO = new ShippingOption("1868922929754472449", "YunExpress Ordinary",
            767, 8, 15, "SEGUNDO");
    private static final ShippingOption DE_YUNEXPRESS = new ShippingOption("FZZXR", "Apparel line", 785, 5, 8,
            "YUNEXPRESS");

    private Order guardado;

    @BeforeEach
    void carritoListoParaPagar() {
        // Sin bolsas asignadas: estas pruebas no miden la subvención, y un mock sin preparar devolvería
        // null donde el contrato dice que siempre hay dos importes.
        org.mockito.Mockito.lenient().when(productSubsidyService.bagsFor(org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.nexaplatform.dropshipping.application.service.ProductSubsidyService.Bags.NONE);
        ProductEntity p = new ProductEntity();
        p.setId(PRODUCTO);
        p.setSlug("camiseta");
        p.setBasePrice(new BigDecimal("9.00"));
        p.setHsCode("610910");
        p.setImages(new ArrayList<>());
        p.setStatus(ProductStatus.ACTIVE);
        when(productRepository.findById(PRODUCTO)).thenReturn(Optional.of(p));
        when(pricingService.priceFor(any(), any(), anyInt(), any()))
                .thenReturn(new PricingService.PricedAmount(new BigDecimal("1.34"), new BigDecimal("10.00"),
                        new BigDecimal("10.00"), "USD", "$", null, null, null, null, null, null, null, null, null));

        when(fulfillment.isSupported(anyString())).thenReturn(true);
        when(router.cotizar(anyString(), any(), anyList())).thenReturn(new ShippingQuote(true, "ES", 767,
                "Transportista", "Standard", 8, 15, "EU", List.of(DE_SEGUNDO, DE_YUNEXPRESS)));
        when(affiliateProgramService.referralDiscountCents(any(), anyLong())).thenReturn(0L);

        CheckoutTotalsService.CheckoutTotals totals = mock(CheckoutTotalsService.CheckoutTotals.class);
        when(totals.blocked()).thenReturn(false);
        when(totals.shippingCents()).thenReturn(0);
        when(totals.taxCents()).thenReturn(0);
        when(totals.totalCents(anyInt())).thenAnswer(i -> i.getArgument(0));
        when(checkoutTotalsService.compute(any(), any(), anyInt(), anyInt(), anyList(), any())).thenReturn(totals);

        when(orderRepository.save(any())).thenAnswer(i -> {
            Order o = i.getArgument(0);
            if (o.getId() == null) {
                o.setId(UUID.randomUUID());
            }
            guardado = o;
            when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));
            return o;
        });
        UserEntity user = new UserEntity();
        user.setId(USUARIO);
        user.setEmail("comprador@example.com");
        user.setLanguage("es");
        when(userRepository.findById(USUARIO)).thenReturn(Optional.of(user));
    }

    private void comprarEligiendo(String codigoDeEnvio) {
        MeCheckoutDtoIn req = new MeCheckoutDtoIn();
        req.setPaymentMethod("WALLET");
        req.setShippingOptionCode(codigoDeEnvio);
        req.setShippingAddressInline(new AddressInput("Nombre Apellido", "+34600000000", "comprador@example.com",
                "Calle 1", null, "Madrid", "Madrid", "28001", "ES"));
        MeCheckoutDtoIn.Item item = new MeCheckoutDtoIn.Item();
        item.setProductId(PRODUCTO);
        item.setQuantity(1);
        req.setItems(List.of(item));
        subject.checkout(USUARIO, req, null);
    }

    @Test
    @DisplayName("elegir una opción de SEGUNDO deja el pedido marcado como de SEGUNDO")
    void guardaElTransportistaDeLaOpcionElegida() {
        comprarEligiendo(DE_SEGUNDO.code());

        assertThat(guardado.getShippingCarrier()).as("sin esto, al despachar no se sabe a quién pedirle la guía")
                .isEqualTo("SEGUNDO");
        assertThat(guardado.getShippingChannelCode()).isEqualTo(DE_SEGUNDO.code());
    }

    @Test
    @DisplayName("elegir una opción de YunExpress deja el pedido marcado como de YunExpress")
    void tambienGuardaElOtroTransportista() {
        comprarEligiendo(DE_YUNEXPRESS.code());

        assertThat(guardado.getShippingCarrier()).isEqualTo("YUNEXPRESS");
        assertThat(guardado.getShippingChannelCode()).isEqualTo("FZZXR");
    }

    @Test
    @DisplayName("el cobro cotiza con el enrutador, o la opción del otro transportista no existiría")
    void elCobroPreguntaALosDosTransportistas() {
        comprarEligiendo(DE_SEGUNDO.code());

        // Si el cobro cotizara solo contra un transportista, la opción de SEGUNDO no aparecería en la
        // cotización de revalidación, se daría por inválida y se caería a la más barata de YunExpress:
        // el cliente elige una cosa y se le cobra y se le envía otra.
        assertThat(guardado.getShippingCarrier()).isEqualTo("SEGUNDO");
        assertThat(guardado.getShippingChannelCode()).isEqualTo(DE_SEGUNDO.code());
    }

    @Test
    @DisplayName("un código inventado no cuela: se cae a la más barata, con su transportista")
    void unCodigoInventadoCaeALaMasBarata() {
        comprarEligiendo("CANAL-QUE-NO-EXISTE");

        assertThat(guardado.getShippingChannelCode())
                .as("el código llega del navegador: aceptarlo sin revalidar dejaría elegir precio")
                .isEqualTo(DE_SEGUNDO.code());
        assertThat(guardado.getShippingCarrier()).isEqualTo("SEGUNDO");
    }

    @Test
    @DisplayName("sin elegir nada se cobra la más barata y queda anotado quién la lleva")
    void sinElegirSeCobraLaMasBarata() {
        comprarEligiendo(null);

        assertThat(guardado.getShippingChannelCode()).isEqualTo(DE_SEGUNDO.code());
        assertThat(guardado.getShippingCarrier()).isEqualTo("SEGUNDO");
    }
}
