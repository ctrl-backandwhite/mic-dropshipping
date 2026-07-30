package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.MentorProfileEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.MentorProfileJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CRUD de mentores del panel de administración.
 *
 * <p>Un mentor SIEMPRE cuelga de un usuario existente (columna obligatoria) y siempre tiene titular: son
 * las dos condiciones que, si se saltan, dejan filas que el escaparate no puede pintar. El resto de
 * campos son opcionales y el alta debe distinguir "no lo mandes" de "bórralo".
 */
class Cov08AdminMentorsControllerTest {

    private MentorProfileJpaRepositoryAdapter repository;
    private UserRepository userRepository;
    private AdminMentorsController controller;

    @BeforeEach
    void setUp() {
        repository = mock(MentorProfileJpaRepositoryAdapter.class);
        userRepository = mock(UserRepository.class);
        controller = new AdminMentorsController(repository, userRepository);
        when(repository.save(any(MentorProfileEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static UserEntity usuario(String email, String displayName) {
        UserEntity u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setDisplayName(displayName);
        return u;
    }

    private Map<String, Object> altaValida() {
        Map<String, Object> body = new HashMap<>();
        body.put("userEmail", " mentor@nx.local ");
        body.put("headline", "Experta en dropshipping");
        return body;
    }

    private void usuarioExiste(String email, UserEntity user) {
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
    }

    // ─────────────────────── alta ───────────────────────

    @Test
    void sinEmailDeUsuarioNoSePuedeCrearUnMentor() {
        Map<String, Object> sinEmail = new HashMap<>();
        sinEmail.put("headline", "Experta");

        assertThatThrownBy(() -> controller.create(sinEmail)).isInstanceOf(BusinessException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void unEmailEnBlancoTampocoVale() {
        Map<String, Object> body = new HashMap<>();
        body.put("userEmail", "   ");

        assertThatThrownBy(() -> controller.create(body)).isInstanceOf(BusinessException.class);
    }

    @Test
    void noSePuedeCrearUnMentorDeUnUsuarioQueNoExiste() {
        // La FK es obligatoria: un mentor huérfano no se puede guardar y el error debe salir aquí, no
        // como violación de integridad en el commit.
        Map<String, Object> body = altaValida();
        when(userRepository.findByEmail("mentor@nx.local")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.create(body)).isInstanceOf(BusinessException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void unMentorSinTitularNoSeGuarda() {
        Map<String, Object> body = new HashMap<>();
        body.put("userEmail", "mentor@nx.local");
        usuarioExiste("mentor@nx.local", usuario("mentor@nx.local", "Marta"));

        assertThatThrownBy(() -> controller.create(body)).isInstanceOf(BusinessException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void elAltaNormalizaListasTarifaYEstado() {
        Map<String, Object> body = altaValida();
        body.put("expertise", List.of(" SEO ", "", "  ", "Ads"));
        body.put("languages", List.of("es", " en "));
        body.put("hourlyRateUsd", 49.99);
        body.put("active", "false");
        usuarioExiste("mentor@nx.local", usuario("mentor@nx.local", "Marta"));

        Map<String, Object> creado = controller.create(body).getBody();

        assertThat(creado).containsEntry("expertise", List.of("SEO", "Ads"))
                .containsEntry("languages", List.of("es", "en"))
                .containsEntry("hourlyRateUsd", 49.99)
                .containsEntry("active", false)
                .containsEntry("name", "Marta");
    }

    @Test
    void laTarifaTambienSePuedeMandarYaEnCentimos() {
        // El panel manda euros/dólares con decimales; las importaciones antiguas mandan céntimos enteros.
        Map<String, Object> body = altaValida();
        body.put("hourlyRateUsdCents", 3500);
        usuarioExiste("mentor@nx.local", usuario("mentor@nx.local", "Marta"));

        assertThat(controller.create(body).getBody()).containsEntry("hourlyRateUsd", 35.0);
    }

    // ─────────────────────── edición ───────────────────────

    @Test
    void editarUnMentorQueNoExisteDevuelveNoEncontrado() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        Map<String, Object> body = new HashMap<>();

        assertThatThrownBy(() -> controller.update(id, body)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void loQueNoViajaEnElCuerpoNoSeBorra() {
        // Una edición parcial (solo el titular) no puede vaciar la biografía ni la zona horaria.
        UUID id = UUID.randomUUID();
        MentorProfileEntity existente = new MentorProfileEntity();
        existente.setId(id);
        existente.setUser(usuario("mentor@nx.local", "Marta"));
        existente.setHeadline("Antiguo");
        existente.setBio("Biografía larga");
        existente.setTimezone("Europe/Madrid");
        when(repository.findById(id)).thenReturn(Optional.of(existente));
        Map<String, Object> body = new HashMap<>();
        body.put("headline", "Nuevo titular");

        Map<String, Object> actualizado = controller.update(id, body).getBody();

        assertThat(actualizado).containsEntry("headline", "Nuevo titular").containsEntry("bio", "Biografía larga")
                .containsEntry("timezone", "Europe/Madrid");
    }

    @Test
    void mandarElCampoEnBlancoSiLoBorra() {
        // Distinguir "ausente" de "vacío" es lo que permite al admin quitar una biografía.
        UUID id = UUID.randomUUID();
        MentorProfileEntity existente = new MentorProfileEntity();
        existente.setId(id);
        existente.setUser(usuario("mentor@nx.local", "Marta"));
        existente.setBio("Biografía larga");
        existente.setTimezone("Europe/Madrid");
        when(repository.findById(id)).thenReturn(Optional.of(existente));
        Map<String, Object> body = new HashMap<>();
        body.put("bio", "   ");
        body.put("timezone", null);

        Map<String, Object> actualizado = controller.update(id, body).getBody();

        assertThat(actualizado).containsEntry("bio", "").containsEntry("timezone", "");
        assertThat(existente.getBio()).isNull();
        assertThat(existente.getTimezone()).isNull();
    }

    // ─────────────────────── proyección al panel ───────────────────────

    @Test
    void sinNombreVisibleSeMuestraElEmailComoIdentificador() {
        MentorProfileEntity mentor = new MentorProfileEntity();
        mentor.setUser(usuario("mentor@nx.local", null));
        mentor.setHeadline("Experta");
        when(repository.findAll()).thenReturn(List.of(mentor));

        assertThat(controller.list().get(0)).containsEntry("name", "mentor@nx.local");
    }

    @Test
    void unPerfilHuerfanoSaleConCamposVaciosYNoConNulos() {
        // El front pinta estos campos sin comprobar nulos; un mentor sin usuario (histórico) reventaría
        // el listado entero del panel.
        MentorProfileEntity huerfano = new MentorProfileEntity();
        when(repository.findAll()).thenReturn(List.of(huerfano));

        Map<String, Object> fila = controller.list().get(0);

        assertThat(fila).containsEntry("name", "").containsEntry("userEmail", null)
                .containsEntry("headline", "").containsEntry("bio", "").containsEntry("timezone", "")
                .containsEntry("expertise", List.of()).containsEntry("languages", List.of());
    }

    @Test
    void borrarUnMentorRespondeSinContenido() {
        UUID id = UUID.randomUUID();

        assertThat(controller.delete(id).getStatusCode().value()).isEqualTo(204);
        verify(repository).deleteById(id);
    }
}
