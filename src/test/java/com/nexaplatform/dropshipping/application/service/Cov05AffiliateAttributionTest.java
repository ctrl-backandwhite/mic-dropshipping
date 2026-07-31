package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateAttributionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateCommissionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateProgramConfigEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateReferralCodeEntity;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Atribución de clics (último clic, con deduplicación y ventana de caducidad), vínculo del visitante
 * anónimo con el cliente que se registra, y las transiciones de estado de la comisión que NO son el
 * pago: aprobación por vencimiento del periodo de devolución y resolución de la revisión antifraude.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov05AffiliateAttributionTest {

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

    @InjectMocks
    AffiliateProgramService service;

    private static final UUID AFFILIATE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CODE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String VISITOR = "visitor-token-abc";

    @BeforeEach
    void setUp() {
        when(configRepo.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(AffiliateProgramConfigEntity.builder()
                .defaultPercent(new BigDecimal("10.000")).attributionWindowDays(30).returnPeriodDays(14)
                .minPayoutCents(5000).currency("EUR").attributionModel("LAST_CLICK").clickDedupMinutes(30).build()));
        when(attrRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(codeRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(commissionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(affiliateRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private AffiliateEntity affiliate(String status) {
        UserEntity owner = new UserEntity();
        owner.setId(OWNER_ID);
        owner.setEmail("ana@example.com");
        owner.setRole(UserRole.USER);
        AffiliateEntity a = AffiliateEntity.builder().user(owner).code("ref-1").active("ACTIVE".equals(status))
                .status(status).build();
        a.setId(AFFILIATE_ID);
        return a;
    }

    private AffiliateReferralCodeEntity referralCode(AffiliateEntity owner, boolean active) {
        AffiliateReferralCodeEntity c = AffiliateReferralCodeEntity.builder().affiliate(owner).code("ref-1")
                .active(active).clicks(7).build();
        c.setId(CODE_ID);
        return c;
    }

    /* ===================== clic de referido ===================== */

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("sin código en la URL no se atribuye nada")
    void sinCodigoNoSeAtribuyeNada(String code) {
        assertThat(service.recordClick(code, VISITOR)).isEmpty();
        verify(attrRepo, never()).save(any());
    }

    @Test
    @DisplayName("un código que no existe no atribuye ni suma clics")
    void unCodigoDesconocidoNoAtribuye() {
        when(codeRepo.findByCodeIgnoreCase("nope")).thenReturn(Optional.empty());

        assertThat(service.recordClick("nope", VISITOR)).isEmpty();
        verify(attrRepo, never()).save(any());
        verify(codeRepo, never()).save(any());
    }

    @Test
    @DisplayName("un código desactivado deja de atribuir aunque siga circulando por ahí")
    void unCodigoDesactivadoNoAtribuye() {
        when(codeRepo.findByCodeIgnoreCase("ref-1")).thenReturn(Optional.of(referralCode(affiliate("ACTIVE"), false)));

        assertThat(service.recordClick("ref-1", VISITOR)).isEmpty();
        verify(attrRepo, never()).save(any());
    }

    @Test
    @DisplayName("un afiliado suspendido no atribuye clics")
    void unAfiliadoSuspendidoNoAtribuye() {
        when(codeRepo.findByCodeIgnoreCase("ref-1"))
                .thenReturn(Optional.of(referralCode(affiliate("SUSPENDED"), true)));

        assertThat(service.recordClick("ref-1", VISITOR)).isEmpty();
        verify(attrRepo, never()).save(any());
    }

    @Test
    @DisplayName("un clic válido suma al contador y abre la atribución con la ventana configurada")
    void unClicValidoAbreLaAtribucionConSuVentana() {
        AffiliateReferralCodeEntity code = referralCode(affiliate("ACTIVE"), true);
        when(codeRepo.findByCodeIgnoreCase("ref-1")).thenReturn(Optional.of(code));
        when(attrRepo.findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
        Instant before = Instant.now();

        Optional<AffiliateAttributionEntity> attr = service.recordClick("  ref-1  ", VISITOR);

        assertThat(attr).isPresent();
        assertThat(code.getClicks()).isEqualTo(8);
        assertThat(attr.get().getReferralCodeId()).isEqualTo(CODE_ID);
        assertThat(attr.get().getAffiliateId()).isEqualTo(AFFILIATE_ID);
        assertThat(attr.get().getVisitorToken()).isEqualTo(VISITOR);
        // La ventana de atribución es lo que decide si una compra futura genera comisión.
        assertThat(attr.get().getExpiresAt()).isAfter(before.plus(Duration.ofDays(29)));
    }

    @Test
    @DisplayName("recargar la página con el mismo enlace no infla los clics ni duplica la atribución")
    void losClicsRepetidosDelMismoVisitanteNoInflanElContador() {
        AffiliateReferralCodeEntity code = referralCode(affiliate("ACTIVE"), true);
        when(codeRepo.findByCodeIgnoreCase("ref-1")).thenReturn(Optional.of(code));
        AffiliateAttributionEntity recent = AffiliateAttributionEntity.builder().referralCodeId(CODE_ID)
                .affiliateId(AFFILIATE_ID).visitorToken(VISITOR).clickedAt(Instant.now().minus(Duration.ofMinutes(5)))
                .expiresAt(Instant.now().plus(Duration.ofDays(30))).build();
        when(attrRepo.findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(any(), any()))
                .thenReturn(Optional.of(recent));

        Optional<AffiliateAttributionEntity> attr = service.recordClick("ref-1", VISITOR);

        assertThat(attr).containsSame(recent);
        assertThat(code.getClicks()).isEqualTo(7);
        verify(attrRepo, never()).save(any());
        verify(codeRepo, never()).save(any());
    }

    @Test
    @DisplayName("el mismo visitante con el enlace de OTRO afiliado sí abre atribución nueva (último clic)")
    void elClicDeOtroCodigoDelMismoVisitanteSiAtribuye() {
        AffiliateReferralCodeEntity code = referralCode(affiliate("ACTIVE"), true);
        when(codeRepo.findByCodeIgnoreCase("ref-1")).thenReturn(Optional.of(code));
        AffiliateAttributionEntity fromAnotherCode = AffiliateAttributionEntity.builder()
                .referralCodeId(UUID.fromString("99999999-9999-9999-9999-999999999999")).affiliateId(UUID.randomUUID())
                .visitorToken(VISITOR).clickedAt(Instant.now().minus(Duration.ofMinutes(2)))
                .expiresAt(Instant.now().plus(Duration.ofDays(30))).build();
        when(attrRepo.findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(any(), any()))
                .thenReturn(Optional.of(fromAnotherCode));

        Optional<AffiliateAttributionEntity> attr = service.recordClick("ref-1", VISITOR);

        assertThat(attr).isPresent();
        assertThat(attr.get().getReferralCodeId()).isEqualTo(CODE_ID);
        assertThat(code.getClicks()).isEqualTo(8);
    }

    @Test
    @DisplayName("un clic anterior a la ventana de deduplicación cuenta como clic nuevo")
    void unClicFueraDeLaVentanaDeDeduplicacionCuentaComoNuevo() {
        AffiliateReferralCodeEntity code = referralCode(affiliate("ACTIVE"), true);
        when(codeRepo.findByCodeIgnoreCase("ref-1")).thenReturn(Optional.of(code));
        AffiliateAttributionEntity old = AffiliateAttributionEntity.builder().referralCodeId(CODE_ID)
                .affiliateId(AFFILIATE_ID).visitorToken(VISITOR).clickedAt(Instant.now().minus(Duration.ofHours(2)))
                .expiresAt(Instant.now().plus(Duration.ofDays(30))).build();
        when(attrRepo.findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(any(), any()))
                .thenReturn(Optional.of(old));

        service.recordClick("ref-1", VISITOR);

        assertThat(code.getClicks()).isEqualTo(8);
        verify(attrRepo).save(any());
    }

    @Test
    @DisplayName("sin cookie de visitante el clic se registra igual (no se puede deduplicar)")
    void sinCookieDeVisitanteElClicSeRegistraIgual() {
        AffiliateReferralCodeEntity code = referralCode(affiliate("ACTIVE"), true);
        when(codeRepo.findByCodeIgnoreCase("ref-1")).thenReturn(Optional.of(code));

        assertThat(service.recordClick("ref-1", null)).isPresent();
        assertThat(code.getClicks()).isEqualTo(8);
        verify(attrRepo, never()).findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(any(), any());
    }

    /* ===================== vínculo visitante → cliente ===================== */

    @Test
    @DisplayName("al registrarse, el visitante queda vinculado a la atribución viva")
    void alRegistrarseElVisitanteQuedaVinculado() {
        AffiliateAttributionEntity attr = AffiliateAttributionEntity.builder().referralCodeId(CODE_ID)
                .affiliateId(AFFILIATE_ID).visitorToken(VISITOR).clickedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofDays(30))).build();
        when(attrRepo.findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(any(), any()))
                .thenReturn(Optional.of(attr));
        when(affiliateRepo.findById(AFFILIATE_ID)).thenReturn(Optional.of(affiliate("ACTIVE")));

        service.bindVisitorToUser(VISITOR, CUSTOMER_ID);

        assertThat(attr.getReferredUserId()).isEqualTo(CUSTOMER_ID);
        verify(attrRepo).save(attr);
    }

    @Test
    @DisplayName("nadie se auto-refiere: el afiliado que pincha su propio enlace no se vincula")
    void nadieSeAutoRefiere() {
        AffiliateAttributionEntity attr = AffiliateAttributionEntity.builder().referralCodeId(CODE_ID)
                .affiliateId(AFFILIATE_ID).visitorToken(VISITOR).clickedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofDays(30))).build();
        when(attrRepo.findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(any(), any()))
                .thenReturn(Optional.of(attr));
        when(affiliateRepo.findById(AFFILIATE_ID)).thenReturn(Optional.of(affiliate("ACTIVE")));

        service.bindVisitorToUser(VISITOR, OWNER_ID);

        assertThat(attr.getReferredUserId()).isNull();
        verify(attrRepo, never()).save(any());
    }

    @Test
    @DisplayName("sin cookie o sin usuario no hay nada que vincular")
    void sinCookieOSinUsuarioNoHayNadaQueVincular() {
        service.bindVisitorToUser(null, CUSTOMER_ID);
        service.bindVisitorToUser("   ", CUSTOMER_ID);
        service.bindVisitorToUser(VISITOR, null);

        verify(attrRepo, never()).findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(any(), any());
    }

    @Test
    @DisplayName("si el afiliado de la atribución ya no existe, el cliente se vincula igual (no es auto-referido)")
    void unaAtribucionDeAfiliadoBorradoSiVinculaAlCliente() {
        AffiliateAttributionEntity attr = AffiliateAttributionEntity.builder().referralCodeId(CODE_ID)
                .affiliateId(AFFILIATE_ID).visitorToken(VISITOR).clickedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofDays(30))).build();
        when(attrRepo.findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(any(), any()))
                .thenReturn(Optional.of(attr));
        when(affiliateRepo.findById(AFFILIATE_ID)).thenReturn(Optional.empty());

        service.bindVisitorToUser(VISITOR, CUSTOMER_ID);

        // isSelf() devuelve false si el afiliado no existe → se vincula igualmente al cliente.
        assertThat(attr.getReferredUserId()).isEqualTo(CUSTOMER_ID);
    }

    /* ===================== aprobación por vencimiento ===================== */

    private AffiliateCommissionEntity commission(String status, Instant createdAt, long cents) {
        AffiliateCommissionEntity c = AffiliateCommissionEntity.builder().affiliateId(AFFILIATE_ID)
                .conversionId(UUID.randomUUID()).amountCents(cents).currency("EUR")
                .percentage(new BigDecimal("10.000")).status(status).build();
        c.setId(UUID.randomUUID());
        c.setCreatedAt(createdAt);
        return c;
    }

    @Test
    @DisplayName("solo se aprueban las comisiones que ya pasaron el periodo de devolución")
    void soloSeApruebanLasComisionesVencidas() {
        AffiliateCommissionEntity due = commission("PENDING", Instant.now().minus(Duration.ofDays(20)), 500);
        AffiliateCommissionEntity fresh = commission("PENDING", Instant.now().minus(Duration.ofDays(2)), 700);
        when(commissionRepo.findByStatus("PENDING")).thenReturn(List.of(due, fresh));
        when(affiliateRepo.findById(AFFILIATE_ID)).thenReturn(Optional.of(affiliate("ACTIVE")));
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(affiliate("ACTIVE").getUser()));

        int approved = service.approveDueCommissions();

        assertThat(approved).isEqualTo(1);
        assertThat(due.getStatus()).isEqualTo("APPROVED");
        assertThat(due.getApprovedAt()).isNotNull();
        // Si se aprobara antes de tiempo, se pagaría comisión de pedidos que aún pueden devolverse.
        assertThat(fresh.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("una comisión sin fecha de alta nunca se auto-aprueba")
    void unaComisionSinFechaDeAltaNoSeAutoAprueba() {
        AffiliateCommissionEntity noDate = commission("PENDING", null, 500);
        when(commissionRepo.findByStatus("PENDING")).thenReturn(List.of(noDate));

        assertThat(service.approveDueCommissions()).isZero();
        assertThat(noDate.getStatus()).isEqualTo("PENDING");
    }

    /* ===================== revisión antifraude ===================== */

    @Test
    @DisplayName("solo se puede resolver una comisión que esté en REVIEW")
    void soloSePuedeResolverUnaComisionEnRevision() {
        AffiliateCommissionEntity pending = commission("PENDING", Instant.now(), 500);
        when(commissionRepo.findById(pending.getId())).thenReturn(Optional.of(pending));
        UUID id = pending.getId();

        assertThatThrownBy(() -> service.resolveReview(id, true)).isInstanceOf(BusinessException.class);
        assertThat(pending.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("resolver una comisión que no existe falla")
    void resolverUnaComisionInexistenteFalla() {
        UUID id = UUID.randomUUID();
        when(commissionRepo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveReview(id, true)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("aprobar la revisión deja la comisión aprobada y sella la fecha")
    void aprobarLaRevisionSellaLaFecha() {
        AffiliateCommissionEntity review = commission("REVIEW", Instant.now(), 500);
        when(commissionRepo.findById(review.getId())).thenReturn(Optional.of(review));

        service.resolveReview(review.getId(), true);

        assertThat(review.getStatus()).isEqualTo("APPROVED");
        assertThat(review.getApprovedAt()).isNotNull();
        verify(commissionRepo).save(review);
    }

    @Test
    @DisplayName("rechazar la revisión resta lo devengado al afiliado")
    void rechazarLaRevisionRestaLoDevengado() {
        AffiliateCommissionEntity review = commission("REVIEW", Instant.now(), 500);
        when(commissionRepo.findById(review.getId())).thenReturn(Optional.of(review));
        AffiliateEntity a = affiliate("ACTIVE");
        a.setEarningsUsdCents(1200);
        when(affiliateRepo.findById(AFFILIATE_ID)).thenReturn(Optional.of(a));

        service.resolveReview(review.getId(), false);

        assertThat(review.getStatus()).isEqualTo("REJECTED");
        assertThat(review.getNote()).contains("anti-fraude");
        assertThat(a.getEarningsUsdCents()).isEqualTo(700);
    }

    @Test
    @DisplayName("lo devengado nunca queda en negativo al rechazar una revisión")
    void loDevengadoNuncaQuedaEnNegativo() {
        AffiliateCommissionEntity review = commission("REVIEW", Instant.now(), 5000);
        when(commissionRepo.findById(review.getId())).thenReturn(Optional.of(review));
        AffiliateEntity a = affiliate("ACTIVE");
        a.setEarningsUsdCents(100);
        when(affiliateRepo.findById(AFFILIATE_ID)).thenReturn(Optional.of(a));

        service.resolveReview(review.getId(), false);

        assertThat(a.getEarningsUsdCents()).isZero();
    }

    /* ===================== consultas ===================== */

    @Test
    @DisplayName("las consultas del panel delegan en el repositorio sin filtrar de más")
    void lasConsultasDelPanelDeleganEnElRepositorio() {
        AffiliateCommissionEntity c = commission("APPROVED", Instant.now(), 300);
        when(commissionRepo.findByAffiliateIdOrderByCreatedAtDesc(AFFILIATE_ID)).thenReturn(List.of(c));
        when(conversionRepo.findByAffiliateIdOrderByCreatedAtDesc(AFFILIATE_ID)).thenReturn(List.of());
        when(affiliateRepo.findAllWithUser()).thenReturn(List.of(affiliate("ACTIVE")));
        when(payoutRepo.findByAffiliateIdOrderByCreatedAtDesc(AFFILIATE_ID)).thenReturn(List.of());
        when(payoutRepo.findByStatusOrderByCreatedAtDesc("REQUESTED")).thenReturn(List.of());
        when(codeRepo.findByAffiliateIdOrderByCreatedAtAsc(AFFILIATE_ID)).thenReturn(List.of());

        assertThat(service.commissionsForAffiliate(AFFILIATE_ID)).containsExactly(c);
        assertThat(service.conversionsForAffiliate(AFFILIATE_ID)).isEmpty();
        assertThat(service.allAffiliates()).hasSize(1);
        assertThat(service.payoutsForAffiliate(AFFILIATE_ID)).isEmpty();
        assertThat(service.pendingPayouts()).isEmpty();
        assertThat(service.listCodes(AFFILIATE_ID)).isEmpty();
    }

    @Test
    @DisplayName("la comisión rechazada en revisión guarda su nota para la auditoría")
    void laComisionRechazadaGuardaNotaDeAuditoria() {
        AffiliateCommissionEntity review = commission("REVIEW", Instant.now(), 500);
        when(commissionRepo.findById(review.getId())).thenReturn(Optional.of(review));
        when(affiliateRepo.findById(AFFILIATE_ID)).thenReturn(Optional.of(affiliate("ACTIVE")));

        service.resolveReview(review.getId(), false);

        ArgumentCaptor<AffiliateCommissionEntity> saved = ArgumentCaptor.forClass(AffiliateCommissionEntity.class);
        verify(commissionRepo).save(saved.capture());
        assertThat(saved.getValue().getNote()).isNotBlank();
    }
}
