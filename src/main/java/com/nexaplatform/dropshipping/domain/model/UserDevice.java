package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Un dispositivo al que se le pueden mandar avisos del sistema operativo.
 *
 * <p>La identidad es el TOKEN, no la persona: alguien puede tener varios dispositivos, y un
 * dispositivo puede cambiar de manos. Si el mismo token vuelve con otro usuario, la fila se reasigna
 * en vez de duplicarse; así el teléfono de quien lo vendió deja de recibir los avisos del anterior
 * dueño.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDevice {

    private UUID id;
    private UUID userId;
    private String pushToken;
    private String plataforma;
    private Instant creadoEl;
    private Instant ultimaSenal;
}
