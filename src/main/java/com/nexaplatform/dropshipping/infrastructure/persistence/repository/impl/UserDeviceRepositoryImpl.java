package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.domain.model.UserDevice;
import com.nexaplatform.dropshipping.domain.repository.UserDeviceRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserDeviceEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserDeviceJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Adaptador JPA del puerto de dispositivos. */
@Repository
@RequiredArgsConstructor
public class UserDeviceRepositoryImpl implements UserDeviceRepository {

    private final UserDeviceJpaRepositoryAdapter jpa;

    @Override
    @Transactional
    public UserDevice registra(UUID userId, String pushToken, String plataforma) {
        Instant ahora = Instant.now();
        // Se busca por TOKEN y no por usuario: si ese dispositivo ya estaba —del mismo dueño o de
        // otro— la fila se reasigna. Insertar sin más chocaría con la clave única, y dejar la fila
        // vieja haría que el teléfono de quien lo vendió siguiera recibiendo los avisos anteriores.
        UserDeviceEntity fila = jpa.findByPushToken(pushToken).orElseGet(() -> UserDeviceEntity.builder()
                .id(UUID.randomUUID()).pushToken(pushToken).creadoEl(ahora).build());

        UserEntity propietario = new UserEntity();
        propietario.setId(userId);
        fila.setUser(propietario);
        fila.setPlataforma(plataforma == null || plataforma.isBlank() ? "UNKNOWN" : plataforma);
        fila.setUltimaSenal(ahora);
        if (fila.getCreadoEl() == null) {
            fila.setCreadoEl(ahora);
        }
        return aModelo(jpa.save(fila));
    }

    @Override
    @Transactional
    public void olvida(UUID userId, String pushToken) {
        // Solo se borra si el token es de quien lo pide: sin ese filtro, cualquiera con una sesión
        // podría dar de baja el dispositivo de otra persona sabiendo su token.
        jpa.deleteByUser_IdAndPushToken(userId, pushToken);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDevice> deUsuario(UUID userId) {
        return jpa.findByUser_Id(userId).stream().map(UserDeviceRepositoryImpl::aModelo).toList();
    }

    @Override
    @Transactional
    public void retiraToken(String pushToken) {
        jpa.deleteByPushToken(pushToken);
    }

    private static UserDevice aModelo(UserDeviceEntity e) {
        return UserDevice.builder().id(e.getId())
                .userId(e.getUser() != null ? e.getUser().getId() : null)
                .pushToken(e.getPushToken()).plataforma(e.getPlataforma())
                .creadoEl(e.getCreadoEl()).ultimaSenal(e.getUltimaSenal()).build();
    }
}
