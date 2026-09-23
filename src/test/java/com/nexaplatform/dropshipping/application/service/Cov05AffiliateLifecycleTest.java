package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateProgramConfigEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateReferralCodeEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NotificationEntity;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Alta y ciclo de vida del afiliado: quién puede serlo, cómo se genera su código de referido, la
 * aceptación de términos, la gestión de códigos, el cambio de estado y la configuración del programa.
 * El pago de comisiones lo cubren otras suites; aquí se fija todo lo anterior al devengo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov05AffiliateLifecycleTest {

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

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AFFILIATE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        when(affiliateRepo.save(any())).thenAnswer(i -> {
            AffiliateEntity a = i.getArgument(0);
            if (a.getId() == null) {
                a.setId(AFFILIATE_ID);
            }
            return a;
        });
        when(codeRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(codeRepo.existsByCodeIgnoreCase(anyString())).thenReturn(false);
        when(notificationRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(configRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private UserEntity customer() {
        UserEntity u = new UserEntity();
        u.setId(USER_ID);
        u.setEmail("ana@example.com");
        u.setRole(UserRole.USER);
        u.setDisplayName("Ana Torres");
        return u;
    }

    private AffiliateEntity existingAffiliate(UserEntity owner) {
        AffiliateEntity a = AffiliateEntity.builder().user(owner).code("ref-1").active(true).status("ACTIVE").build();
        a.setId(AFFILIATE_ID);
        return a;
    }

    private AffiliateProgramConfigEntity storedConfig() {
        return AffiliateProgramConfigEntity.builder().defaultPercent(new BigDecimal("10.000")).attributionWindowDays(30)
                .returnPeriodDays(14).minPayoutCents(5000).currency("EUR").attributionModel("LAST_CLICK").build();
    }

    /* ===================== quién puede ser afiliado ===================== */

    @ParameterizedTest
    @CsvSource({"ADMIN", "OPERATOR"})
    @DisplayName("una cuenta de administración nunca puede darse de alta como afiliado")
    void lasCuentasDeAdministracionNoPuedenSerAfiliados(String role) {
        UserEntity staff = customer();
        staff.setRole(UserRole.valueOf(role));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(staff));

        assertThatThrownBy(() -> service.getOrCreateForUser(USER_ID)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("administración");
        // Si esto fallara, un admin podría cobrarse comisiones a sí mismo desde su propio panel.
        verify(affiliateRepo, never()).save(any());
    }

    @Test
    @DisplayName("un usuario que no existe no puede convertirse en afiliado")
    void unUsuarioInexistenteNoPuedeHacerseAfiliado() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrCreateForUser(USER_ID)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("el alta crea el afiliado PENDIENTE de aprobación y su primer código de referido")
    void elAltaCreaAfiliadoPendienteConSuPrimerCodigo() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(customer()));
        when(affiliateRepo.findByUser_Id(USER_ID)).thenReturn(Optional.empty());
        when(codeRepo.findByAffiliateIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        AffiliateEntity created = service.getOrCreateForUser(USER_ID);

        // El programa de afiliados exige aprobación del admin: se crea PENDIENTE e inactivo.
        assertThat(created.getStatus()).isEqualTo("PENDING");
        assertThat(created.isActive()).isFalse();
        ArgumentCaptor<AffiliateReferralCodeEntity> code = ArgumentCaptor.forClass(AffiliateReferralCodeEntity.class);
        verify(codeRepo).save(code.capture());
        assertThat(code.getValue().getCode()).isEqualTo(created.getCode());
        assertThat(code.getValue().getLabel()).isEqualTo("Primary");
        assertThat(code.getValue().isActive()).isTrue();
        verify(affiliateIndexer).indexAffiliate(created);
    }

    @Test
    @DisplayName("un afiliado que ya tiene códigos no recibe otro al releerlo")
    void obtenerUnAfiliadoExistenteNoDuplicaSuCodigo() {
        UserEntity owner = customer();
        AffiliateEntity existing = existingAffiliate(owner);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(owner));
        when(affiliateRepo.findByUser_Id(USER_ID)).thenReturn(Optional.of(existing));
        when(codeRepo.findByAffiliateIdOrderByCreatedAtAsc(AFFILIATE_ID))
                .thenReturn(List.of(AffiliateReferralCodeEntity.builder().affiliate(existing).code("ref-1").build()));

        AffiliateEntity got = service.getOrCreateForUser(USER_ID);

        assertThat(got).isSameAs(existing);
        verify(codeRepo, never()).save(any());
        verify(affiliateRepo, never()).save(any());
    }

    /* ===================== semilla del código ===================== */

    @Test
    @DisplayName("el código se saca del nombre visible, sin acentos ni símbolos y como mucho 8 letras")
    void elCodigoSeSanitizaYSeAcortaAOchoCaracteres() {
        UserEntity u = customer();
        u.setDisplayName("María Ñ Pérez-López!!");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(affiliateRepo.findByUser_Id(USER_ID)).thenReturn(Optional.empty());
        when(codeRepo.findByAffiliateIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        AffiliateEntity created = service.getOrCreateForUser(USER_ID);

        // El código viaja en una URL pública: solo minúsculas/dígitos y un sufijo aleatorio.
        assertThat(created.getCode()).startsWith("maraprez-").matches("[a-z0-9]{1,8}-[a-z0-9]{6}");
    }

    @Test
    @DisplayName("sin nombre visible el código se saca del email")
    void sinNombreVisibleElCodigoSeSacaDelEmail() {
        UserEntity u = customer();
        u.setDisplayName(null);
        u.setEmail("juan.perez@example.com");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(affiliateRepo.findByUser_Id(USER_ID)).thenReturn(Optional.empty());
        when(codeRepo.findByAffiliateIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        AffiliateEntity created = service.getOrCreateForUser(USER_ID);

        assertThat(created.getCode()).startsWith("juanpere-");
    }

    @Test
    @DisplayName("un nombre sin letras ni dígitos cae a la semilla genérica 'ref'")
    void unNombreSinLetrasCaeALaSemillaGenerica() {
        UserEntity u = customer();
        u.setDisplayName("***");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(u));
        when(affiliateRepo.findByUser_Id(USER_ID)).thenReturn(Optional.empty());
        when(codeRepo.findByAffiliateIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        AffiliateEntity created = service.getOrCreateForUser(USER_ID);

        assertThat(created.getCode()).startsWith("ref-");
    }

    @Test
    @DisplayName("un código ya usado se descarta y se reintenta con otro")
    void unCodigoYaUsadoSeReintenta() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(customer()));
        when(affiliateRepo.findByUser_Id(USER_ID)).thenReturn(Optional.empty());
        when(codeRepo.findByAffiliateIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        // Los dos primeros candidatos ya existen en base de datos: el código es único por contrato.
        when(codeRepo.existsByCodeIgnoreCase(anyString())).thenReturn(true, true, false);

        AffiliateEntity created = service.getOrCreateForUser(USER_ID);

        assertThat(created.getCode()).isNotNull();
        verify(codeRepo, times(3)).existsByCodeIgnoreCase(anyString());
    }

    /* ===================== alta explícita en el programa ===================== */

    @Test
    @DisplayName("unirse al programa sella la aceptación de términos una sola vez")
    void unirseAlProgramaSellaLosTerminosUnaSolaVez() {
        UserEntity owner = customer();
        AffiliateEntity existing = existingAffiliate(owner);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(owner));
        when(affiliateRepo.findByUser_Id(USER_ID)).thenReturn(Optional.of(existing));
        when(codeRepo.findByAffiliateIdOrderByCreatedAtAsc(AFFILIATE_ID))
                .thenReturn(List.of(AffiliateReferralCodeEntity.builder().affiliate(existing).code("ref-1").build()));

        service.joinProgram(USER_ID);
        Instant firstAcceptance = existing.getAcceptedTermsAt();
        service.joinProgram(USER_ID);

        assertThat(firstAcceptance).isNotNull();
        assertThat(existing.getAcceptedTermsAt()).isEqualTo(firstAcceptance);
        // Reincorporarse no debe reenviar la bienvenida ni volver a avisar al staff.
        verify(affiliateRepo, times(1)).save(existing);
    }

    @Test
    @DisplayName("el aviso al staff identifica al nuevo afiliado (nombre, email, país y empresa)")
    void elAvisoAlStaffIdentificaAlNuevoAfiliado() {
        UserEntity owner = customer();
        owner.setDisplayName(null);
        owner.setFirstName("Ana");
        owner.setLastName1("Gómez");
        owner.setLastName2("Ruiz");
        owner.setCountry("  ES  ");
        owner.setCompanyName("  ACME  ");
        UserEntity admin = new UserEntity();
        admin.setId(ADMIN_ID);
        admin.setEmail("admin@example.com");
        admin.setRole(UserRole.ADMIN);
        AffiliateEntity existing = existingAffiliate(owner);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(owner));
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
        when(userRepository.findAll()).thenReturn(List.of(owner, admin));
        when(affiliateRepo.findByUser_Id(USER_ID)).thenReturn(Optional.of(existing));
        when(codeRepo.findByAffiliateIdOrderByCreatedAtAsc(AFFILIATE_ID))
                .thenReturn(List.of(AffiliateReferralCodeEntity.builder().affiliate(existing).code("ref-1").build()));

        service.joinProgram(USER_ID);

        ArgumentCaptor<NotificationEntity> saved = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepo, times(2)).save(saved.capture());
        NotificationEntity staffAlert = saved.getAllValues().stream()
                .filter(n -> "AFFILIATE_JOIN".equals(n.getEventType())).findFirst().orElseThrow();
        assertThat(staffAlert.getUser()).isSameAs(admin);
        assertThat(staffAlert.getBody()).contains("Ana Gómez Ruiz").contains("ana@example.com").contains("País: ES")
                .contains("Empresa: ACME").contains(USER_ID.toString());
        Map<String, Object> payload = staffAlert.getPayload();
        assertThat(payload).containsEntry("name", "Ana Gómez Ruiz").containsEntry("email", "ana@example.com")
                .containsEntry("country", "ES").containsEntry("company", "ACME");
    }

    @Test
    @DisplayName("el aviso de nuevo afiliado NO se envía a los clientes, solo al staff")
    void elAvisoDeNuevoAfiliadoNoVaALosClientes() {
        UserEntity owner = customer();
        UserEntity otherCustomer = new UserEntity();
        otherCustomer.setId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        otherCustomer.setEmail("otro@example.com");
        otherCustomer.setRole(UserRole.USER);
        AffiliateEntity existing = existingAffiliate(owner);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(owner));
        when(userRepository.findAll()).thenReturn(List.of(owner, otherCustomer));
        when(affiliateRepo.findByUser_Id(USER_ID)).thenReturn(Optional.of(existing));
        when(codeRepo.findByAffiliateIdOrderByCreatedAtAsc(AFFILIATE_ID))
                .thenReturn(List.of(AffiliateReferralCodeEntity.builder().affiliate(existing).code("ref-1").build()));

        service.joinProgram(USER_ID);

        // Solo la bienvenida al propio afiliado: ningún cliente recibe la alerta interna.
        ArgumentCaptor<NotificationEntity> saved = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepo, times(1)).save(saved.capture());
        // Unirse ahora es SOLICITAR (queda pendiente de aprobación del admin): el evento es AFFILIATE_APPLIED.
        assertThat(saved.getValue().getEventType()).isEqualTo("AFFILIATE_APPLIED");
    }

    @Test
    @DisplayName("si falla el envío de la notificación, el alta en el programa se completa igual")
    void unFalloAlNotificarNoTumbaElAlta() {
        UserEntity owner = customer();
        AffiliateEntity existing = existingAffiliate(owner);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(owner));
        when(affiliateRepo.findByUser_Id(USER_ID)).thenReturn(Optional.of(existing));
        when(codeRepo.findByAffiliateIdOrderByCreatedAtAsc(AFFILIATE_ID))
                .thenReturn(List.of(AffiliateReferralCodeEntity.builder().affiliate(existing).code("ref-1").build()));
        when(notificationRepo.save(any())).thenThrow(new IllegalStateException("bandeja caída"));

        AffiliateEntity joined = service.joinProgram(USER_ID);

        assertThat(joined.getAcceptedTermsAt()).isNotNull();
    }

    @Test
    @DisplayName("la notificación del afiliado sale marcada como marketing y con enlace a su panel")
    void laNotificacionDelAfiliadoLlevaEnlaceASuPanel() {
        UserEntity owner = customer();
        owner.setLanguage("pt");
        AffiliateEntity existing = existingAffiliate(owner);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(owner));
        when(affiliateRepo.findByUser_Id(USER_ID)).thenReturn(Optional.of(existing));
        when(codeRepo.findByAffiliateIdOrderByCreatedAtAsc(AFFILIATE_ID))
                .thenReturn(List.of(AffiliateReferralCodeEntity.builder().affiliate(existing).code("ref-1").build()));

        service.joinProgram(USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> extra = ArgumentCaptor.forClass(Map.class);
        verify(notificationsPublisher).dispatch(eq("AFFILIATE_APPLIED"), eq(USER_ID), eq("ana@example.com"),
                extra.capture(), eq("pt"));
        assertThat(extra.getValue()).containsEntry("marketing", true).containsEntry("ctaUrl", "/affiliate");
    }

    @Test
    @DisplayName("sin idioma en el perfil, el correo del afiliado sale en español")
    void sinIdiomaEnElPerfilElCorreoSaleEnEspanol() {
        UserEntity owner = customer();
        owner.setLanguage(null);
        AffiliateEntity existing = existingAffiliate(owner);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(owner));
        when(affiliateRepo.findByUser_Id(USER_ID)).thenReturn(Optional.of(existing));
        when(codeRepo.findByAffiliateIdOrderByCreatedAtAsc(AFFILIATE_ID))
                .thenReturn(List.of(AffiliateReferralCodeEntity.builder().affiliate(existing).code("ref-1").build()));

        service.joinProgram(USER_ID);

        verify(notificationsPublisher).dispatch(anyString(), eq(USER_ID), anyString(), any(), eq("es"));
    }

    /* ===================== códigos ===================== */

    @Test
    @DisplayName("no se pueden crear códigos para un afiliado que no existe")
    void anadirCodigoAUnAfiliadoInexistenteFalla() {
        when(affiliateRepo.findById(AFFILIATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addCode(AFFILIATE_ID, "Instagram")).isInstanceOf(NotFoundException.class);
        verify(codeRepo, never()).save(any());
    }

    @Test
    @DisplayName("un código sin etiqueta se guarda como 'Link' y activo")
    void unCodigoSinEtiquetaSeLlamaLink() {
        when(affiliateRepo.findById(AFFILIATE_ID)).thenReturn(Optional.of(existingAffiliate(customer())));

        AffiliateReferralCodeEntity created = service.addCode(AFFILIATE_ID, null);

        assertThat(created.getLabel()).isEqualTo("Link");
        assertThat(created.isActive()).isTrue();
        assertThat(created.getCode()).startsWith("anatorre-");
    }

    @Test
    @DisplayName("desactivar un código que no existe falla en vez de crearlo")
    void desactivarUnCodigoInexistenteFalla() {
        UUID codeId = UUID.randomUUID();
        when(codeRepo.findById(codeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setCodeActive(USER_ID, codeId, false)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("desactivar un código propio lo deja inactivo y lo persiste")
    void desactivarUnCodigoPropioLoPersiste() {
        UUID codeId = UUID.randomUUID();
        AffiliateEntity mine = existingAffiliate(customer());
        AffiliateReferralCodeEntity c = AffiliateReferralCodeEntity.builder().affiliate(mine).code("ref-1").active(true)
                .build();
        when(codeRepo.findById(codeId)).thenReturn(Optional.of(c));
        when(affiliateRepo.findByUser_Id(USER_ID)).thenReturn(Optional.of(mine));

        AffiliateReferralCodeEntity updated = service.setCodeActive(USER_ID, codeId, false);

        assertThat(updated.isActive()).isFalse();
        verify(codeRepo).save(c);
    }

    /* ===================== estado del afiliado ===================== */

    @ParameterizedTest
    @ValueSource(strings = {"BORRADO", "", "activo"})
    @DisplayName("un estado de afiliado desconocido se rechaza")
    void unEstadoDeAfiliadoDesconocidoSeRechaza(String status) {
        assertThatThrownBy(() -> service.setAffiliateStatus(AFFILIATE_ID, status))
                .isInstanceOf(BusinessException.class);
        verify(affiliateRepo, never()).save(any());
    }

    @Test
    @DisplayName("un estado nulo se rechaza igual que uno inválido")
    void unEstadoNuloSeRechaza() {
        assertThatThrownBy(() -> service.setAffiliateStatus(AFFILIATE_ID, null)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE,true", "PENDING,false", "SUSPENDED,false"})
    @DisplayName("solo el estado ACTIVE deja al afiliado operativo")
    void soloElEstadoActivoDejaAlAfiliadoOperativo(String requested, boolean expectedActive) {
        AffiliateEntity a = existingAffiliate(customer());
        when(affiliateRepo.findById(AFFILIATE_ID)).thenReturn(Optional.of(a));

        AffiliateEntity saved = service.setAffiliateStatus(AFFILIATE_ID, requested);

        assertThat(saved.getStatus()).isEqualTo(requested);
        assertThat(saved.isActive()).isEqualTo(expectedActive);
        // El índice del admin debe reflejar el cambio o el listado seguiría mostrando el estado viejo.
        verify(affiliateIndexer).indexAffiliate(saved);
    }

    @Test
    @DisplayName("el estado admite espacios y minúsculas (se normaliza antes de validar)")
    void elEstadoSeNormalizaAntesDeValidar() {
        AffiliateEntity a = existingAffiliate(customer());
        when(affiliateRepo.findById(AFFILIATE_ID)).thenReturn(Optional.of(a));

        AffiliateEntity saved = service.setAffiliateStatus(AFFILIATE_ID, "  suspended  ");

        assertThat(saved.getStatus()).isEqualTo("SUSPENDED");
        assertThat(saved.isActive()).isFalse();
    }

    @Test
    @DisplayName("no se puede cambiar el estado de un afiliado inexistente")
    void noSePuedeCambiarElEstadoDeUnAfiliadoInexistente() {
        when(affiliateRepo.findById(AFFILIATE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setAffiliateStatus(AFFILIATE_ID, "SUSPENDED"))
                .isInstanceOf(NotFoundException.class);
    }

    /* ===================== configuración del programa ===================== */

    @Test
    @DisplayName("sin fila guardada, el programa arranca con los valores por defecto")
    void sinFilaGuardadaSeUsanLosValoresPorDefecto() {
        when(configRepo.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        AffiliateProgramConfigEntity cfg = service.config();

        assertThat(cfg.getDefaultPercent()).isEqualByComparingTo("10.000");
        assertThat(cfg.getAttributionWindowDays()).isEqualTo(30);
        assertThat(cfg.getReturnPeriodDays()).isEqualTo(14);
        assertThat(cfg.getMinPayoutCents()).isEqualTo(5000);
        assertThat(cfg.getCurrency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("actualizar la configuración solo cambia los campos que llegan con valor")
    void actualizarLaConfiguracionSoloCambiaLoQueLlega() {
        AffiliateProgramConfigEntity stored = storedConfig();
        when(configRepo.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));

        AffiliateProgramConfigEntity updated = service.updateConfig(new BigDecimal("12.500"), null, null, null, null,
                20000L);

        assertThat(updated.getDefaultPercent()).isEqualByComparingTo("12.500");
        assertThat(updated.getMaxCommissionPeriodCents()).isEqualTo(20000L);
        // Los nulos NO son "borrar": se conserva lo configurado.
        assertThat(updated.getAttributionWindowDays()).isEqualTo(30);
        assertThat(updated.getReturnPeriodDays()).isEqualTo(14);
        assertThat(updated.getMinPayoutCents()).isEqualTo(5000);
        assertThat(updated.getCurrency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("una divisa en blanco no borra la divisa de liquidación")
    void unaDivisaEnBlancoNoBorraLaDivisa() {
        AffiliateProgramConfigEntity stored = storedConfig();
        when(configRepo.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(stored));

        AffiliateProgramConfigEntity updated = service.updateConfig(null, null, null, null, "   ", null);

        assertThat(updated.getCurrency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("el descuento del comprador referido está fijado en el 10 %")
    void elDescuentoDelCompradorReferidoEsElDiezPorCiento() {
        assertThat(service.referralDiscountPercent()).isEqualTo(10);
    }
}
