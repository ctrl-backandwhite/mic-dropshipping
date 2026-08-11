package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.AuditLogger;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.PartnerPlanSyncService;
import com.nexaplatform.dropshipping.application.service.StockService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.service.SubscriptionNotificationService;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Apertura de una RECARGA de saldo: qué importe se acaba cobrando, en qué divisa se liquida y qué se
 * rechaza antes de tocar la pasarela.
 *
 * <p>La regla que sostiene todo lo demás: el importe canónico en dólares lo calcula el BACKEND a partir de
 * lo que el usuario tecleó en su divisa activa. Si se aceptase el {@code amountUsdCents} que manda el
 * cliente, cualquiera pagaría 1 € y se acreditaría 10 000 $ de saldo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov09PaymentRechargeTest {

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

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID walletId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private PaymentUseCaseImpl subject;

    @BeforeEach
    void buildSubject() {
        subject = new PaymentUseCaseImpl(List.of(gateway), paymentRepository, paymentJpaRepositoryAdapter,
                userRepository, orderRepository, walletUseCase, auditLogger, partnerPlanSyncService,
                customerSubscriptionUseCase, subscriptionNotificationService, new ObjectMapper(), orderEmailService,
                currencyRateService, new OrderAmounts(currencyRateService), stockService, mock(SupplierPurchaseService.class), opsAlertService);
    }

    /** Usuario existente, wallet disponible y pasarela que abre el cobro sin incidencias. */
    private void happyPath() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(mock(UserEntity.class)));
        Wallet w = new Wallet();
        w.setId(walletId);
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
                "cs_test_1", null, null, null, null, null, Map.of()));
    }

    // ------------------------------------------------------------ el importe lo fija el backend

    @Test
    void elImporteEnDolaresSeCalculaDesdeLaDivisaDelUsuarioIgnorandoElQueMandaElCliente() {
        // El cliente declara 1 céntimo; lo que vale es la conversión de los 50 € que tecleó.
        happyPath();
        when(currencyRateService.toUsd(new BigDecimal("50.00"), "EUR")).thenReturn(new BigDecimal("55.00"));

        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, 1L, "EUR", new BigDecimal("50.00"), "k1",
                null);

        assertThat(p.getAmountUsdCents()).isEqualTo(5500L);
    }

    @Test
    void sinImporteEnDivisaSeAceptaElDeDolaresComoRespaldo() {
        happyPath();

        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, 2500L, "USD", null, "k1", null);

        assertThat(p.getAmountUsdCents()).isEqualTo(2500L);
        verify(currencyRateService, never()).toUsd(any(), anyString());
    }

    @Test
    void sinImporteDeNingunTipoNoSeAbreLaRecarga() {
        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.CARD, null, "USD", null, "k1", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Recharge amount is required");
    }

    @Test
    void unImporteEnDivisaCeroONegativoNoCuentaComoImporte() {
        // signum() <= 0 no es "importe válido": se cae al de dólares y, si tampoco hay, se rechaza.
        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.CARD, 0L, "EUR",
                BigDecimal.ZERO, "k1", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Recharge amount is required");
    }

    // ------------------------------------------------------------ límites

    @Test
    void porDebajoDeUnDolarNoSeAbreLaRecarga() {
        // Por debajo de 1,00 $ la comisión de la pasarela se come el ingreso.
        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.CARD, 99L, "USD", null, "k1", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Minimum recharge");

        verify(gateway, never()).initiate(any());
    }

    @Test
    void porEncimaDelMillonDeDolaresNoSeAbreLaRecarga() {
        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.CARD, 100_000_001L, "USD", null,
                "k1", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Maximum recharge");
    }

    @Test
    void elMillonDeDolaresExactoSiSeAdmite() {
        happyPath();

        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, 100_000_000L, "USD", null, "k1", null);

        assertThat(p.getAmountUsdCents()).isEqualTo(100_000_000L);
    }

    // ------------------------------------------------------------ idempotencia y usuario

    @Test
    void laMismaClaveDeIdempotenciaDevuelveLaRecargaYaAbiertaSinLlamarALaPasarela() {
        // Sin esto, un doble clic en "Recargar" abre dos cobros por el mismo dinero.
        Payment ya = new Payment();
        ya.setId(UUID.randomUUID());
        ya.setStatus(PaymentStatus.REQUIRES_ACTION);
        when(paymentRepository.findByIdempotencyKey("k1")).thenReturn(Optional.of(ya));

        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, 5000L, "USD", null, "k1", null);

        assertThat(p).isSameAs(ya);
        verify(gateway, never()).initiate(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void sinClaveDeIdempotenciaNiSeBuscaUnCobroPrevio() {
        happyPath();

        subject.initiateRecharge(userId, PaymentMethod.CARD, 5000L, "USD", null, null, null);

        verify(paymentRepository, never()).findByIdempotencyKey(any());
    }

    @Test
    void noSeAbreRecargaDeUnUsuarioQueNoExiste() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.CARD, 5000L, "USD", null, "k1", null))
                .isInstanceOf(NotFoundException.class);

        verify(walletUseCase, never()).getOrCreate(any());
    }

    // ------------------------------------------------------------ divisa de liquidación

    @Test
    void conTarjetaYEnEurosSeCobraExactamenteElImporteTecleadoSinReconvertirlo() {
        // Reconvertir 50 € → USD → EUR devuelve 49,99: el usuario vería un importe distinto del que pidió.
        happyPath();
        when(currencyRateService.toUsd(new BigDecimal("50.00"), "EUR")).thenReturn(new BigDecimal("55.00"));

        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, null, "EUR", new BigDecimal("50.00"), "k1",
                null);

        assertThat(p.getSettlementCurrency()).isEqualTo("EUR");
        assertThat(p.getSettlementAmount()).isEqualByComparingTo("50.00");
        verify(currencyRateService, never()).usdTo(any(), eq("EUR"));
    }

    @Test
    void enCualquierOtraDivisaLaTarjetaLiquidaEnDolares() {
        happyPath();
        when(currencyRateService.toUsd(new BigDecimal("40.00"), "GBP")).thenReturn(new BigDecimal("50.00"));

        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, null, "GBP", new BigDecimal("40.00"), "k1",
                null);

        assertThat(p.getSettlementCurrency()).isEqualTo("USD");
        assertThat(p.getSettlementAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void laCriptoLiquidaEnUsdtAunqueElUsuarioNavegueEnEuros() {
        happyPath();
        when(currencyRateService.toUsd(new BigDecimal("50.00"), "EUR")).thenReturn(new BigDecimal("55.00"));
        when(currencyRateService.usdTo(new BigDecimal("55.00"), "USDT")).thenReturn(new BigDecimal("55.00"));
        when(currencyRateService.decimalsOf(anyString())).thenReturn(2);

        Payment p = subject.initiateRecharge(userId, PaymentMethod.USDT, null, "EUR", new BigDecimal("50.00"), "k1",
                "TRON");

        assertThat(p.getSettlementCurrency()).isEqualTo("USDT");
        assertThat(p.getSettlementAmount()).isEqualByComparingTo("55.00");
    }

    @Test
    void sinDivisaDeclaradaSeAsumeDolar() {
        happyPath();

        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, 5000L, "  ", null, "k1", null);

        assertThat(p.getSettlementCurrency()).isEqualTo("USD");
        assertThat(p.getSettlementAmount()).isEqualByComparingTo("50.00");
    }

    // ------------------------------------------------------------ lo que se publica para el front

    @Test
    void elPagoQuedaEsperandoAccionDelUsuarioConLaReferenciaDeLaPasarela() {
        happyPath();

        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, 5000L, "USD", null, "k1", null);

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REQUIRES_ACTION);
        assertThat(p.getProvider()).isEqualTo("stripe");
        assertThat(p.getProviderRef()).isEqualTo("cs_test_1");
        assertThat(p.getWalletId()).isEqualTo(walletId);
    }

    @Test
    void elSecretoYLaUrlDeAprobacionViajanEnLosMetadatosDelPago() {
        // Sin estos dos datos el front no puede terminar el cobro fuera de la app.
        happyPath();
        when(gateway.initiate(any())).thenReturn(new PaymentGateway.InitiateResult(
                "cs_test_1", "secret_1", "https://paypal/approve", null, null, null, Map.of("foo", "bar")));

        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, 5000L, "USD", null, "k1", null);

        assertThat(p.getProviderResponse()).containsEntry("clientSecret", "secret_1")
                .containsEntry("approveUrl", "https://paypal/approve").containsEntry("foo", "bar");
    }

    @Test
    void laDireccionCriptoSeGuardaConVencimientoYSePublicaElMismoInstante() {
        // El pago y la metadata deben publicar EXACTAMENTE el mismo vencimiento, no dos "ahora + 30 min".
        happyPath();
        when(gateway.initiate(any())).thenReturn(new PaymentGateway.InitiateResult(
                "usdt_1", null, null, "T-addr", "TRON", "https://qr", null));

        Payment p = subject.initiateRecharge(userId, PaymentMethod.USDT, 5000L, "USD", null, "k1", "TRON");

        assertThat(p.getCryptoAddress()).isEqualTo("T-addr");
        assertThat(p.getCryptoChain()).isEqualTo("TRON");
        assertThat(p.getQrUrl()).isEqualTo("https://qr");
        assertThat(p.getCryptoExpiresAt()).isAfter(Instant.now());
        assertThat(p.getProviderResponse()).containsEntry("cryptoAddress", "T-addr")
                .containsEntry("expiresAt", p.getCryptoExpiresAt().toString());
    }

    @Test
    void sinDireccionCriptoNoSePublicaVencimientoAlguno() {
        happyPath();

        Payment p = subject.initiateRecharge(userId, PaymentMethod.CARD, 5000L, "USD", null, "k1", null);

        assertThat(p.getCryptoExpiresAt()).isNull();
        assertThat(p.getProviderResponse()).doesNotContainKey("expiresAt");
    }

    // ------------------------------------------------------------ fallos de la pasarela

    @Test
    void siLaPasarelaNoAbreLaRecargaSeAvisaAlResponsableYElErrorSePropaga() {
        // Un fallo aquí significa que ese método de pago ha dejado de cobrar: hay que enterarse ya.
        happyPath();
        when(gateway.initiate(any())).thenThrow(new IllegalStateException("stripe caído"));

        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.CARD, 5000L, "USD", null, "k1", null))
                .isInstanceOf(IllegalStateException.class);

        verify(opsAlertService).paymentFailed(eq("stripe"), eq("recarga de saldo"), anyString(), anyString());
    }

    @Test
    void sinPasarelaParaElMetodoDePagoLaRecargaSeRechaza() {
        happyPath();
        when(gateway.supports(any())).thenReturn(false);

        assertThatThrownBy(() -> subject.initiateRecharge(userId, PaymentMethod.USDT, 5000L, "USD", null, "k1", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No gateway for method");
    }
}
