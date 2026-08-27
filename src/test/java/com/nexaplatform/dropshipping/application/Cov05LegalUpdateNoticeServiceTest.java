package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.LegalUpdateNoticeService;
import com.nexaplatform.dropshipping.infrastructure.email.EmailQueueService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El aviso de cambio en los textos legales.
 *
 * <p>Lo que se protege aquí es que el aviso salga UNA vez y llegue a TODOS. Los dos fallos posibles son
 * caros y silenciosos: repetirlo convierte cada reinicio del servicio en un correo masivo a la base entera
 * —y quema la credibilidad del aviso justo cuando más falta hace—, y dejar fuera a quien rechazó la
 * publicidad deja a esos usuarios vinculados por unas condiciones que nunca vieron.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov05LegalUpdateNoticeServiceTest {

    private static final String VERSION = "2026-08-15";

    @Mock
    private UserRepository userRepository;
    @Mock
    private EmailQueueService emailQueue;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private LegalUpdateNoticeService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "legalVersion", VERSION);
        ReflectionTestUtils.setField(service, "notifyOnChange", true);
        ReflectionTestUtils.setField(service, "storefrontBaseUrl", "https://nx036.com");
    }

    private static UserEntity usuario(String email, String idioma, boolean rechazaPublicidad) {
        UserEntity u = UserEntity.builder().email(email).language(idioma).active(true)
                .marketingOptOut(rechazaPublicidad).build();
        u.setId(UUID.randomUUID());
        return u;
    }

    /** Versión aún no avisada: la consulta de control devuelve 0. */
    private void versionNueva() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
    }

    @Test
    @DisplayName("una versión nueva se avisa a todas las cuentas activas")
    void avisaALasCuentasActivas() {
        versionNueva();
        when(userRepository.findByActiveTrueAndDeletedAtIsNull())
                .thenReturn(List.of(usuario("uno@test", "es", false), usuario("dos@test", "en", false)));

        service.avisarSiCambioLaVersion();

        verify(emailQueue, times(2)).enqueue(anyString(), anyString(), eq("emails/legal-update"), any());
    }

    @Test
    @DisplayName("también lo recibe quien tiene la publicidad desactivada: no es marketing")
    void alcanzaAQuienRechazoLaPublicidad() {
        // Este es el caso que motiva el método propio del repositorio. Con la audiencia de marketing, el
        // usuario que rechazó las campañas se quedaba sin enterarse de que cambian sus condiciones.
        versionNueva();
        when(userRepository.findByActiveTrueAndDeletedAtIsNull())
                .thenReturn(List.of(usuario("rechaza@test", "es", true)));

        service.avisarSiCambioLaVersion();

        verify(emailQueue).enqueue(eq("rechaza@test"), anyString(), eq("emails/legal-update"), any());
        verify(userRepository, never()).findByActiveTrueAndMarketingOptOutFalse();
    }

    @Test
    @DisplayName("una versión ya avisada no se repite en el siguiente arranque")
    void noRepiteLaMismaVersion() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);

        service.avisarSiCambioLaVersion();

        verify(userRepository, never()).findByActiveTrueAndDeletedAtIsNull();
        verify(emailQueue, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("la versión se marca como avisada ANTES de encolar, para que un fallo a mitad no repita el envío")
    void marcaAntesDeEnviar() {
        versionNueva();
        when(userRepository.findByActiveTrueAndDeletedAtIsNull()).thenReturn(List.of(usuario("uno@test", "es", false)));

        service.avisarSiCambioLaVersion();

        // Si se marcara al final, un fallo a mitad del barrido dejaría la versión sin registrar y el
        // siguiente arranque volvería a mandarlo TODO, incluidos los que ya lo recibieron.
        verify(jdbcTemplate).update(anyString(), eq(VERSION), eq(1));
    }

    @Test
    @DisplayName("el correo va en el idioma del usuario y lleva los dos documentos")
    @SuppressWarnings("unchecked")
    void correoEnSuIdiomaYConAmbosEnlaces() {
        versionNueva();
        when(userRepository.findByActiveTrueAndDeletedAtIsNull()).thenReturn(List.of(usuario("de@test", "de", false)));

        service.avisarSiCambioLaVersion();

        ArgumentCaptor<String> asunto = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(emailQueue).enqueue(eq("de@test"), asunto.capture(), anyString(), vars.capture());

        assertThat(asunto.getValue()).isEqualTo("Wir haben unsere AGB und Datenschutzerklärung aktualisiert");
        assertThat(vars.getValue())
                .containsEntry("privacyUrl", "https://nx036.com/legal/privacy")
                .containsEntry("termsUrl", "https://nx036.com/legal/terms")
                .containsEntry("version", VERSION);
    }

    @Test
    @DisplayName("una cuenta sin correo no rompe el barrido de las demás")
    void unaCuentaSinCorreoNoDetieneElResto() {
        versionNueva();
        UserEntity sinCorreo = usuario(null, "es", false);
        when(userRepository.findByActiveTrueAndDeletedAtIsNull())
                .thenReturn(List.of(sinCorreo, usuario("bueno@test", "es", false)));

        service.avisarSiCambioLaVersion();

        verify(emailQueue, times(1)).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("una cuenta ya borrada no recibe el aviso")
    void laCuentaBorradaNoRecibeNada() {
        versionNueva();
        UserEntity borrada = usuario("borrada@test", "es", false);
        borrada.setDeletedAt(Instant.now());
        when(userRepository.findByActiveTrueAndDeletedAtIsNull()).thenReturn(List.of(borrada));

        service.avisarSiCambioLaVersion();

        verify(emailQueue, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("con el aviso apagado no se consulta siquiera la base de usuarios")
    void apagadoNoHaceNada() {
        ReflectionTestUtils.setField(service, "notifyOnChange", false);

        service.avisarSiCambioLaVersion();

        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Integer.class), any(Object[].class));
        verify(emailQueue, never()).enqueue(anyString(), anyString(), anyString(), any());
    }
}
