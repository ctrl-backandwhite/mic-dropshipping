package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.AddCodeRequest;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.AffiliateDashboardView;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.BindRequest;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.PayoutProfileUpdateRequest;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.PayoutProfileView;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.PayoutRequest;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.ReferralCodeView;
import com.nexaplatform.dropshipping.api.mapper.AffiliateViewMapper;
import com.nexaplatform.dropshipping.application.service.AffiliateProgramService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateCommissionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateConversionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliatePayoutEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateProgramConfigEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateReferralCodeEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas del área de afiliado del cliente: quién puede pedir el cobro, qué porcentaje se le muestra y
 * de dónde sale el usuario (siempre del token, nunca del cuerpo de la petición).
 *
 * <p>Se usa el {@link AffiliateViewMapper} real: el cuadro de mando es sobre todo agregación, y con un
 * mapper simulado los importes no probarían nada.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov02MeAffiliateControllerTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private AffiliateProgramService service;

    @Mock
    private Authentication auth;

    private MeAffiliateController controller;
    private AffiliateEntity afiliado;
    private AffiliateProgramConfigEntity config;
    private List<AffiliateCommissionEntity> comisiones;
    private List<AffiliatePayoutEntity> cobros;

    @BeforeEach
    void setUp() {
        controller = new MeAffiliateController(service, new AffiliateViewMapper());
        when(auth.getName()).thenReturn(USER_ID.toString());

        afiliado = AffiliateEntity.builder().code("ana01").status("ACTIVE").build();
        afiliado.setId(UUID.randomUUID());
        config = AffiliateProgramConfigEntity.builder().defaultPercent(new BigDecimal("10.000"))
                .attributionWindowDays(30).returnPeriodDays(14).minPayoutCents(5000).currency("EUR").build();
        comisiones = new ArrayList<>();
        cobros = new ArrayList<>();

        when(service.getOrCreateForUser(USER_ID)).thenReturn(afiliado);
        when(service.config()).thenReturn(config);
        when(service.listCodes(afiliado.getId())).thenReturn(List.of(codigo("ana01", 7)));
        when(service.conversionsForAffiliate(afiliado.getId())).thenReturn(List.of());
        when(service.commissionsForAffiliate(afiliado.getId())).thenAnswer(inv -> comisiones);
        when(service.payoutsForAffiliate(afiliado.getId())).thenAnswer(inv -> cobros);
    }

    private static AffiliateReferralCodeEntity codigo(String code, int clicks) {
        AffiliateReferralCodeEntity c = AffiliateReferralCodeEntity.builder().code(code).label("Principal")
                .active(true).clicks(clicks).build();
        c.setId(UUID.randomUUID());
        return c;
    }

    private static AffiliateCommissionEntity comision(String estado, long cents) {
        AffiliateCommissionEntity c = AffiliateCommissionEntity.builder().amountCents(cents).currency("EUR")
                .percentage(new BigDecimal("10.000")).status(estado).conversionId(UUID.randomUUID()).build();
        c.setId(UUID.randomUUID());
        c.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return c;
    }

    /* ============ cuadro de mando ============ */

    @Test
    void elCuadroDeMandoSumaLasComisionesPorEstado() {
        comisiones.add(comision("PENDING", 1000));
        comisiones.add(comision("APPROVED", 2000));
        comisiones.add(comision("PAID", 500));

        AffiliateDashboardView vista = controller.dashboard(auth).getBody();

        assertThat(vista).isNotNull();
        assertThat(vista.stats().pendingCents()).isEqualTo(1000);
        assertThat(vista.stats().approvedCents()).isEqualTo(2000);
        assertThat(vista.stats().paidCents()).isEqualTo(500);
        assertThat(vista.stats().currency()).isEqualTo("EUR");
    }

    @Test
    void sinAceptarLosTerminosElAfiliadoNoFiguraComoUnido() {
        AffiliateDashboardView vista = controller.dashboard(auth).getBody();

        assertThat(vista).isNotNull();
        assertThat(vista.joined()).isFalse();
    }

    @Test
    void unirseAlProgramaRegistraLaAceptacionYDevuelveElCuadroDeMando() {
        // Sin la marca de aceptación no hay consentimiento explícito de las condiciones del programa.
        afiliado.setAcceptedTermsAt(Instant.now());

        AffiliateDashboardView vista = controller.join(auth).getBody();

        verify(service).joinProgram(USER_ID);
        assertThat(vista).isNotNull();
        assertThat(vista.joined()).isTrue();
    }

    @Test
    void elPorcentajeParticularDelAfiliadoManadaSobreElDelPrograma() {
        afiliado.setCommissionPercentOverride(new BigDecimal("25.000"));

        AffiliateDashboardView vista = controller.dashboard(auth).getBody();

        assertThat(vista).isNotNull();
        assertThat(vista.commissionPercent()).isEqualByComparingTo("25.000");
    }

    @Test
    void sinPorcentajeParticularSeMuestraElDelPrograma() {
        AffiliateDashboardView vista = controller.dashboard(auth).getBody();

        assertThat(vista).isNotNull();
        assertThat(vista.commissionPercent()).isEqualByComparingTo("10.000");
    }

    @Test
    void noSePuedePedirElCobroSiNoSeLlegaAlMinimo() {
        comisiones.add(comision("APPROVED", 4999));

        AffiliateDashboardView vista = controller.dashboard(auth).getBody();

        assertThat(vista).isNotNull();
        assertThat(vista.canRequestPayout()).isFalse();
        assertThat(vista.minPayoutCents()).isEqualTo(5000);
    }

    @Test
    void alLlegarAlMinimoSeHabilitaLaPeticionDeCobro() {
        comisiones.add(comision("APPROVED", 5000));

        AffiliateDashboardView vista = controller.dashboard(auth).getBody();

        assertThat(vista).isNotNull();
        assertThat(vista.canRequestPayout()).isTrue();
    }

    @Test
    void conUnCobroYaSolicitadoNoSePuedePedirOtro() {
        // Dos peticiones abiertas del mismo afiliado pagarían dos veces las mismas comisiones.
        comisiones.add(comision("APPROVED", 20000));
        AffiliatePayoutEntity pendiente = AffiliatePayoutEntity.builder().affiliateId(afiliado.getId())
                .amountCents(20000).currency("EUR").status("REQUESTED").method("WALLET").build();
        cobros.add(pendiente);

        AffiliateDashboardView vista = controller.dashboard(auth).getBody();

        assertThat(vista).isNotNull();
        assertThat(vista.payoutRequested()).isTrue();
        assertThat(vista.canRequestPayout()).isFalse();
    }

    @Test
    void unCobroYaPagadoNoBloqueaLaSiguientePeticion() {
        comisiones.add(comision("APPROVED", 20000));
        cobros.add(AffiliatePayoutEntity.builder().affiliateId(afiliado.getId()).amountCents(9000).currency("EUR")
                .status("PAID").method("BANK").build());

        AffiliateDashboardView vista = controller.dashboard(auth).getBody();

        assertThat(vista).isNotNull();
        assertThat(vista.payoutRequested()).isFalse();
        assertThat(vista.canRequestPayout()).isTrue();
    }

    @Test
    void elCuadroDeMandoSoloMuestraLasVeinteComisionesMasRecientes() {
        // La vista del cliente no puede arrastrar el historial completo en cada carga.
        for (int i = 0; i < 25; i++) {
            comisiones.add(comision("PAID", 100));
        }

        AffiliateDashboardView vista = controller.dashboard(auth).getBody();

        assertThat(vista).isNotNull();
        assertThat(vista.recentCommissions()).hasSize(20);
        assertThat(vista.stats().paidCents()).isEqualTo(2500);
    }

    @Test
    void losClicsNuncaPuedenSerMenosQueLasConversiones() {
        // Dato heredado: contadores de clic a 0 con comisiones cobradas ("0 clics pero pagado").
        AffiliateConversionEntity conv1 = AffiliateConversionEntity.builder().affiliateId(afiliado.getId())
                .baseAmountCents(1000).currency("EUR").build();
        conv1.setId(UUID.randomUUID());
        AffiliateConversionEntity conv2 = AffiliateConversionEntity.builder().affiliateId(afiliado.getId())
                .baseAmountCents(2000).currency("EUR").build();
        conv2.setId(UUID.randomUUID());
        when(service.listCodes(afiliado.getId())).thenReturn(List.of(codigo("ana01", 0)));
        when(service.conversionsForAffiliate(afiliado.getId())).thenReturn(List.of(conv1, conv2));

        AffiliateDashboardView vista = controller.dashboard(auth).getBody();

        assertThat(vista).isNotNull();
        assertThat(vista.stats().conversions()).isEqualTo(2);
        assertThat(vista.stats().clicks()).isEqualTo(2);
    }

    /* ============ cobro y perfil ============ */

    @Test
    void pedirCobroSinCuerpoUsaLaCarteraComoMetodoPorDefecto() {
        AffiliatePayoutEntity creado = AffiliatePayoutEntity.builder().affiliateId(afiliado.getId())
                .amountCents(9000).currency("EUR").status("REQUESTED").method("WALLET").build();
        creado.setId(UUID.randomUUID());
        when(service.requestPayout(USER_ID, "WALLET")).thenReturn(creado);

        Map<String, Object> cuerpo = controller.requestPayout(auth, null).getBody();

        assertThat(cuerpo).containsEntry("status", "REQUESTED").containsEntry("method", "WALLET");
        verify(service).requestPayout(USER_ID, "WALLET");
    }

    @Test
    void pedirCobroConMetodoNuloEnElCuerpoTambienCaeEnCartera() {
        AffiliatePayoutEntity creado = AffiliatePayoutEntity.builder().affiliateId(afiliado.getId())
                .amountCents(9000).currency("EUR").status("REQUESTED").method("WALLET").build();
        creado.setId(UUID.randomUUID());
        when(service.requestPayout(USER_ID, "WALLET")).thenReturn(creado);

        controller.requestPayout(auth, new PayoutRequest(null));

        verify(service).requestPayout(USER_ID, "WALLET");
    }

    @Test
    void pedirCobroRespetaElMetodoElegido() {
        AffiliatePayoutEntity creado = AffiliatePayoutEntity.builder().affiliateId(afiliado.getId())
                .amountCents(9000).currency("EUR").status("REQUESTED").method("BANK").build();
        creado.setId(UUID.randomUUID());
        when(service.requestPayout(USER_ID, "BANK")).thenReturn(creado);

        Map<String, Object> cuerpo = controller.requestPayout(auth, new PayoutRequest("BANK")).getBody();

        assertThat(cuerpo).containsEntry("method", "BANK");
    }

    @Test
    void actualizarElPerfilDeCobroDevuelveElPerfilYaGuardado() {
        PayoutProfileView guardado = new PayoutProfileView("BANK", "Ana", "ES** **** 1234", "BIC", null, true, false);
        when(service.getPayoutProfile(USER_ID)).thenReturn(guardado);
        PayoutProfileUpdateRequest req = new PayoutProfileUpdateRequest("Ana", "ES1234", "BIC", null, "BANK", "clave");

        PayoutProfileView vista = controller.updatePayoutProfile(auth, req).getBody();

        verify(service).updatePayoutProfile(USER_ID, req);
        assertThat(vista).isNotNull();
        // El IBAN vuelve enmascarado: la respuesta del guardado no puede revelarlo entero.
        assertThat(vista.bankIbanMasked()).isEqualTo("ES** **** 1234");
    }

    @Test
    void consultarElPerfilDeCobroUsaSiempreElUsuarioDelToken() {
        PayoutProfileView perfil = new PayoutProfileView("WALLET", null, null, null, null, false, false);
        when(service.getPayoutProfile(USER_ID)).thenReturn(perfil);

        assertThat(controller.payoutProfile(auth).getBody()).isSameAs(perfil);
        verify(service).getPayoutProfile(USER_ID);
    }

    /* ============ códigos y vinculación ============ */

    @Test
    void crearUnCodigoLoAsociaAlAfiliadoDelUsuarioAutenticado() {
        AffiliateReferralCodeEntity nuevo = codigo("ana02", 0);
        when(service.addCode(afiliado.getId(), "Instagram")).thenReturn(nuevo);

        ReferralCodeView vista = controller.addCode(auth, new AddCodeRequest("Instagram")).getBody();

        assertThat(vista).isNotNull();
        assertThat(vista.code()).isEqualTo("ana02");
        // El enlace que se comparte se construye a partir del código, no lo elige el cliente.
        assertThat(vista.url()).isEqualTo("/?ref=ana02");
    }

    @Test
    void desactivarUnCodigoDevuelveSuNuevoEstado() {
        AffiliateReferralCodeEntity desactivado = AffiliateReferralCodeEntity.builder().code("ana01")
                .label("Principal").active(false).clicks(3).build();
        desactivado.setId(UUID.randomUUID());
        when(service.setCodeActive(USER_ID, desactivado.getId(), false)).thenReturn(desactivado);

        ReferralCodeView vista = controller.toggle(auth, desactivado.getId(), false).getBody();

        assertThat(vista).isNotNull();
        assertThat(vista.active()).isFalse();
    }

    @Test
    void elCodigoDeOtroAfiliadoNoSePuedeApagar() {
        // El sujeto sale del token y el servicio comprueba propiedad: sin eso bastaba conocer un
        // identificador de código para apagar el enlace de otro y cortarle las comisiones futuras.
        UUID codigoAjeno = UUID.randomUUID();
        when(service.setCodeActive(USER_ID, codigoAjeno, false))
                .thenThrow(new NotFoundException("Code not found"));

        assertThatThrownBy(() -> controller.toggle(auth, codigoAjeno, false))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void vincularLaCookieAnonimaNoDevuelveCuerpo() {
        ResponseEntity<Void> respuesta = controller.bind(auth, new BindRequest("visitor-token-1"));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(204);
        verify(service).bindVisitorToUser("visitor-token-1", USER_ID);
    }
}
