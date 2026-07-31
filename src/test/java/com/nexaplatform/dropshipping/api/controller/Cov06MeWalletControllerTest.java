package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.MeWalletRechargeDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletPaymentStatusDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletRechargeDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeWalletTxDtoOut;
import com.nexaplatform.dropshipping.api.mapper.MeWalletDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import com.nexaplatform.dropshipping.application.usecase.RechargeOptions;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Monedero del usuario autenticado. Aquí se comprueba lo que el controlador SÍ decide: de quién es el
 * monedero (siempre el del token, nunca un id del cuerpo), el tope de la paginación y el importe de
 * cobro que se le enseña al usuario antes de mandarlo a la pasarela.
 */
@ExtendWith(MockitoExtension.class)
class Cov06MeWalletControllerTest {

    @Mock
    WalletUseCase walletUseCase;
    @Mock
    PaymentUseCase paymentUseCase;
    @Mock
    MeWalletDtoMapper meWalletDtoMapper;
    @Mock
    CurrencyRateService currencyRateService;

    @InjectMocks
    MeWalletController controller;

    private UUID userId;
    private Authentication auth;

    @BeforeEach
    void preparar() {
        userId = UUID.randomUUID();
        auth = mock(Authentication.class);
        lenient().when(auth.getName()).thenReturn(userId.toString());
    }

    /** El monedero servido es SIEMPRE el del sujeto del token; el cliente no elige de quién es. */
    @Test
    void elMonederoDevueltoEsElDelUsuarioAutenticado() {
        Wallet wallet = Wallet.builder().balanceUsdCents(1000).build();
        MeWalletDtoOut dto = new MeWalletDtoOut();
        when(walletUseCase.getMyWallet(userId)).thenReturn(wallet);
        when(meWalletDtoMapper.toWalletDtoOut(wallet)).thenReturn(dto);

        ResponseEntity<MeWalletDtoOut> resp = controller.wallet(auth);

        assertThat(resp.getBody()).isSameAs(dto);
    }

    /**
     * El tamaño de página se acota a 100 aunque el cliente pida más: sin el tope, un {@code size} enorme
     * cargaría el histórico entero del monedero en memoria.
     */
    @Test
    void elTamanoDePaginaSeAcotaACien() {
        when(walletUseCase.getMyTransactions(userId, 0, 100)).thenReturn(List.of());
        when(meWalletDtoMapper.toTxDtoOutList(List.of())).thenReturn(List.of());
        when(walletUseCase.countMyTransactions(userId)).thenReturn(3L);

        ResponseEntity<PageResponse<MeWalletTxDtoOut>> resp = controller.transactions(auth, 0, 5000);

        assertThat(resp.getBody().size()).isEqualTo(100);
        assertThat(resp.getBody().totalElements()).isEqualTo(3);
        verify(walletUseCase).getMyTransactions(userId, 0, 100);
    }

    @Test
    void unTamanoDePaginaPorDebajoDelTopeSeRespeta() {
        when(walletUseCase.getMyTransactions(userId, 2, 20)).thenReturn(List.of());
        when(meWalletDtoMapper.toTxDtoOutList(List.of())).thenReturn(List.of());

        ResponseEntity<PageResponse<MeWalletTxDtoOut>> resp = controller.transactions(auth, 2, 20);

        assertThat(resp.getBody().page()).isEqualTo(2);
        assertThat(resp.getBody().size()).isEqualTo(20);
    }

    /**
     * Lo que se cobra de verdad va en la divisa de liquidación (EUR/USD), no en la que el usuario tecleó.
     * El backend la formatea y el front solo la pinta: si esto no se rellenara, el usuario aprobaría en la
     * pasarela un importe distinto del que vio.
     */
    @Test
    void laRecargaDevuelveElImporteDeCobroFormateadoPorElBackend() {
        Payment p = Payment.builder().id(UUID.randomUUID()).settlementCurrency("EUR")
                .settlementAmount(new BigDecimal("23.10")).build();
        MeWalletRechargeDtoOut dto = new MeWalletRechargeDtoOut();
        when(paymentUseCase.initiateRecharge(eq(userId), eq(PaymentMethod.CARD), any(), eq("MXN"),
                eq(new BigDecimal("400")), eq("idem-1"), isNull())).thenReturn(p);
        when(meWalletDtoMapper.toRechargeDtoOut(p)).thenReturn(dto);
        when(currencyRateService.formatDisplay(new BigDecimal("23.10"), "EUR")).thenReturn("23,10 €");

        MeWalletRechargeDtoIn req = MeWalletRechargeDtoIn.builder().method("CARD").currencyDisplay("MXN")
                .amountDisplay(new BigDecimal("400")).build();
        MeWalletRechargeDtoOut body = controller.recharge(auth, req, "idem-1").getBody();

        assertThat(body.getChargeCurrency()).isEqualTo("EUR");
        assertThat(body.getChargeFormatted()).isEqualTo("23,10 €");
    }

    /** Sin importe de liquidación (cripto pendiente de fijar) NO se inventa un texto de cobro. */
    @Test
    void sinImporteDeLiquidacionNoSeFormateaNingunCobro() {
        Payment p = Payment.builder().id(UUID.randomUUID()).build();
        MeWalletRechargeDtoOut dto = new MeWalletRechargeDtoOut();
        when(paymentUseCase.initiateRecharge(any(), any(), any(), any(), any(), any(), any())).thenReturn(p);
        when(meWalletDtoMapper.toRechargeDtoOut(p)).thenReturn(dto);

        MeWalletRechargeDtoIn req = MeWalletRechargeDtoIn.builder().method("USDT").cryptoChain("TRC20").build();
        MeWalletRechargeDtoOut body = controller.recharge(auth, req, null).getBody();

        assertThat(body.getChargeCurrency()).isNull();
        assertThat(body.getChargeFormatted()).isNull();
        verifyNoInteractions(currencyRateService);
    }

    /** Tras confirmar la recarga se devuelve el SALDO ya actualizado: el usuario debe verlo al volver. */
    @Test
    void alConfirmarLaRecargaSeDevuelveElSaldoActualizado() {
        UUID paymentId = UUID.randomUUID();
        Payment p = Payment.builder().id(paymentId).status(PaymentStatus.SUCCEEDED).build();
        when(paymentUseCase.confirmRecharge(userId, paymentId)).thenReturn(p);
        when(walletUseCase.getOrCreate(userId)).thenReturn(Wallet.builder().balanceUsdCents(5000).build());

        MeWalletPaymentStatusDtoOut body = controller.confirmRecharge(auth, paymentId).getBody();

        assertThat(body.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(body.getBalanceUsdCents()).isEqualTo(5000);
        assertThat(body.getPaymentId()).isEqualTo(paymentId);
    }

    /** La captura de PayPal la dispara el propio proveedor: no hay usuario y por eso no se devuelve saldo. */
    @Test
    void laCapturaDePayPalNoDevuelveSaldo() {
        UUID paymentId = UUID.randomUUID();
        when(paymentUseCase.capturePayPal(paymentId))
                .thenReturn(Payment.builder().id(paymentId).status(PaymentStatus.SUCCEEDED).build());

        MeWalletPaymentStatusDtoOut body = controller.capturePayPal(paymentId).getBody();

        assertThat(body.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(body.getBalanceUsdCents()).isNull();
    }

    /** La confirmación simulada también es del usuario del token, nunca de un id recibido por parámetro. */
    @Test
    void laConfirmacionSimuladaVaContraElUsuarioDelToken() {
        UUID paymentId = UUID.randomUUID();
        when(paymentUseCase.confirmMockRecharge(userId, paymentId))
                .thenReturn(Payment.builder().id(paymentId).status(PaymentStatus.SUCCEEDED).build());
        when(walletUseCase.getOrCreate(userId)).thenReturn(Wallet.builder().balanceUsdCents(100).build());

        MeWalletPaymentStatusDtoOut body = controller.confirmMock(auth, paymentId).getBody();

        assertThat(body.getBalanceUsdCents()).isEqualTo(100);
        verify(paymentUseCase).confirmMockRecharge(userId, paymentId);
    }

    @Test
    void lasOpcionesDeRecargaSeSirvenTalCualLasCalculaElCasoDeUso() {
        RechargeOptions opciones = new RechargeOptions("EUR", "€", List.of());
        when(paymentUseCase.rechargeOptions("EUR")).thenReturn(opciones);

        assertThat(controller.rechargeOptions("EUR").getBody()).isSameAs(opciones);
    }
}
