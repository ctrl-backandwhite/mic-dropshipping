package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.AddressInput;
import com.nexaplatform.dropshipping.api.dto.in.MeCheckoutDtoIn;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.application.service.OperatorCommissionService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PricingCountryHolder;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.service.UnserviceableZoneService;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.service.FulfillmentRouter;
import com.nexaplatform.dropshipping.application.usecase.impl.OrderUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.ShippingQuote;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * Con qué país se calcula el MARGEN del pedido, y con cuántos céntimos exactos se cobra.
 *
 * <p>Regla de negocio (decisión del dueño, 18-ago-2026): <b>el margen va por el país del COMPRADOR</b>
 * —el que manda el front en {@code X-Country}, que es su país de registro—, no por el destino del
 * paquete. Quien se registra en México ve y paga precio de México aunque mande el paquete a España; para
 * pagar precio español hay que registrarse en España. El IVA y el arancel son otra cosa y siguen yendo
 * por el país de ENTREGA, porque los fija la aduana del destino.
 *
 * <p>Esto se descubrió certificando en local: el mismo carrito enseñaba 23,19 $ y cobraba 22,66 $. La
 * ficha y la vista previa ya tarificaban por el país del comprador, pero el cobro lo pisaba con el país
 * de la dirección de envío, así que en cuanto los dos países se separaban el cargo dejaba de coincidir
 * con lo enseñado. Ninguna de las 4.448 pruebas lo vio porque todas cotizaban y cobraban con el mismo
 * país: el descuadre sólo aparece cuando se separan, que es justo lo que estos casos hacen.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PedidoPaisDelMargenTest {

    @Mock com.nexaplatform.dropshipping.domain.repository.OrderRepository orderRepository;
    @Mock com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository orderEntityRepository;
    @Mock ProductRepository productRepository;
    @Mock ProductVariantRepository variantRepository;
    @Mock UserRepository userRepository;
    @Mock ShopConnectionRepository shopConnectionRepository;
    @Mock UserAddressRepository userAddressRepository;
    @Mock WebhookDispatcherService webhooks;
    @Mock WalletUseCase walletUseCase;
    @Mock NotificationsPublisher notificationsPublisher;
    @Mock PricingService pricingService;
    @Mock AffiliateProgramService affiliateProgramService;
    @Mock StockService stockService;
    @Mock PaymentUseCase paymentUseCase;
    @Mock OrderEmailService orderEmailService;
    @Mock
    FulfillmentProvider fulfillment;
    @Mock
    FulfillmentRouter router;
    @Mock CheckoutTotalsService checkoutTotalsService;
    @Mock OperatorCommissionService operatorCommissionService;
    @Mock OrderIndexer orderIndexer;
    @Mock OrderSearchService orderSearchService;
    @Mock SupplierPurchaseService supplierPurchaseService;

    @org.mockito.Mock
    com.nexaplatform.dropshipping.application.service.CustomsDeclarationGroupService declarationGroups;
    @Mock UnserviceableZoneService unserviceableZoneService;
    @Spy CustomsDutyLinesService customsDutyLinesService = new CustomsDutyLinesService(null);

    @InjectMocks
    private OrderUseCaseImpl subject;

    private static final UUID USUARIO = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRODUCTO = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String DESTINO = "ES";

    /** Qué país veía el tarificador en cada consulta de precio del pedido. */
    private final List<String> paisesAlTarificar = new ArrayList<>();
    private Order pedidoGuardado;

    @BeforeEach
    void catalogoYCobroListos() {
        precioUnitario(new BigDecimal("10.00"));
        when(fulfillment.isSupported(anyString())).thenReturn(true);
        when(router.cotizar(anyString(), any(), anyList()))
                .thenReturn(new ShippingQuote(true, DESTINO, 0, "YunExpress", "Standard", 7, 15, "EU"));
        when(affiliateProgramService.referralDiscountCents(any(), anyLong())).thenReturn(0L);

        ProductEntity p = new ProductEntity();
        p.setId(PRODUCTO);
        p.setSlug("gafas");
        p.setBasePrice(new BigDecimal("9.00"));
        p.setImages(new ArrayList<>());
        p.setStatus(ProductStatus.ACTIVE);
        when(productRepository.findById(PRODUCTO)).thenReturn(Optional.of(p));

        CheckoutTotalsService.CheckoutTotals totals = mock(CheckoutTotalsService.CheckoutTotals.class);
        when(totals.blocked()).thenReturn(false);
        when(totals.shippingCents()).thenReturn(0);
        when(totals.taxCents()).thenReturn(0);
        when(totals.totalCents(anyInt())).thenAnswer(i -> i.getArgument(0));
        when(checkoutTotalsService.compute(any(), any(), anyInt(), anyInt(), anyList())).thenReturn(totals);

        when(orderRepository.save(any())).thenAnswer(i -> {
            Order o = i.getArgument(0);
            if (o.getId() == null) {
                o.setId(UUID.randomUUID());
            }
            pedidoGuardado = o;
            when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));
            return o;
        });
        UserEntity user = new UserEntity();
        user.setId(USUARIO);
        user.setEmail("comprador@example.com");
        user.setLanguage("es");
        when(userRepository.findById(USUARIO)).thenReturn(Optional.of(user));
    }

    @AfterEach
    void limpiaElHilo() {
        PricingCountryHolder.clear();
    }

    /** El tarificador anota el país que ve y devuelve el precio pedido. */
    private void precioUnitario(BigDecimal retailUsd) {
        when(pricingService.priceFor(any(), any())).thenAnswer(inv -> {
            paisesAlTarificar.add(PricingCountryHolder.get());
            return new PricingService.PricedAmount(new BigDecimal("1.34"), retailUsd, retailUsd,
                    "USD", "$", null, null, null, null, null, null, null, null, null);
        });
    }

    private void comprar(int unidades) {
        MeCheckoutDtoIn req = new MeCheckoutDtoIn();
        req.setPaymentMethod("WALLET");
        req.setShippingAddressInline(new AddressInput("Nombre Apellido", "+34600000000",
                "comprador@example.com", "Calle 1", null, "Madrid", "Madrid", "28001", DESTINO));
        MeCheckoutDtoIn.Item item = new MeCheckoutDtoIn.Item();
        item.setProductId(PRODUCTO);
        item.setQuantity(unidades);
        req.setItems(List.of(item));
        subject.checkout(USUARIO, req, null);
    }

    // ------------------------------------------------------------------ el país que manda

    @Test
    @DisplayName("el margen se cobra por el país del COMPRADOR, no por el destino del paquete")
    void elMargenVaPorElPaisDelComprador() {
        // Comprador registrado en México que manda el paquete a España.
        PricingCountryHolder.set("MX");

        comprar(2);

        assertThat(paisesAlTarificar)
                .as("con MX manda la regla global (120 %) y con ES la de la UE (104 %): 53 céntimos "
                        + "de diferencia en este carrito, y lo enseñado deja de ser lo cobrado")
                .containsOnly("MX");
    }

    @Test
    @DisplayName("sin país del comprador no se usa el del destino: se cae a la regla global")
    void sinPaisDelCompradorNoMandaElDestino() {
        PricingCountryHolder.clear();

        comprar(1);

        assertThat(paisesAlTarificar)
                .as("un invitado sin geolocalizar paga la tarifa global, no la del sitio al que envía")
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("al terminar, el hilo queda como estaba: el pedido no contamina la petición")
    void elHiloQuedaComoEstaba() {
        PricingCountryHolder.set("MX");

        comprar(1);

        assertThat(PricingCountryHolder.get())
                .as("los hilos vienen de un pool: dejarlo cambiado tarifica mal la SIGUIENTE petición")
                .isEqualTo("MX");
    }

    // ------------------------------------------------------------------ el céntimo exacto

    @ParameterizedTest(name = "{0} $/ud × {1} = {2} céntimos")
    @CsvSource({
            // Redondeo del céntimo: HALF_UP, el mismo que el catálogo y la vista previa. La mitad
            // exacta sube, que es lo que el cliente ha visto en la ficha.
            "5.475, 1, 548",
            "5.474, 1, 547",
            "5.005, 1, 501",
            "0.005, 1, 1",
            "9.995, 1, 1000",
            // Y el importe de línea multiplica el céntimo ya redondeado, no el decimal crudo: si se
            // multiplicara antes, 5,475 × 4 daría 2.190 y el cliente vería cuatro veces 5,48 = 21,92.
            "5.475, 4, 2192",
            "0.005, 100, 100",
            // Céntimo exacto sin decimales que redondear: no puede desviarse ni por arriba ni por abajo.
            "12.00, 3, 3600",
    })
    @DisplayName("el céntimo cobrado sale de redondear el precio al alza en la mitad exacta")
    void elCentimoCobradoEsElDeLaFicha(String retailUsd, int unidades, int lineaEsperadaCents) {
        PricingCountryHolder.set("MX");
        precioUnitario(new BigDecimal(retailUsd));

        comprar(unidades);

        assertThat(pedidoGuardado.getItems().get(0).getLineTotalCents())
                .as("lo que se cobra por la línea tiene que ser lo que el cliente sumó en pantalla")
                .isEqualTo(lineaEsperadaCents);
    }
}
