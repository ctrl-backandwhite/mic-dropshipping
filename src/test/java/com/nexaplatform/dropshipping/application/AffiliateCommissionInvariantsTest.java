package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateAttributionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateCommissionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateConversionEntity;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cuándo se genera comisión de afiliado y cuánta.
 *
 * <p>La comisión es dinero que la plataforma se compromete a pagar, así que generarla de más —o donde no
 * toca— es una pérdida directa. Las reglas que se fijan: hace falta una atribución viva, nadie cobra por
 * su propio código, un afiliado dado de baja no devenga, el mismo pedido no genera dos comisiones, y
 * cancelar o reembolsar el pedido deshace lo devengado (salvo que ya se hubiera pagado).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AffiliateCommissionInvariantsTest {

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

    private final UUID buyerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID affiliateUserId = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private final UUID affiliateId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private final UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private AffiliateProgramService service() {
        return new AffiliateProgramService(affiliateRepo, codeRepo, attrRepo, conversionRepo, commissionRepo,
                configRepo, payoutRepo, userRepository, passwordEncoder, notificationRepo, notificationsPublisher,
                walletUseCase, affiliateIndexer);
    }

    /** Afiliado ACTIVO, con la cuenta de OTRA persona distinta del comprador. */
    private AffiliateEntity affiliate(String status) {
        UserEntity user = new UserEntity();
        user.setId(affiliateUserId);
        AffiliateEntity a = new AffiliateEntity();
        a.setId(affiliateId);
        a.setUser(user);
        a.setStatus(status);
        a.setEarningsUsdCents(0L);
        a.setPayoutUsdCents(0L);
        a.setReferralsCount(0);
        when(affiliateRepo.findById(affiliateId)).thenReturn(Optional.of(a));
        when(affiliateRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        return a;
    }

    private void liveAttribution() {
        AffiliateAttributionEntity attr = new AffiliateAttributionEntity();
        attr.setId(UUID.randomUUID());
        attr.setAffiliateId(affiliateId);
        attr.setReferredUserId(buyerId);
        attr.setExpiresAt(Instant.now().plusSeconds(3600));
        when(attrRepo.findTopByReferredUserIdAndExpiresAtAfterOrderByClickedAtDesc(eq(buyerId), any()))
                .thenReturn(Optional.of(attr));
    }

    private void noAttribution() {
        when(attrRepo.findTopByReferredUserIdAndExpiresAtAfterOrderByClickedAtDesc(any(), any()))
                .thenReturn(Optional.empty());
    }

    private void defaults() {
        when(configRepo.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(
                AffiliateProgramConfigEntity.builder().defaultPercent(new BigDecimal("10.000"))
                        .attributionWindowDays(30).returnPeriodDays(14).minPayoutCents(5000)
                        .currency("EUR").attributionModel("LAST_CLICK").build()));
        when(conversionRepo.existsByOrderId(orderId)).thenReturn(false);
        when(conversionRepo.save(any())).thenAnswer(i -> {
            AffiliateConversionEntity c = i.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });
        when(commissionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(commissionRepo.findByAffiliateIdOrderByCreatedAtDesc(any())).thenReturn(java.util.List.of());
    }

    // ---------------------------------------------------------------- cuándo NO hay comisión

    @Test
    void sinAtribucionVivaNoSeDevengaComision() {
        defaults();
        noAttribution();

        service().onOrderPlaced(orderId, buyerId, 10_000L, "EUR");

        verify(commissionRepo, never()).save(any());
        verify(conversionRepo, never()).save(any());
    }

    @Test
    void nadieCobraComisionPorUsarSuPropioCodigo() {
        // El auto-referido es el fraude más barato: comprar con tu propio código y cobrarte la comisión.
        defaults();
        AffiliateAttributionEntity attr = new AffiliateAttributionEntity();
        attr.setAffiliateId(affiliateId);
        attr.setReferredUserId(buyerId);
        attr.setExpiresAt(Instant.now().plusSeconds(3600));
        when(attrRepo.findTopByReferredUserIdAndExpiresAtAfterOrderByClickedAtDesc(eq(buyerId), any()))
                .thenReturn(Optional.of(attr));
        UserEntity self = new UserEntity();
        self.setId(buyerId);                      // el afiliado ES el comprador
        AffiliateEntity a = new AffiliateEntity();
        a.setId(affiliateId);
        a.setUser(self);
        a.setStatus("ACTIVE");
        when(affiliateRepo.findById(affiliateId)).thenReturn(Optional.of(a));

        service().onOrderPlaced(orderId, buyerId, 10_000L, "EUR");
        long descuento = service().referralDiscountCents(buyerId, 10_000L);

        verify(commissionRepo, never()).save(any());
        assertThat(descuento).isZero();           // tampoco se lleva el descuento de comprador
    }

    @Test
    void unAfiliadoDadoDeBajaNoDevengaNiDaDescuento() {
        defaults();
        liveAttribution();
        affiliate("SUSPENDED");

        service().onOrderPlaced(orderId, buyerId, 10_000L, "EUR");

        verify(commissionRepo, never()).save(any());
        assertThat(service().referralDiscountCents(buyerId, 10_000L)).isZero();
    }

    @Test
    void elMismoPedidoNoGeneraComisionDosVeces() {
        // El pedido puede reprocesarse (reintento, webhook duplicado); la comisión se paga una sola vez.
        defaults();
        liveAttribution();
        affiliate("ACTIVE");
        when(conversionRepo.existsByOrderId(orderId)).thenReturn(true);

        service().onOrderPlaced(orderId, buyerId, 10_000L, "EUR");

        verify(commissionRepo, never()).save(any());
    }

    @Test
    void unPedidoSinImporteNoDevengaNada() {
        defaults();
        liveAttribution();
        affiliate("ACTIVE");

        service().onOrderPlaced(orderId, buyerId, 0L, "EUR");
        service().onOrderPlaced(orderId, buyerId, -100L, "EUR");

        verify(commissionRepo, never()).save(any());
    }

    // ---------------------------------------------------------------- cuánto

    @Test
    void laComisionEsElPorcentajeConfiguradoSobreElImporteDeProducto() {
        defaults();
        liveAttribution();
        AffiliateEntity a = affiliate("ACTIVE");

        service().onOrderPlaced(orderId, buyerId, 10_000L, "EUR");

        // 10% de 100,00 € = 10,00 €
        verify(commissionRepo).save(argThat(c -> c.getAmountCents() == 1_000L && "PENDING".equals(c.getStatus())));
        assertThat(a.getEarningsUsdCents()).isEqualTo(1_000L);
        assertThat(a.getReferralsCount()).isEqualTo(1);
    }

    @Test
    void elPorcentajePropioDelAfiliadoMandaSobreElGeneral() {
        defaults();
        liveAttribution();
        AffiliateEntity a = affiliate("ACTIVE");
        a.setCommissionPercentOverride(new BigDecimal("25.000"));

        service().onOrderPlaced(orderId, buyerId, 10_000L, "EUR");

        verify(commissionRepo).save(argThat(c -> c.getAmountCents() == 2_500L));
    }

    @Test
    void elDescuentoDelCompradorReferidoEsElDiezPorCientoDelImporteDeProducto() {
        defaults();
        liveAttribution();
        affiliate("ACTIVE");

        assertThat(service().referralDiscountCents(buyerId, 10_000L)).isEqualTo(1_000L);
        // Sin comprador identificado (compra anónima) no hay descuento que aplicar.
        assertThat(service().referralDiscountCents(null, 10_000L)).isZero();
        assertThat(service().referralDiscountCents(buyerId, 0L)).isZero();
    }

    // ---------------------------------------------------------------- deshacer

    @Test
    void cancelarElPedidoDeshaceLaComisionYLoDevengado() {
        AffiliateEntity a = affiliate("ACTIVE");
        a.setEarningsUsdCents(1_000L);
        AffiliateConversionEntity conv = new AffiliateConversionEntity();
        conv.setId(UUID.randomUUID());
        conv.setOrderId(orderId);
        conv.setStatus("CONFIRMED");
        AffiliateCommissionEntity comm = new AffiliateCommissionEntity();
        comm.setId(UUID.randomUUID());
        comm.setAffiliateId(affiliateId);
        comm.setAmountCents(1_000L);
        comm.setStatus("PENDING");
        when(conversionRepo.findByOrderId(orderId)).thenReturn(Optional.of(conv));
        when(conversionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(commissionRepo.findByConversionId(conv.getId())).thenReturn(Optional.of(comm));
        when(commissionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service().rejectForOrder(orderId);

        assertThat(conv.getStatus()).isEqualTo("CANCELLED");
        assertThat(comm.getStatus()).isEqualTo("REJECTED");
        assertThat(a.getEarningsUsdCents()).isZero();
    }

    @Test
    void cancelarElPedidoNoDeshaceUnaComisionQueYaSePago() {
        // El dinero ya salió: revertirlo aquí descuadraría el libro sin recuperar nada.
        AffiliateEntity a = affiliate("ACTIVE");
        a.setEarningsUsdCents(1_000L);
        AffiliateConversionEntity conv = new AffiliateConversionEntity();
        conv.setId(UUID.randomUUID());
        conv.setOrderId(orderId);
        AffiliateCommissionEntity comm = new AffiliateCommissionEntity();
        comm.setAffiliateId(affiliateId);
        comm.setAmountCents(1_000L);
        comm.setStatus("PAID");
        when(conversionRepo.findByOrderId(orderId)).thenReturn(Optional.of(conv));
        when(conversionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(commissionRepo.findByConversionId(conv.getId())).thenReturn(Optional.of(comm));

        service().rejectForOrder(orderId);

        assertThat(comm.getStatus()).isEqualTo("PAID");
        assertThat(a.getEarningsUsdCents()).isEqualTo(1_000L);
        verify(commissionRepo, never()).save(any());
    }

    @Test
    void loDevengadoNuncaQuedaEnNegativoAlDeshacer() {
        AffiliateEntity a = affiliate("ACTIVE");
        a.setEarningsUsdCents(500L);          // menos de lo que vale la comisión que se deshace
        AffiliateConversionEntity conv = new AffiliateConversionEntity();
        conv.setId(UUID.randomUUID());
        conv.setOrderId(orderId);
        AffiliateCommissionEntity comm = new AffiliateCommissionEntity();
        comm.setAffiliateId(affiliateId);
        comm.setAmountCents(1_000L);
        comm.setStatus("PENDING");
        when(conversionRepo.findByOrderId(orderId)).thenReturn(Optional.of(conv));
        when(conversionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(commissionRepo.findByConversionId(conv.getId())).thenReturn(Optional.of(comm));
        when(commissionRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service().rejectForOrder(orderId);

        assertThat(a.getEarningsUsdCents()).isZero();
    }

    @Test
    void cancelarUnPedidoSinConversionNoRompeNada() {
        when(conversionRepo.findByOrderId(orderId)).thenReturn(Optional.empty());

        service().rejectForOrder(orderId);

        verify(commissionRepo, never()).save(any());
    }

    // ---------------------------------------------------------------- tope antifraude

    @Test
    void superarElTopeDelPeriodoDejaLaComisionEnRevisionEnVezDeAprobarla() {
        // DROP-652: un pico de comisiones en poco tiempo huele a fraude; se marca para revisión manual
        // en vez de devengarse sin más.
        liveAttribution();
        affiliate("ACTIVE");
        when(configRepo.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(
                AffiliateProgramConfigEntity.builder().defaultPercent(new BigDecimal("10.000"))
                        .attributionWindowDays(30).returnPeriodDays(14).minPayoutCents(5000).currency("EUR")
                        .attributionModel("LAST_CLICK").maxCommissionPeriodCents(500L).maxPeriodDays(30).build()));
        when(conversionRepo.existsByOrderId(orderId)).thenReturn(false);
        when(conversionRepo.save(any())).thenAnswer(i -> {
            AffiliateConversionEntity c = i.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        when(commissionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(commissionRepo.findByAffiliateIdOrderByCreatedAtDesc(any())).thenReturn(java.util.List.of());

        service().onOrderPlaced(orderId, buyerId, 10_000L, "EUR");

        verify(commissionRepo).save(argThat(c -> "REVIEW".equals(c.getStatus())));
    }
}
