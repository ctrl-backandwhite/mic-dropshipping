package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.model.WalletTransaction;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateCommissionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliatePayoutEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateProgramConfigEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateAttributionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateCommissionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateConversionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliatePayoutRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateProgramConfigRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AffiliateReferralCodeRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.NotificationJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Invariantes de los pagos a afiliados: por aquí SALE dinero de la plataforma.
 *
 * <p>Un payout paga las comisiones aprobadas de un afiliado. Con método WALLET se abona en su monedero;
 * con BANK o PAYPAL el admin ya pagó por fuera y aquí sólo se registra la referencia —si además se
 * tocara el monedero, se estaría pagando dos veces—. Lo que se fija: no se paga dos veces, no se paga
 * lo rechazado, no se paga sin comisiones que liquidar, y el pago externo no mueve saldo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AffiliatePayoutInvariantsTest {

    @Mock
    AffiliateJpaRepositoryAdapter affiliateRepo;
    @Mock
    AffiliateReferralCodeRepository codeRepo;
    @Mock
    AffiliateAttributionRepository attrRepo;
    @Mock
    AffiliateConversionRepository conversionRepo;
    @Mock
    AffiliateCommissionRepository commissionRepo;
    @Mock
    AffiliateProgramConfigRepository configRepo;
    @Mock
    AffiliatePayoutRepository payoutRepo;
    @Mock
    UserRepository userRepository;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    NotificationJpaRepositoryAdapter notificationRepo;
    @Mock
    NotificationsPublisher notificationsPublisher;
    @Mock
    WalletUseCase walletUseCase;
    @Mock
    AffiliateIndexer affiliateIndexer;

    private final UUID userId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID affiliateId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private final UUID payoutId = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private AffiliateProgramService service() {
        return new AffiliateProgramService(affiliateRepo, codeRepo, attrRepo, conversionRepo, commissionRepo,
                configRepo, payoutRepo, userRepository, passwordEncoder, notificationRepo, notificationsPublisher,
                walletUseCase, affiliateIndexer);
    }

    private AffiliateEntity affiliate() {
        UserEntity user = new UserEntity();
        user.setId(userId);
        AffiliateEntity a = new AffiliateEntity();
        a.setId(affiliateId);
        a.setUser(user);
        a.setStatus("ACTIVE");
        a.setEarningsUsdCents(0L);
        a.setPayoutUsdCents(0L);
        a.setReferralsCount(0);
        when(affiliateRepo.findById(affiliateId)).thenReturn(Optional.of(a));
        when(affiliateRepo.findByUser_Id(userId)).thenReturn(Optional.of(a));
        when(affiliateRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        return a;
    }

    private AffiliatePayoutEntity payout(String status, String method) {
        AffiliatePayoutEntity p = new AffiliatePayoutEntity();
        p.setId(payoutId);
        p.setAffiliateId(affiliateId);
        p.setStatus(status);
        p.setMethod(method);
        p.setAmountCents(0L);
        when(payoutRepo.findById(payoutId)).thenReturn(Optional.of(p));
        when(payoutRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        return p;
    }

    private void approvedCommissions(long... amounts) {
        List<AffiliateCommissionEntity> list = java.util.Arrays.stream(amounts).mapToObj(a -> {
            AffiliateCommissionEntity c = new AffiliateCommissionEntity();
            c.setId(UUID.randomUUID());
            c.setAffiliateId(affiliateId);
            c.setAmountCents(a);
            c.setStatus("APPROVED");
            return c;
        }).toList();
        when(commissionRepo.findByAffiliateIdAndStatus(affiliateId, "APPROVED")).thenReturn(list);
        when(commissionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private void config(long minPayoutCents) {
        when(configRepo.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(
                AffiliateProgramConfigEntity.builder().defaultPercent(new BigDecimal("10.000"))
                        .attributionWindowDays(30).returnPeriodDays(14).minPayoutCents(minPayoutCents)
                        .currency("EUR").attributionModel("LAST_CLICK").build()));
    }


    /**
     * Sujeto bajo prueba, construido una sola vez por test. Se instancia en {@code @BeforeEach} y no
     * en la declaración del campo porque los dobles de prueba se inyectan DESPUÉS de crear la clase:
     * hacerlo antes lo dejaría con todas las dependencias a nulo. Tenerlo aparte permite además que la
     * lambda de cada aserción contenga una sola llamada capaz de lanzar, así que el fallo esperado sólo
     * puede venir del método bajo prueba.
     */
    private AffiliateProgramService subject;

    @BeforeEach
    void buildSubject() {
        subject = service();
    }

    // ---------------------------------------------------------------- no pagar dos veces

    @Test
    void unPagoYaEjecutadoNoSeVuelveAPagar() {
        affiliate();
        AffiliatePayoutEntity p = payout("PAID", "WALLET");

        assertThat(service().approvePayout(payoutId).getStatus()).isEqualTo("PAID");

        verify(walletUseCase, never()).adminTopup(any(), anyLong(), anyString(), anyString());
        assertThat(p.getAmountCents()).isZero();
    }

    @Test
    void unPagoRechazadoNoSePuedeEjecutar() {
        affiliate();
        payout("REJECTED", "WALLET");

        assertThatThrownBy(() -> subject.approvePayout(payoutId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rechazado");

        verify(walletUseCase, never()).adminTopup(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void unPagoYaEjecutadoNoSePuedeRechazarDespues() {
        payout("PAID", "WALLET");

        assertThatThrownBy(() -> subject.rejectPayout(payoutId, "me equivoqué"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ya se ejecutó");
    }

    @Test
    void sinComisionesAprobadasElPagoSeRechazaEnVezDeAbonarCero() {
        affiliate();
        payout("APPROVED", "WALLET");
        approvedCommissions();   // ninguna

        AffiliatePayoutEntity result = service().approvePayout(payoutId);

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        assertThat(result.getNote()).contains("Sin comisiones aprobadas");
        verify(walletUseCase, never()).adminTopup(any(), anyLong(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- monedero vs pago externo

    @Test
    void elPagoPorMonederoAbonaElTotalYMarcaLasComisionesComoPagadas() {
        AffiliateEntity a = affiliate();
        payout("APPROVED", "WALLET");
        approvedCommissions(3_000L, 2_500L);
        WalletTransaction tx = WalletTransaction.builder().id(UUID.randomUUID()).build();
        when(walletUseCase.adminTopup(any(), anyLong(), anyString(), anyString())).thenReturn(tx);

        AffiliatePayoutEntity result = service().approvePayout(payoutId);

        verify(walletUseCase).adminTopup(userId, 5_500L, "Affiliate commission payout",
                "affiliate-payout-" + payoutId);
        assertThat(result.getStatus()).isEqualTo("PAID");
        assertThat(result.getAmountCents()).isEqualTo(5_500L);
        assertThat(result.getCommissionCount()).isEqualTo(2);
        assertThat(a.getPayoutUsdCents()).isEqualTo(5_500L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BANK", "PAYPAL"})
    void elPagoExternoSoloSeRegistraYNoTocaElMonedero(String method) {
        // El admin ya transfirió por fuera. Si además se abonara el monedero, el afiliado cobraría dos
        // veces la misma comisión.
        affiliate();
        payout("APPROVED", method);
        approvedCommissions(4_000L);
        UUID adminId = UUID.randomUUID();

        AffiliatePayoutEntity result = service().approvePayout(payoutId, adminId, "TRF-2026-0001");

        verify(walletUseCase, never()).adminTopup(any(), anyLong(), anyString(), anyString());
        assertThat(result.getStatus()).isEqualTo("PAID");
        assertThat(result.getPaidReference()).isEqualTo("TRF-2026-0001");
        assertThat(result.getPaidBy()).isEqualTo(adminId);
        assertThat(result.getWalletTxId()).isNull();
    }

    @Test
    void elPagoDejaCadaComisionEnlazadaConSuLiquidacion() {
        // Sin el enlace no se puede responder "¿en qué pago se me abonó esta comisión?".
        affiliate();
        payout("APPROVED", "WALLET");
        approvedCommissions(1_000L);
        when(walletUseCase.adminTopup(any(), anyLong(), anyString(), anyString()))
                .thenReturn(WalletTransaction.builder().id(UUID.randomUUID()).build());

        service().approvePayout(payoutId);

        verify(commissionRepo).save(org.mockito.ArgumentMatchers.argThat(c ->
                "PAID".equals(c.getStatus()) && payoutId.equals(c.getPayoutId()) && c.getPaidAt() != null));
    }

    // ---------------------------------------------------------------- solicitud del afiliado

    @Test
    void noSePuedeSolicitarUnPagoPorDebajoDelMinimo() {
        affiliate();
        config(5_000L);
        approvedCommissions(1_000L);

        assertThatThrownBy(() -> subject.requestPayout(userId, "WALLET"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("mínimo");

        verify(payoutRepo, never()).save(any());
    }

    @Test
    void noSeAcumulanDosSolicitudesPendientesALaVez() {
        // Dos solicitudes vivas sobre las mismas comisiones aprobadas podrían liquidarse las dos.
        affiliate();
        config(1_000L);
        approvedCommissions(5_000L);
        when(payoutRepo.existsByAffiliateIdAndStatus(affiliateId, "REQUESTED")).thenReturn(true);

        assertThatThrownBy(() -> subject.requestPayout(userId, "WALLET"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("pendiente");
    }

    @Test
    void noSeSolicitaTransferenciaSinDatosBancarios() {
        affiliate();   // sin IBAN ni titular
        config(1_000L);

        assertThatThrownBy(() -> subject.requestPayout(userId, "BANK"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("datos bancarios");
    }

    @Test
    void noSeSolicitaPagoPorPaypalSinCorreoConfigurado() {
        affiliate();
        config(1_000L);

        assertThatThrownBy(() -> subject.requestPayout(userId, "PAYPAL"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PayPal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CRYPTO", "cheque", "efectivo"})
    void unMetodoDeCobroDesconocidoSeRechaza(String method) {
        assertThatThrownBy(() -> subject.requestPayout(userId, method))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no válido");
    }

    @Test
    void laSolicitudPorTransferenciaCongelaElDestinoEnEseMomento() {
        // Si el afiliado cambia su IBAN después de solicitar, el pago debe irse al que había cuando se
        // pidió: si no, un cambio de cuenta desvía un pago ya aprobado.
        AffiliateEntity a = affiliate();
        a.setBankIban("ES9121000418450200051332");
        a.setBankHolder("Nombre Apellido");
        a.setBankBic("CAIXESBBXXX");
        config(1_000L);
        approvedCommissions(5_000L);
        when(payoutRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        AffiliatePayoutEntity solicitud = service().requestPayout(userId, "BANK");

        assertThat(solicitud.getDestIban()).isEqualTo("ES9121000418450200051332");
        assertThat(solicitud.getDestHolder()).isEqualTo("Nombre Apellido");
        assertThat(solicitud.getStatus()).isEqualTo("REQUESTED");
        assertThat(solicitud.getAmountCents()).isEqualTo(5_000L);
    }

    @Test
    void solicitarPagoSinSerAfiliadoNoCreaNada() {
        when(affiliateRepo.findByUser_Id(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> subject.requestPayout(userId, "WALLET"))
                .isInstanceOf(NotFoundException.class);

        verify(payoutRepo, never()).save(any());
    }

    // ---------------------------------------------------------------- liquidación directa del admin

    @Test
    void laLiquidacionDirectaRespetaElMinimoSalvoQueSeFuerce() {
        affiliate();
        config(5_000L);
        approvedCommissions(1_000L);

        assertThat(service().payoutApproved(affiliateId, false)).isZero();
        verify(payoutRepo, never()).save(any());
    }

    @Test
    void laLiquidacionDirectaForzadaPagaAunqueNoLlegueAlMinimo() {
        affiliate();
        config(5_000L);
        approvedCommissions(1_000L);
        when(payoutRepo.save(any())).thenAnswer(i -> {
            AffiliatePayoutEntity p = i.getArgument(0);
            if (p.getId() == null) {
                p.setId(payoutId);
            }
            when(payoutRepo.findById(payoutId)).thenReturn(Optional.of(p));
            return p;
        });
        when(walletUseCase.adminTopup(any(), anyLong(), anyString(), anyString()))
                .thenReturn(WalletTransaction.builder().id(UUID.randomUUID()).build());

        assertThat(service().payoutApproved(affiliateId, true)).isEqualTo(1_000L);
    }

    @Test
    void sinNadaAprobadoLaLiquidacionDirectaNoCreaPago() {
        affiliate();
        config(0L);
        approvedCommissions();

        assertThat(service().payoutApproved(affiliateId, true)).isZero();
        verify(payoutRepo, never()).save(any());
    }
}
