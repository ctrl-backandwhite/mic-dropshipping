package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PartnerPlanSyncService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.service.SubscriptionNotificationService;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.service.CartService;
import com.nexaplatform.dropshipping.application.usecase.impl.PaymentUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.PaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A la pasarela se le pide EXACTAMENTE el importe que el cliente vio en el resumen.
 *
 * <p>El importe de cobro se recomponía sumando líneas, envío e impuestos, y ahí se olvidaba el
 * descuento de referido. Con el pedido que lo destapó en la certificación —44,67 € de producto, 4,46 €
 * de descuento, 8,41 € de envío y 10,21 € de impuestos— la pantalla anunciaba 58,83 € y Stripe pedía
 * 63,29 €: los 4,46 € del descuento, cobrados igualmente. El pedido guardaba bien su total; era la
 * cifra enviada a cobrar la que no lo respetaba.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Se cobra el importe que se le enseñó al cliente")
class OrderChargeMatchesShownTotalTest {

    /* Importes del pedido real, en céntimos USD. */
    private static final int SUBTOTAL = 5094;
    private static final int DESCUENTO = 509;
    private static final int ENVIO = 959;
    private static final int IMPUESTOS = 1164;
    private static final int TOTAL = SUBTOTAL - DESCUENTO + ENVIO + IMPUESTOS; // 6708

    private static final BigDecimal USD_A_EUR = new BigDecimal("0.87717");

    private final UUID orderId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentJpaRepositoryAdapter paymentJpaRepositoryAdapter;
    @Mock
    private UserRepository userRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private WalletUseCase walletUseCase;
    @Mock
    private AuditLogger auditLogger;
    @Mock
    private CurrencyRateService currencyRateService;

    private PaymentUseCaseImpl subject;

    @BeforeEach
    void setUp() {
        PaymentGateway tarjeta = mock(PaymentGateway.class);
        when(tarjeta.supports(PaymentMethod.CARD)).thenReturn(true);
        when(tarjeta.providerName()).thenReturn("stripe");
        when(tarjeta.initiate(any())).thenReturn(
                new PaymentGateway.InitiateResult("ref-1", null, null, null, null, null, Map.of()));

        subject = new PaymentUseCaseImpl(List.of(tarjeta), paymentRepository, paymentJpaRepositoryAdapter, userRepository,
                orderRepository, walletUseCase, org.mockito.Mockito.mock(com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService.class), auditLogger, mock(PartnerPlanSyncService.class),
                mock(CustomerSubscriptionUseCase.class), mock(SubscriptionNotificationService.class),
                new ObjectMapper(), mock(OrderEmailService.class), currencyRateService, new OrderAmounts(currencyRateService), mock(StockService.class),
                mock(SupplierPurchaseService.class), mock(OpsAlertService.class), mock(CartService.class));

        when(currencyRateService.usdTo(any(BigDecimal.class), anyString()))
                .thenAnswer(inv -> inv.<BigDecimal>getArgument(0).multiply(USD_A_EUR));
        when(currencyRateService.decimalsOf(anyString())).thenReturn(2);

        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setEmail("cliente@example.com");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        Wallet wallet = new Wallet();
        wallet.setId(UUID.randomUUID());
        wallet.setUserId(userId);
        when(walletUseCase.getOrCreate(userId)).thenReturn(wallet);

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(pedidoConDescuento()));
        when(paymentRepository.save(any())).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            // La pasarela lee la entidad ya persistida, así que el doble la devuelve como haría JPA.
            PaymentEntity managed = new PaymentEntity();
            managed.setId(p.getId());
            when(paymentJpaRepositoryAdapter.findById(p.getId())).thenReturn(Optional.of(managed));
            return p;
        });
    }

    /** El pedido de la certificación: 3 uds a 16,98 USD, con su descuento de referido. */
    private Order pedidoConDescuento() {
        Order o = new Order();
        o.setId(orderId);
        o.setOrderNumber("NX-CERT");
        o.setUserId(userId);
        o.setStatus(OrderStatus.PENDING);
        o.setCurrency("USD");
        o.setSubtotalCents(SUBTOTAL);
        o.setDiscountCents(DESCUENTO);
        o.setShippingCents(ENVIO);
        o.setTaxCents(IMPUESTOS);
        o.setTotalCents(TOTAL);
        o.setItems(List.of(OrderItem.builder().unitPriceCents(1698).quantity(3).build()));
        return o;
    }

    @Test
    void elImporteEnviadoALaPasarelaLlevaRestadoElDescuento() {
        subject.initiateOrderPayment(orderId, userId, PaymentMethod.CARD, "idem-1");

        // Se guarda dos veces: al crear el pago y tras enriquecerlo con la referencia de la pasarela.
        // Interesa el estado final, que es el importe con el que se cobra.
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, atLeastOnce()).save(captor.capture());
        BigDecimal cobrado = captor.getValue().getSettlementAmount().setScale(2, RoundingMode.HALF_UP);

        // 44,68 (la LÍNEA: 3 × 16,98 = 50,94 $ convertidos) − 4,46 + 8,41 + 10,21 = 58,84 €, que es la
        // cifra que el cliente leyó en el resumen del checkout: la vista previa hace exactamente esta
        // misma cuenta. Redondear el unitario y multiplicarlo (14,89 × 3 = 44,67) daba 58,83 €, un
        // céntimo de menos aquí y hasta un 1,45 % de MÁS con importes pequeños y cantidades grandes.
        // Sin restar el descuento salían 63,30 €.
        assertThat(cobrado).isEqualByComparingTo(new BigDecimal("58.84"));
    }

    @Test
    void elImporteCanonicoEnDolaresEsElTotalDelPedido() {
        subject.initiateOrderPayment(orderId, userId, PaymentMethod.CARD, "idem-2");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, atLeastOnce()).save(captor.capture());

        assertThat(captor.getValue().getAmountUsdCents()).isEqualTo(TOTAL);
    }

    @Test
    void sinDescuentoElCobroNoCambia() {
        Order sinDescuento = pedidoConDescuento();
        sinDescuento.setDiscountCents(0);
        sinDescuento.setTotalCents(SUBTOTAL + ENVIO + IMPUESTOS);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(sinDescuento));

        subject.initiateOrderPayment(orderId, userId, PaymentMethod.CARD, "idem-3");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, atLeastOnce()).save(captor.capture());
        // 44,68 + 8,41 + 10,21 = 63,30 €, la misma cuenta sin el descuento.
        assertThat(captor.getValue().getSettlementAmount().setScale(2, RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("63.30"));
    }
}
