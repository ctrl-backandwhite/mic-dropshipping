package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.PlatformNotification;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NotificationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.NotificationEntityMapper;
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

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adaptador de persistencia de la bandeja de notificaciones. Lo suyo es resolver el destinatario a
 * partir del {@code userId} plano en el alta y NO pisar canal ni contenido cuando el modelo llega sin
 * ellos (una actualización parcial no puede vaciar la notificación que ya está en la bandeja).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov05NotificationRepositoryImplTest {

    @Mock
    NotificationEntityMapper notificationEntityMapper;
    @Mock
    NotificationJpaRepositoryAdapter notificationJpaRepositoryAdapter;
    @Mock
    UserRepository userRepository;

    @InjectMocks
    NotificationRepositoryImpl repository;

    private static final UUID NOTIFICATION_ID = UUID.fromString("11111111-0000-0000-0000-00000000000a");
    private static final UUID USER_ID = UUID.fromString("22222222-0000-0000-0000-00000000000b");

    @BeforeEach
    void setUp() {
        when(notificationJpaRepositoryAdapter.save(any())).thenAnswer(i -> i.getArgument(0));
        when(notificationEntityMapper.toDomain(any())).thenReturn(PlatformNotification.builder().build());
    }

    private UserEntity user() {
        UserEntity u = new UserEntity();
        u.setId(USER_ID);
        u.setEmail("ana@example.com");
        return u;
    }

    private NotificationEntity captureSaved() {
        ArgumentCaptor<NotificationEntity> captor = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationJpaRepositoryAdapter).save(captor.capture());
        return captor.getValue();
    }

    /* ===================== alta ===================== */

    @Test
    @DisplayName("el alta resuelve el destinatario a partir de su id")
    void elAltaResuelveElDestinatario() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));

        repository.save(PlatformNotification.builder().userId(USER_ID).eventType("ORDER_PLACED").title("Pedido")
                .body("Tu pedido está en camino").channel("EMAIL").build());

        NotificationEntity saved = captureSaved();
        assertThat(saved.getUser().getId()).isEqualTo(USER_ID);
        assertThat(saved.getChannel()).isEqualTo("EMAIL");
    }

    @Test
    @DisplayName("no se puede dejar una notificación a un usuario que no existe")
    void noSePuedeNotificarAUnUsuarioInexistente() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        PlatformNotification model = PlatformNotification.builder().userId(USER_ID).title("x").build();

        assertThatThrownBy(() -> repository.save(model)).isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User");
        verify(notificationJpaRepositoryAdapter, never()).save(any());
    }

    @Test
    @DisplayName("el contenido estructurado se guarda cuando llega con la notificación")
    void elContenidoEstructuradoSeGuarda() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        Map<String, Object> payload = Map.of("orderNumber", "NX-1001");

        repository.save(PlatformNotification.builder().userId(USER_ID).title("Pedido").payload(payload).build());

        assertThat(captureSaved().getPayload()).containsEntry("orderNumber", "NX-1001");
    }

    /* ===================== actualización ===================== */

    @Test
    @DisplayName("actualizar una notificación que no existe falla en vez de crear otra")
    void actualizarUnaNotificacionInexistenteFalla() {
        when(notificationJpaRepositoryAdapter.findById(NOTIFICATION_ID)).thenReturn(Optional.empty());
        PlatformNotification model = PlatformNotification.builder().id(NOTIFICATION_ID).userId(USER_ID).build();

        assertThatThrownBy(() -> repository.update(model)).isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Notification");
    }

    @Test
    @DisplayName("al actualizar no se vuelve a buscar el destinatario ya enlazado")
    void alActualizarNoSeVuelveABuscarElDestinatario() {
        NotificationEntity managed = new NotificationEntity();
        managed.setId(NOTIFICATION_ID);
        managed.setUser(user());
        when(notificationJpaRepositoryAdapter.findById(NOTIFICATION_ID)).thenReturn(Optional.of(managed));

        repository.update(PlatformNotification.builder().id(NOTIFICATION_ID).userId(USER_ID).build());

        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("canal y contenido nulos NO borran los que ya tenía la notificación")
    void canalYContenidoNulosNoBorranLosExistentes() {
        NotificationEntity managed = new NotificationEntity();
        managed.setId(NOTIFICATION_ID);
        managed.setUser(user());
        managed.setChannel("EMAIL");
        managed.setPayload(new HashMap<>(Map.of("orderNumber", "NX-1001")));
        when(notificationJpaRepositoryAdapter.findById(NOTIFICATION_ID)).thenReturn(Optional.of(managed));

        // Marcar como leída manda un modelo parcial: si canal/contenido se pisaran, la fila se vaciaría.
        repository.update(PlatformNotification.builder().id(NOTIFICATION_ID).userId(USER_ID)
                .readAt(Instant.now()).build());

        assertThat(managed.getChannel()).isEqualTo("EMAIL");
        assertThat(managed.getPayload()).containsEntry("orderNumber", "NX-1001");
    }

    @Test
    @DisplayName("un canal nuevo sí sustituye al anterior")
    void unCanalNuevoSustituyeAlAnterior() {
        NotificationEntity managed = new NotificationEntity();
        managed.setId(NOTIFICATION_ID);
        managed.setUser(user());
        managed.setChannel("IN_APP");
        when(notificationJpaRepositoryAdapter.findById(NOTIFICATION_ID)).thenReturn(Optional.of(managed));

        repository.update(PlatformNotification.builder().id(NOTIFICATION_ID).userId(USER_ID).channel("EMAIL").build());

        assertThat(managed.getChannel()).isEqualTo("EMAIL");
    }

    /* ===================== consultas ===================== */

    @Test
    @DisplayName("la bandeja se lee de la más reciente a la más antigua")
    void laBandejaSeLeeDeLaMasRecienteALaMasAntigua() {
        List<NotificationEntity> rows = List.of(new NotificationEntity(), new NotificationEntity());
        when(notificationJpaRepositoryAdapter.findByUser_IdOrderByCreatedAtDesc(USER_ID)).thenReturn(rows);
        when(notificationEntityMapper.toDomainList(rows))
                .thenReturn(List.of(PlatformNotification.builder().build(), PlatformNotification.builder().build()));

        assertThat(repository.findByUserId(USER_ID)).hasSize(2);
        verify(notificationJpaRepositoryAdapter).findByUser_IdOrderByCreatedAtDesc(USER_ID);
    }

    @Test
    @DisplayName("el contador de no leídas excluye archivadas y papelera")
    void elContadorDeNoLeidasExcluyeArchivadasYPapelera() {
        when(notificationJpaRepositoryAdapter
                .countByUser_IdAndReadAtIsNullAndArchivedAtIsNullAndDeletedAtIsNull(USER_ID)).thenReturn(3L);

        // Si contara las archivadas/borradas, la campanita mostraría avisos que el usuario ya retiró.
        assertThat(repository.countUnreadByUserId(USER_ID)).isEqualTo(3L);
    }

    @Test
    @DisplayName("pedir una notificación inexistente devuelve nulo, no una excepción")
    void pedirUnaNotificacionInexistenteDevuelveNulo() {
        when(notificationJpaRepositoryAdapter.findById(NOTIFICATION_ID)).thenReturn(Optional.empty());

        assertThat(repository.getById(NOTIFICATION_ID)).isNull();
    }

    @Test
    @DisplayName("borrar y comprobar existencia delegan en el adaptador de JPA")
    void borrarYComprobarExistenciaDelegan() {
        when(notificationJpaRepositoryAdapter.existsById(NOTIFICATION_ID)).thenReturn(true);

        repository.delete(NOTIFICATION_ID);

        verify(notificationJpaRepositoryAdapter).deleteById(NOTIFICATION_ID);
        assertThat(repository.existsById(NOTIFICATION_ID)).isTrue();
    }
}
