package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.UserDevice;

import java.util.List;
import java.util.UUID;

/** Puerto de los dispositivos registrados para recibir avisos. */
public interface UserDeviceRepository {

    /**
     * Da de alta el dispositivo, o lo actualiza si ese token ya estaba.
     *
     * <p>Reasignar en vez de duplicar es lo que evita que un teléfono que cambió de manos siga
     * recibiendo los avisos del dueño anterior.
     */
    UserDevice registra(UUID userId, String pushToken, String plataforma);

    /** Olvida un dispositivo. Se llama al cerrar sesión: sin esto seguiría recibiendo avisos ajenos. */
    void olvida(UUID userId, String pushToken);

    /** Los dispositivos de una persona, para repartirle un aviso. */
    List<UserDevice> deUsuario(UUID userId);

    /** Retira un token que la pasarela de avisos ha declarado inservible. */
    void retiraToken(String pushToken);
}
