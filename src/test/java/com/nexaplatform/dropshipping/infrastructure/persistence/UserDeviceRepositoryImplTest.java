package com.nexaplatform.dropshipping.infrastructure.persistence;

import com.nexaplatform.dropshipping.domain.model.UserDevice;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserDeviceEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserDeviceJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl.UserDeviceRepositoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El alta de dispositivos.
 *
 * <p>La identidad es el TOKEN y no la persona: alguien puede tener varios dispositivos y un
 * dispositivo puede cambiar de manos.
 */
class UserDeviceRepositoryImplTest {

    private static final UUID ANA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LUIS = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TOKEN = "ExponentPushToken[abc]";

    private UserDeviceJpaRepositoryAdapter jpa;
    private UserDeviceRepositoryImpl repositorio;

    @BeforeEach
    void setUp() {
        jpa = mock(UserDeviceJpaRepositoryAdapter.class);
        when(jpa.save(any(UserDeviceEntity.class))).thenAnswer(i -> i.getArgument(0));
        repositorio = new UserDeviceRepositoryImpl(jpa);
    }

    private static UserDeviceEntity fila(UUID dueno, String token) {
        UserEntity user = new UserEntity();
        user.setId(dueno);
        return UserDeviceEntity.builder().id(UUID.randomUUID()).user(user).pushToken(token)
                .plataforma("android").creadoEl(Instant.now()).ultimaSenal(Instant.now()).build();
    }

    @Test
    void daDeAltaUnDispositivoNuevo() {
        when(jpa.findByPushToken(TOKEN)).thenReturn(Optional.empty());

        UserDevice guardado = repositorio.registra(ANA, TOKEN, "ios");

        assertThat(guardado.getPushToken()).isEqualTo(TOKEN);
        assertThat(guardado.getUserId()).isEqualTo(ANA);
        assertThat(guardado.getPlataforma()).isEqualTo("ios");
    }

    /**
     * Un teléfono que cambia de manos: si la fila vieja se quedara con el dueño anterior, quien lo
     * compró recibiría los avisos —con su título y su cuerpo— de una cuenta que no es suya.
     */
    @Test
    void reasignaElDispositivoQueCambiaDeDueno() {
        when(jpa.findByPushToken(TOKEN)).thenReturn(Optional.of(fila(ANA, TOKEN)));

        repositorio.registra(LUIS, TOKEN, "android");

        ArgumentCaptor<UserDeviceEntity> captor = ArgumentCaptor.forClass(UserDeviceEntity.class);
        verify(jpa).save(captor.capture());
        assertThat(captor.getValue().getUser().getId()).isEqualTo(LUIS);
    }

    /** Sin plataforma se guarda «UNKNOWN»: la columna no admite nulos y el dato es informativo. */
    @Test
    void aguantaUnAltaSinPlataforma() {
        when(jpa.findByPushToken(TOKEN)).thenReturn(Optional.empty());

        UserDevice guardado = repositorio.registra(ANA, TOKEN, "  ");

        assertThat(guardado.getPlataforma()).isEqualTo("UNKNOWN");
    }

    /**
     * La baja lleva el usuario además del token: sin ese filtro, cualquiera con una sesión podría
     * dejar sin avisos a otra persona con solo conocer su token.
     */
    @Test
    void laBajaSoloAlcanzaAQuienLaPide() {
        repositorio.olvida(ANA, TOKEN);

        verify(jpa).deleteByUser_IdAndPushToken(ANA, TOKEN);
    }

    @Test
    void listaLosDispositivosDeUnaPersona() {
        when(jpa.findByUser_Id(ANA)).thenReturn(List.of(fila(ANA, TOKEN), fila(ANA, "otro")));

        assertThat(repositorio.deUsuario(ANA)).hasSize(2)
                .extracting(UserDevice::getPushToken).containsExactly(TOKEN, "otro");
    }

    @Test
    void retiraUnTokenInservible() {
        repositorio.retiraToken(TOKEN);

        verify(jpa).deleteByPushToken(TOKEN);
    }
}
