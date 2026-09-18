package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.CartService;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PartnerPlanSyncService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.SubscriptionNotificationService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.PaymentUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.PaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.payment.PaymentGateway;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Arranque de una recarga de saldo: por aquí ENTRA dinero al monedero.
 *
 * <p>El importe canónico en dólares lo calcula el BACKEND a partir de lo que el usuario tecleó en su
 * divisa, no se acepta el que mande el cliente: si se aceptara, bastaría con manipular la petición para
 * recargar mil dólares pagando uno. El saldo siempre se acredita en dólares aunque el cobro se liquide
 * en otra moneda.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RechargeInitiationTest {

    @Mock
    PaymentRepository paymentRepository;
    @Mock
    PaymentJpaRepositoryAdapter paymentJpaRepositoryAdapter;
    @Mock
    UserRepository userRepository;
    @Mock
    OrderRepository orderRepository;
    @Mock
    WalletUseCase walletUseCase;
    @Mock
    AuditLogger auditLogger;
    @Mock
    PartnerPlanSyncService partnerPlanSyncService;
    @Mock
    CustomerSubscriptionUseCase customerSubscriptionUseCase;
    @Mock
    SubscriptionNotificationService subscriptionNotificationService;
    @Mock
    OrderEmailService orderEmailService;
    @Mock
    CurrencyRateService currencyRateService;
    @Mock
    StockService stockService;
    @Mock
    OpsAlertService opsAlertService;
    @Mock
    PaymentGateway gateway;

    private PaymentUseCaseImpl subject;

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void buildSubject() {
        subject = new PaymentUseCaseImpl(List.of(gateway), paymentRepository, paymentJpaRepositoryAdapter,
                userRepository, orderRepository, walletUseCase, org.mockito.Mockito.mock(com.nexaplatform.dropshipping.infrastructure.integration.stripe.StripeService.class), auditLogger, partnerPlanSyncService,
                customerSubscriptionUseCase, subscriptionNotificationService, new ObjectMapper(), orderEmailService,
                currencyRateService, new OrderAmounts(currencyRateService), stockService, mock(SupplierPurchaseService.class), opsAlertService, mock(CartService.class));

        when(userRepository.findById(userId)).thenReturn(Optional.of(mock(UserEntity.class)));
        Wallet w = new Wallet();
        w.setId(UUID.randomUUID());
        when(walletUseCase.getOrCreate(userId)).thenReturn(w);
        when(paymentRepository.save(any())).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID());
            }
            return p;
        });
        when(paymentJpaRepositoryAdapter.findById(any())).thenReturn(Optional.of(mock(PaymentEntity.class)));
        when(gateway.supports(any())).thenReturn(true);
        when(gateway.providerName()).thenReturn("stripe");
        when(gateway.initiate(any())).thenReturn(new PaymentGateway.InitiateResult(
                "cs_test_1", null, "https://pay/1", null, null, null, Map.of()));
        // 1 EUR = 1,10 USD para que las cuentas del test se lean solas.
        when(currencyRateService.toUsd(any(BigDecimal.class), anyString())).thenAnswer(i -> {
            BigDecimal amount = i.getArgument(0);
            return "EUR".equalsIgnoreCase(i.getArgument(1)) ? amount.multiply(new BigDecimal("1.10")) : amount;
        });
    }

    // ---------------------------------------------------------------- importe canónico

    @Test
    void elImporteEnDolaresLoCalculaElBackendDesdeLoQueTecleoElUsuario() {
        // 25,00 € × 1,10 = 27,50 $. Aceptar el importe en dólares que manda el cliente permitiría
        // recargar mil pagando uno.
        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, 999_999L, "EUR",
                new BigDecimal("25.00"), "k1", null);

        assertThat(p.getAmountUsdCents()).isEqualTo(2750L);
    }

    @Test
    void sinImporteEnDivisaSeAceptaElDeDolaresPorCompatibilidad() {
        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, 5000L, null, null, "k1", null);

        assertThat(p.getAmountUsdCents()).isEqualTo(5000L);
    }

    @Test
    void sinNingunImporteLaRecargaSeRechaza() {
        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.CARD, null, "EUR", null, "k1", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("amount is required");

        verify(gateway, never()).initiate(any());
    }

    @Test
    void unImporteAceroONegativoNoCuentaComoImporte() {
        // El importe negativo se construye FUERA de la lambda: dentro habría dos llamadas capaces de
        // lanzar y un formato mal escrito daría el test por bueno sin ejercitar la validación.
        BigDecimal negativo = new BigDecimal("-5");

        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.CARD, 0L, "EUR",
                BigDecimal.ZERO, "k1", null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.CARD, -100L, "EUR",
                negativo, "k1", null)).isInstanceOf(BusinessException.class);
    }

    // ---------------------------------------------------------------- límites

    @Test
    void noSeRecargaPorDebajoDelMinimoDeLaPasarela() {
        // Por debajo de 1,00 $ la comisión se come el importe; el intento sólo genera ruido.
        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.CARD, 99L, null, null, "k1", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Minimum recharge");
    }

    @Test
    void noSeRecargaPorEncimaDelTope() {
        // Un tope alto pero finito: sin él, un error de tecleo o una petición manipulada abriría un cobro
        // desmesurado en la pasarela.
        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.CARD, 100_000_001L, null, null,
                "k1", null)).isInstanceOf(BusinessException.class).hasMessageContaining("Maximum recharge");
    }

    @Test
    void justoEnElMinimoSiSeAdmite() {
        assertThat(subject.initiateRecharge(userId, PaymentMethod.CARD, 100L, null, null, "k1", null)
                .getAmountUsdCents()).isEqualTo(100L);
    }

    // ---------------------------------------------------------------- divisa de liquidación

    @ParameterizedTest
    @CsvSource({
            "CARD,   EUR, EUR",   // Stripe cobra en euros si el usuario navega en euros
            "CARD,   USD, USD",
            "CARD,   GBP, USD",   // cualquier otra divisa se liquida en dólares
            "PAYPAL, EUR, USD",   // PayPal liquida siempre en dólares
            "USDT,   EUR, USDT"   // cripto liquida en USDT sea cual sea la divisa mostrada
    })
    void laDivisaDeCobroDependeDelMetodoYDeLaQueUseElComprador(String method, String display, String expected) {
        Payment p = subject.initiateRecharge(userId, PaymentMethod.valueOf(method), null, display,
                new BigDecimal("25.00"), "k1", null);

        assertThat(p.getSettlementCurrency()).isEqualTo(expected);
    }

    @Test
    void elSaldoSeAcreditaSiempreEnDolaresAunqueSeCobreEnEuros() {
        // El monedero es canónico en dólares; mezclar divisas en el saldo haría imposible cuadrarlo.
        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, null, "EUR",
                new BigDecimal("25.00"), "k1", null);

        assertThat(p.getSettlementCurrency()).isEqualTo("EUR");
        assertThat(p.getAmountUsdCents()).isEqualTo(2750L);
    }

    // ---------------------------------------------------------------- idempotencia y estado

    @Test
    void reintentarConLaMismaClaveDevuelveLaRecargaYaAbiertaSinLlamarALaPasarela() {
        // Sin esto, un doble clic en "Recargar" abre dos cobros por el mismo importe.
        Payment ya = new Payment();
        ya.setId(UUID.randomUUID());
        // De quién es el cobro importa: la reutilización por clave es solo para quien lo abrió.
        ya.setUserId(userId);
        ya.setStatus(PaymentStatus.REQUIRES_ACTION);
        when(paymentRepository.findByIdempotencyKey("k1")).thenReturn(Optional.of(ya));

        assertThat(subject.initiateRecharge(userId, PaymentMethod.CARD, 5000L, null, null, "k1", null))
                .isSameAs(ya);

        verify(gateway, never()).initiate(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void laRecargaQuedaEsperandoAccionDelUsuarioConLaReferenciaDeLaPasarela() {
        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, 5000L, null, null, "k1", null);

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(p.getProviderRef()).isEqualTo("cs_test_1");
        assertThat(p.getProvider()).isEqualTo("stripe");
    }

    @Test
    void unaRecargaDeUnUsuarioQueNoExisteNoAbreCobro() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.CARD, 5000L, null, null, "k1", null))
                .isInstanceOf(NotFoundException.class);

        verify(gateway, never()).initiate(any());
    }

    @Test
    void siLaPasarelaFallaSeAvisaAlResponsableYNoSeSilencia() {
        when(gateway.initiate(any())).thenThrow(new IllegalStateException("stripe unreachable"));

        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.CARD, 5000L, null, null, "k1", null))
                .isInstanceOf(IllegalStateException.class);

        verify(opsAlertService).paymentFailed(anyString(), anyString(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- cripto

    @Test
    void unaRecargaEnCriptoLlevaDireccionCadenaYCaducidad() {
        // Sin caducidad, un pago que llega días después a la misma dirección se acreditaría a destiempo.
        when(gateway.initiate(any())).thenReturn(new PaymentGateway.InitiateResult(
                "charge_1", null, null, "TX1abc...", "TRC20", "https://qr/1", Map.of()));

        Payment p = subject.initiateRecharge(userId, PaymentMethod.USDT, 5000L, null, null, "k1", "TRC20");

        assertThat(p.getCryptoAddress()).isEqualTo("TX1abc...");
        assertThat(p.getCryptoChain()).isEqualTo("TRC20");
        assertThat(p.getQrUrl()).isEqualTo("https://qr/1");
        assertThat(p.getCryptoExpiresAt()).isNotNull();
    }
}
