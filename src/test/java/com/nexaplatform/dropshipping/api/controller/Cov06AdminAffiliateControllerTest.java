package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.AdminAffiliateRow;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.ApprovePayoutRequest;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.PendingPayoutView;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.StatusRequest;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AffiliateViewMapper;
import com.nexaplatform.dropshipping.application.service.AdminAffiliateQueryService;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliatePayoutEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateProgramConfigEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Panel de afiliados del administrador: paginación, bandeja de pagos e identificación del beneficiario.
 * Un pago mal identificado se transfiere a la persona equivocada, así que el nombre que se enseña en la
 * bandeja es tan crítico como el importe.
 */
@ExtendWith(MockitoExtension.class)
class Cov06AdminAffiliateControllerTest {

    @Mock
    AffiliateProgramService service;
    @Mock
    AffiliateViewMapper mapper;
    @Mock
    AdminAffiliateQueryService affiliateQuery;
    @Mock
    AffiliateIndexer affiliateIndexer;
    @Mock
    CurrencyRateService currencyRateService;

    @InjectMocks
    AdminAffiliateController controller;

    /* ------------------------------ listado ------------------------------ */

    /** El número de páginas se redondea HACIA ARRIBA: si no, la última página quedaría inalcanzable. */
    @Test
    void elTotalDePaginasRedondeaHaciaArriba() {
        when(affiliateQuery.page(null, null, 0, 20)).thenReturn(new AdminAffiliateQueryService.AffiliatePage(
                List.of(), 41));

        ResponseEntity<PageResponse<AdminAffiliateRow>> resp = controller.list(null, null, 0, 20);

        assertThat(resp.getBody().totalPages()).isEqualTo(3);
        assertThat(resp.getBody().totalElements()).isEqualTo(41);
    }

    /** Tamaño de página 0 (parámetro manipulado): no puede provocar una división por cero. */
    @Test
    void tamanoDePaginaCeroNoRompeElCalculo() {
        when(affiliateQuery.page(null, null, 0, 0))
                .thenReturn(new AdminAffiliateQueryService.AffiliatePage(List.of(), 5));

        ResponseEntity<PageResponse<AdminAffiliateRow>> resp = controller.list(null, null, 0, 0);

        assertThat(resp.getBody().totalPages()).isEqualTo(5);
    }

    @Test
    void reindexarDevuelveCuantosAfiliadosSeIndexaron() {
        when(affiliateIndexer.reindexAll()).thenReturn(12);

        ResponseEntity<Map<String, Object>> resp = controller.reindex();

        assertThat(resp.getBody()).containsEntry("indexed", 12);
    }

    /* ------------------------------ detalle ------------------------------ */

    @Test
    void elDetalleDeUnAfiliadoInexistenteEs404() {
        UUID id = UUID.randomUUID();
        when(service.config()).thenReturn(config());
        when(service.allAffiliates()).thenReturn(List.of());

        assertThatThrownBy(() -> controller.detail(id)).isInstanceOf(NotFoundException.class);
    }

    /* ------------------------------ bandeja de pagos ------------------------------ */

    /**
     * Manda el nombre que el propio usuario eligió; si no tiene ninguno se cae a su email, y un pago sin
     * usuario asociado se queda sin nombre en vez de reventar la bandeja entera.
     */
    @Test
    void elNombreDelBeneficiarioPrefiereElNombreVisibleYCaeAlEmail() {
        UUID conNombre = UUID.randomUUID();
        UUID soloEmail = UUID.randomUUID();
        UUID sinUsuario = UUID.randomUUID();
        when(service.allAffiliates()).thenReturn(List.of(afiliado(conNombre, usuario("Ana", "ana@x.com")),
                afiliado(soloEmail, usuario("   ", "bob@x.com")), afiliado(sinUsuario, null)));
        when(service.pendingPayouts()).thenReturn(List.of(payout(conNombre), payout(soloEmail), payout(sinUsuario)));
        when(currencyRateService.formatDisplay(any(), eq("EUR"))).thenReturn("25,00 €");

        List<PendingPayoutView> vistas = controller.pendingPayouts().getBody();

        assertThat(vistas).extracting(PendingPayoutView::affiliateName)
                .containsExactly("Ana", "bob@x.com", null);
    }

    /** El importe se enseña formateado por el backend a partir de los céntimos (2500 → "25,00 €"). */
    @Test
    void elImporteDelPagoSeFormateaDesdeLosCentimos() {
        UUID afiliadoId = UUID.randomUUID();
        when(service.allAffiliates()).thenReturn(List.of(afiliado(afiliadoId, usuario("Ana", "ana@x.com"))));
        when(service.pendingPayouts()).thenReturn(List.of(payout(afiliadoId)));
        when(currencyRateService.formatDisplay(new BigDecimal("25.00"), "EUR")).thenReturn("25,00 €");

        List<PendingPayoutView> vistas = controller.pendingPayouts().getBody();

        assertThat(vistas.get(0).amountFormatted()).isEqualTo("25,00 €");
        assertThat(vistas.get(0).amountCents()).isEqualTo(2500);
    }

    /* ------------------------------ aprobar / rechazar ------------------------------ */

    /** Aprobar sin cuerpo (pago por wallet, sin referencia externa) no puede fallar por el body ausente. */
    @Test
    void aprobarUnPagoSinCuerpoNoLlevaReferencia() {
        UUID adminId = UUID.randomUUID();
        UUID payoutId = UUID.randomUUID();
        Authentication auth = auth(adminId);
        AffiliatePayoutEntity pagado = AffiliatePayoutEntity.builder().status("PAID").build();
        when(service.approvePayout(payoutId, adminId, null)).thenReturn(pagado);

        ResponseEntity<Map<String, Object>> resp = controller.approvePayout(auth, payoutId, null);

        assertThat(resp.getBody()).containsEntry("status", "PAID");
        // Map.of no admite valores nulos: sin referencia se devuelve cadena vacía, no null.
        assertThat(resp.getBody()).containsEntry("reference", "");
    }

    @Test
    void aprobarUnPagoConReferenciaLaDevuelveAlAdmin() {
        UUID adminId = UUID.randomUUID();
        UUID payoutId = UUID.randomUUID();
        AffiliatePayoutEntity pagado = AffiliatePayoutEntity.builder().status("PAID").paidReference("TRF-99").build();
        when(service.approvePayout(payoutId, adminId, "TRF-99")).thenReturn(pagado);

        ResponseEntity<Map<String, Object>> resp = controller.approvePayout(auth(adminId), payoutId,
                new ApprovePayoutRequest("TRF-99"));

        assertThat(resp.getBody()).containsEntry("reference", "TRF-99");
    }

    @Test
    void rechazarUnPagoSinCuerpoNoLlevaMotivo() {
        UUID payoutId = UUID.randomUUID();

        ResponseEntity<Void> resp = controller.rejectPayout(payoutId, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(service).rejectPayout(payoutId, null);
    }

    @Test
    void rechazarUnPagoTrasladaElMotivoAlServicio() {
        UUID payoutId = UUID.randomUUID();

        controller.rejectPayout(payoutId, Map.of("reason", "datos bancarios erróneos"));

        verify(service).rejectPayout(payoutId, "datos bancarios erróneos");
    }

    /* ------------------------------ estado y comisiones ------------------------------ */

    @Test
    void cambiarElEstadoDelAfiliadoDelegaEnElServicio() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> resp = controller.setStatus(id, new StatusRequest("SUSPENDED"));

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(service).setAffiliateStatus(id, "SUSPENDED");
    }

    @Test
    void resolverUnaRevisionDeFraudeTrasladaLaDecision() {
        UUID commissionId = UUID.randomUUID();

        controller.resolveReview(commissionId, false);

        verify(service).resolveReview(commissionId, false);
    }

    @Test
    void elPagoManualDevuelveLoAbonadoEnCentimos() {
        UUID id = UUID.randomUUID();
        when(service.payoutApproved(id, true)).thenReturn(7500L);

        ResponseEntity<Map<String, Long>> resp = controller.payout(id);

        assertThat(resp.getBody()).containsEntry("paidCents", 7500L);
    }

    @Test
    void aprobarLasComisionesVencidasDevuelveCuantasSeAprobaron() {
        when(service.approveDueCommissions()).thenReturn(4);

        ResponseEntity<Map<String, Integer>> resp = controller.approveDue();

        assertThat(resp.getBody()).containsEntry("approved", 4);
    }

    /* ------------------------------ helpers ------------------------------ */

    private static AffiliateProgramConfigEntity config() {
        return AffiliateProgramConfigEntity.builder().currency("EUR").returnPeriodDays(14)
                .defaultPercent(new BigDecimal("5.000")).build();
    }

    private static UserEntity usuario(String displayName, String email) {
        UserEntity u = new UserEntity();
        u.setDisplayName(displayName);
        u.setEmail(email);
        return u;
    }

    private static AffiliateEntity afiliado(UUID id, UserEntity user) {
        AffiliateEntity a = new AffiliateEntity();
        a.setId(id);
        a.setUser(user);
        return a;
    }

    private static AffiliatePayoutEntity payout(UUID affiliateId) {
        return AffiliatePayoutEntity.builder().affiliateId(affiliateId).amountCents(2500).currency("EUR")
                .method("BANK").build();
    }

    private static Authentication auth(UUID userId) {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId.toString());
        return auth;
    }
}
