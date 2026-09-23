package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.AdminAffiliateRow;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.AffiliateStats;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.CommissionView;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.ProgramConfigView;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.ReferralCodeView;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateCommissionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateConversionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateProgramConfigEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateReferralCodeEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vistas del programa de afiliados.
 *
 * <p>La regla de negocio que se protege aquí es DROP-694: los clics NUNCA pueden ser menos que las
 * conversiones, porque toda conversión exige un clic atribuido. Datos antiguos dejaban el contador a 0
 * con comisiones ya pagadas y el panel mostraba "0 clics pero comisiones cobradas"; el invariante se
 * fuerza al leer.
 */
class Cov09AffiliateViewMapperTest {

    private AffiliateViewMapper subject;

    @BeforeEach
    void buildSubject() {
        subject = new AffiliateViewMapper();
    }

    private static AffiliateReferralCodeEntity codigo(String code, int clicks) {
        AffiliateReferralCodeEntity c = AffiliateReferralCodeEntity.builder().code(code).label("verano").active(true)
                .clicks(clicks).build();
        c.setId(UUID.randomUUID());
        return c;
    }

    private static AffiliateCommissionEntity comision(String status, long amountCents, UUID conversionId,
            Instant createdAt) {
        AffiliateCommissionEntity c = AffiliateCommissionEntity.builder().amountCents(amountCents).currency("USD")
                .percentage(new BigDecimal("10.00")).status(status).conversionId(conversionId).build();
        c.setId(UUID.randomUUID());
        c.setCreatedAt(createdAt);
        return c;
    }

    private static AffiliateConversionEntity conversion(UUID id, UUID orderId, long baseAmountCents) {
        AffiliateConversionEntity c = AffiliateConversionEntity.builder().orderId(orderId)
                .baseAmountCents(baseAmountCents).build();
        c.setId(id);
        return c;
    }

    // ---------------------------------------------------------------- enlace de referido

    @Test
    void elCodigoDeReferidoSePublicaConSuEnlaceParaCompartir() {
        ReferralCodeView view = subject.toCodeView(codigo("ADA10", 7));

        assertThat(view.code()).isEqualTo("ADA10");
        assertThat(view.url()).isEqualTo("/?ref=ADA10");
        assertThat(view.clicks()).isEqualTo(7);
        assertThat(view.active()).isTrue();
    }

    // ---------------------------------------------------------------- comisiones

    @Test
    void soloUnaComisionPendienteAnunciaCuandoSeAprobara() {
        // La cuenta atrás de la wallet sale de aquí: creación + periodo de devolución.
        Instant creada = Instant.parse("2026-01-01T00:00:00Z");
        UUID conversionId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Map<UUID, AffiliateConversionEntity> porConversion = Map.of(conversionId,
                conversion(conversionId, orderId, 5000L));

        CommissionView pendiente = subject.toCommissionView(comision("PENDING", 500L, conversionId, creada),
                porConversion, 14);
        CommissionView aprobada = subject.toCommissionView(comision("APPROVED", 500L, conversionId, creada),
                porConversion, 14);

        assertThat(pendiente.approvesAt()).isEqualTo(creada.plus(14, ChronoUnit.DAYS));
        assertThat(aprobada.approvesAt()).isNull();
        assertThat(pendiente.orderId()).isEqualTo(orderId);
        assertThat(pendiente.baseAmountCents()).isEqualTo(5000L);
    }

    @Test
    void unPeriodoDeDevolucionNegativoNoAdelantaLaAprobacionAlPasado() {
        // Un valor negativo en la configuración pondría la fecha de aprobación ANTES de la propia comisión.
        Instant creada = Instant.parse("2026-01-01T00:00:00Z");

        CommissionView view = subject.toCommissionView(comision("PENDING", 500L, UUID.randomUUID(), creada), Map.of(),
                -30);

        assertThat(view.approvesAt()).isEqualTo(creada);
    }

    @Test
    void unaComisionSinFechaDeCreacionNoInventaFechaDeAprobacion() {
        CommissionView view = subject.toCommissionView(comision("PENDING", 500L, UUID.randomUUID(), null), Map.of(),
                14);

        assertThat(view.approvesAt()).isNull();
    }

    @Test
    void unaComisionCuyaConversionNoSeEncuentraNoRompeLaVista() {
        // El pedido de origen puede haberse borrado: la comisión sigue teniendo que poder listarse.
        CommissionView view = subject.toCommissionView(comision("PAID", 500L, UUID.randomUUID(), Instant.now()),
                Map.of(), 14);

        assertThat(view.orderId()).isNull();
        assertThat(view.baseAmountCents()).isZero();
    }

    @Test
    void lasConversionesSeIndexanPorSuIdentificadorSinRomperConDuplicados() {
        UUID id = UUID.randomUUID();
        AffiliateConversionEntity primera = conversion(id, UUID.randomUUID(), 100L);
        AffiliateConversionEntity duplicada = conversion(id, UUID.randomUUID(), 200L);

        Map<UUID, AffiliateConversionEntity> index = subject.indexByConversionId(List.of(primera, duplicada));

        assertThat(index).hasSize(1);
        assertThat(index.get(id)).isSameAs(primera);
    }

    // ---------------------------------------------------------------- estadísticas

    @Test
    void losImportesSeAgrupanPorEstadoDeLaComision() {
        List<AffiliateCommissionEntity> comisiones = List.of(
                comision("PENDING", 100L, UUID.randomUUID(), Instant.now()),
                comision("PENDING", 200L, UUID.randomUUID(), Instant.now()),
                comision("APPROVED", 400L, UUID.randomUUID(), Instant.now()),
                comision("PAID", 800L, UUID.randomUUID(), Instant.now()),
                comision("REJECTED", 1600L, UUID.randomUUID(), Instant.now()));

        AffiliateStats stats = subject.stats(List.of(codigo("A", 10)), List.of(), comisiones, "USD");

        assertThat(stats.pendingCents()).isEqualTo(300L);
        assertThat(stats.approvedCents()).isEqualTo(400L);
        // Una comisión rechazada no suma en ningún cubo: no es dinero del afiliado.
        assertThat(stats.paidCents()).isEqualTo(800L);
        assertThat(stats.currency()).isEqualTo("USD");
    }

    @Test
    void losClicsNuncaPuedenSerMenosQueLasConversiones() {
        // Datos heredados dejaban el contador a 0 con comisiones pagadas: "0 clics pero comisiones cobradas".
        List<AffiliateConversionEntity> conversiones = List.of(conversion(UUID.randomUUID(), UUID.randomUUID(), 100L),
                conversion(UUID.randomUUID(), UUID.randomUUID(), 100L),
                conversion(UUID.randomUUID(), UUID.randomUUID(), 100L));

        AffiliateStats stats = subject.stats(List.of(codigo("A", 0)), conversiones, List.of(), "USD");

        assertThat(stats.clicks()).isEqualTo(3);
        assertThat(stats.conversions()).isEqualTo(3);
    }

    @Test
    void losClicsRealesDeTodosLosCodigosSeSumanCuandoSuperanALasConversiones() {
        AffiliateStats stats = subject.stats(List.of(codigo("A", 40), codigo("B", 2)),
                List.of(conversion(UUID.randomUUID(), UUID.randomUUID(), 100L)), List.of(), "EUR");

        assertThat(stats.clicks()).isEqualTo(42);
    }

    // ---------------------------------------------------------------- fila del panel de admin

    @Test
    void laFilaDeAdminMuestraAlDuenoYSusImportesPorEstado() {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("ada@example.com");
        user.setDisplayName("Ada");
        AffiliateEntity afiliado = AffiliateEntity.builder().user(user).status("ACTIVE").referralsCount(2)
                .earningsUsdCents(5000).payoutUsdCents(1000).commissionPercentOverride(new BigDecimal("12.5")).build();
        afiliado.setId(UUID.randomUUID());

        AdminAffiliateRow row = subject.toAdminRow(afiliado, List.of(codigo("A", 9)),
                List.of(comision("PENDING", 100L, UUID.randomUUID(), Instant.now()),
                        comision("APPROVED", 250L, UUID.randomUUID(), Instant.now())),
                "USD");

        assertThat(row.email()).isEqualTo("ada@example.com");
        assertThat(row.name()).isEqualTo("Ada");
        assertThat(row.userId()).isEqualTo(user.getId());
        assertThat(row.codesCount()).isEqualTo(1);
        assertThat(row.clicks()).isEqualTo(9);
        assertThat(row.earningsCents()).isEqualTo(5000L);
        assertThat(row.paidCents()).isEqualTo(1000L);
        assertThat(row.pendingCents()).isEqualTo(100L);
        assertThat(row.approvedCents()).isEqualTo(250L);
        assertThat(row.commissionPercentOverride()).isEqualByComparingTo("12.5");
    }

    @Test
    void laFilaDeAdminTambienFuerzaElMinimoDeClicsSobreLosReferidos() {
        AffiliateEntity afiliado = AffiliateEntity.builder().status("ACTIVE").referralsCount(5).build();
        afiliado.setId(UUID.randomUUID());

        AdminAffiliateRow row = subject.toAdminRow(afiliado, List.of(codigo("A", 1)), List.of(), "USD");

        assertThat(row.clicks()).isEqualTo(5);
    }

    @Test
    void unAfiliadoSinUsuarioAsociadoSeListaSinNombreNiCorreoEnVezDeReventar() {
        AffiliateEntity afiliado = AffiliateEntity.builder().status("PENDING").build();
        afiliado.setId(UUID.randomUUID());

        AdminAffiliateRow row = subject.toAdminRow(afiliado, List.of(), List.of(), "USD");

        assertThat(row.userId()).isNull();
        assertThat(row.name()).isNull();
        assertThat(row.email()).isNull();
    }

    // ---------------------------------------------------------------- configuración del programa

    @Test
    void laConfiguracionDelProgramaSePublicaEntera() {
        AffiliateProgramConfigEntity config = AffiliateProgramConfigEntity.builder()
                .defaultPercent(new BigDecimal("10.00")).attributionWindowDays(30).returnPeriodDays(14)
                .minPayoutCents(5000).currency("USD").attributionModel("LAST_CLICK").maxCommissionPeriodCents(100000)
                .maxPeriodDays(30).clickDedupMinutes(45).build();

        ProgramConfigView view = subject.toConfigView(config);

        assertThat(view.defaultPercent()).isEqualByComparingTo("10.00");
        assertThat(view.attributionWindowDays()).isEqualTo(30);
        assertThat(view.returnPeriodDays()).isEqualTo(14);
        assertThat(view.minPayoutCents()).isEqualTo(5000L);
        assertThat(view.attributionModel()).isEqualTo("LAST_CLICK");
        assertThat(view.maxCommissionPeriodCents()).isEqualTo(100000L);
        assertThat(view.clickDedupMinutes()).isEqualTo(45);
    }
}
