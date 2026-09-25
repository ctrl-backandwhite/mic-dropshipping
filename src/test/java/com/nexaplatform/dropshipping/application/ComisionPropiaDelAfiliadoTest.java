package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateAttributionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateCommissionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El porcentaje de comisión propio de UN afiliado.
 *
 * <p>Regla del titular (25-sep-2026): una vez aprobado, el afiliado cobra el 10 % base de la venta, y
 * se le puede subir el porcentaje <b>solo a su cuenta y a su código, no al resto</b>.
 *
 * <p><b>Qué estaba roto.</b> La columna {@code commission_percent_override} existía, se leía al
 * calcular cada comisión y se pintaba en el listado del panel, pero <b>nadie podía escribirla</b>: no
 * había endpoint ni método. Valía nulo siempre, así que todos cobraban el porcentaje global y la única
 * palanca era la configuración general, que lo cambia para TODOS — justo lo contrario de la regla. Es
 * la peor forma de faltar una función: la columna en la base hace creer que está hecha.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComisionPropiaDelAfiliadoTest {

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

    private final UUID buenPrescriptor = UUID.randomUUID();
    private final UUID otroAfiliado = UUID.randomUUID();
    private final UUID comprador = UUID.randomUUID();
    private final UUID pedido = UUID.randomUUID();

    @BeforeEach
    void setup() {
        when(configRepo.findFirstByOrderByCreatedAtAsc())
                .thenReturn(Optional.of(AffiliateProgramConfigEntity.builder().defaultPercent(new BigDecimal("10.000"))
                        .attributionWindowDays(30).returnPeriodDays(14).minPayoutCents(5000).currency("EUR")
                        .attributionModel("LAST_CLICK").build()));
        when(conversionRepo.save(any())).thenAnswer(i -> {
            var c = i.<com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateConversionEntity>getArgument(
                    0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });
        when(commissionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(affiliateRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("aprobado y sin porcentaje propio, cobra el 10 % base del programa")
    void sinPorcentajePropioCobraElDiez() {
        AffiliateEntity a = afiliado(buenPrescriptor, null);
        preparaVenta(a);

        service.onOrderPlaced(pedido, comprador, 10_000, "EUR"); // 100,00 EUR de venta

        assertThat(comisionGuardada().getAmountCents()).isEqualTo(1_000); // 10,00 EUR
        assertThat(comisionGuardada().getPercentage()).isEqualByComparingTo("10.000");
    }

    @Test
    @DisplayName("con porcentaje propio, cobra el suyo y no el del programa")
    void conPorcentajePropioCobraElSuyo() {
        AffiliateEntity a = afiliado(buenPrescriptor, new BigDecimal("18.000"));
        preparaVenta(a);

        service.onOrderPlaced(pedido, comprador, 10_000, "EUR");

        assertThat(comisionGuardada().getAmountCents()).isEqualTo(1_800); // 18,00 EUR
        assertThat(comisionGuardada().getPercentage()).isEqualByComparingTo("18.000");
    }

    @Test
    @DisplayName("subirle el porcentaje a uno NO se lo sube a los demás")
    void subirleAUnoNoTocaALosDemas() {
        // El corazón de la regla. Sin esta prueba, la forma fácil de «subir el porcentaje» es tocar la
        // configuración general, que paga de más a TODO el programa sin que nadie lo note hasta la
        // liquidación del mes.
        AffiliateEntity subido = afiliado(buenPrescriptor, null);
        when(affiliateRepo.findById(buenPrescriptor)).thenReturn(Optional.of(subido));
        service.setCommissionPercent(buenPrescriptor, new BigDecimal("25.000"));
        assertThat(subido.getCommissionPercentOverride()).isEqualByComparingTo("25.000");

        // El otro afiliado, intacto: sigue cobrando el 10 % del programa.
        AffiliateEntity resto = afiliado(otroAfiliado, null);
        preparaVenta(resto);
        service.onOrderPlaced(pedido, comprador, 10_000, "EUR");

        assertThat(resto.getCommissionPercentOverride()).isNull();
        assertThat(comisionGuardada().getAmountCents()).isEqualTo(1_000);
        assertThat(comisionGuardada().getPercentage()).isEqualByComparingTo("10.000");
    }

    @Test
    @DisplayName("poner el porcentaje a nulo devuelve al afiliado al del programa")
    void aNuloVuelveAlDelPrograma() {
        AffiliateEntity a = afiliado(buenPrescriptor, new BigDecimal("25.000"));
        when(affiliateRepo.findById(buenPrescriptor)).thenReturn(Optional.of(a));

        service.setCommissionPercent(buenPrescriptor, null);

        assertThat(a.getCommissionPercentOverride()).isNull();
        preparaVenta(a);
        service.onOrderPlaced(pedido, comprador, 10_000, "EUR");
        assertThat(comisionGuardada().getAmountCents()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("cero por ciento se puede escribir: no es lo mismo que no tener porcentaje propio")
    void ceroNoEsNulo() {
        // «Nulo o cero» es la confusión que más veces ha costado dinero en este repositorio. Aquí
        // significa dejar de pagar a un afiliado sin expulsarlo, y tiene que poder escribirse.
        AffiliateEntity a = afiliado(buenPrescriptor, null);
        when(affiliateRepo.findById(buenPrescriptor)).thenReturn(Optional.of(a));

        service.setCommissionPercent(buenPrescriptor, BigDecimal.ZERO);

        assertThat(a.getCommissionPercentOverride()).isEqualByComparingTo("0");
        preparaVenta(a);
        service.onOrderPlaced(pedido, comprador, 10_000, "EUR");
        assertThat(comisionGuardada().getAmountCents()).isZero();
    }

    @Test
    @DisplayName("no se admite un porcentaje fuera de 0-100")
    void porcentajeFueraDeRango() {
        // Por encima de 100 la comisión superaría lo cobrado en la venta: cada pedido costaría dinero.
        AffiliateEntity a = afiliado(buenPrescriptor, null);
        when(affiliateRepo.findById(buenPrescriptor)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.setCommissionPercent(buenPrescriptor, new BigDecimal("120")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.setCommissionPercent(buenPrescriptor, new BigDecimal("-5")))
                .isInstanceOf(BusinessException.class);
        assertThat(a.getCommissionPercentOverride()).isNull();
    }

    @Test
    @DisplayName("sin aprobar no hay comisión, por mucho porcentaje propio que tenga")
    void sinAprobarNoHayComision() {
        // «Una vez aprobado»: el porcentaje propio no adelanta la aprobación.
        AffiliateEntity pendiente = afiliado(buenPrescriptor, new BigDecimal("25.000"));
        pendiente.setStatus("PENDING");
        pendiente.setActive(false);
        preparaVenta(pendiente);

        service.onOrderPlaced(pedido, comprador, 10_000, "EUR");

        verify(commissionRepo, org.mockito.Mockito.never()).save(any());
    }

    /* ---------------- apoyo ---------------- */

    private AffiliateEntity afiliado(UUID id, BigDecimal porcentajePropio) {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        AffiliateEntity a = AffiliateEntity.builder().user(u).code("ref-" + id).active(true).status("ACTIVE")
                .commissionPercentOverride(porcentajePropio).build();
        a.setId(id);
        return a;
    }

    /** Deja lista una venta atribuida a ese afiliado. */
    private void preparaVenta(AffiliateEntity a) {
        when(conversionRepo.existsByOrderId(pedido)).thenReturn(false);
        when(attrRepo.findTopByReferredUserIdAndExpiresAtAfterOrderByClickedAtDesc(eq(comprador), any()))
                .thenReturn(Optional.of(AffiliateAttributionEntity.builder().affiliateId(a.getId())
                        .referralCodeId(UUID.randomUUID()).referredUserId(comprador).clickedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(86_400)).build()));
        when(affiliateRepo.findById(a.getId())).thenReturn(Optional.of(a));
    }

    private AffiliateCommissionEntity comisionGuardada() {
        ArgumentCaptor<AffiliateCommissionEntity> cap = ArgumentCaptor.forClass(AffiliateCommissionEntity.class);
        verify(commissionRepo).save(cap.capture());
        return cap.getValue();
    }
}
