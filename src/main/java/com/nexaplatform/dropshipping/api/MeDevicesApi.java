package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.DeviceRegistrationDtoIn;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Dispositivos de la persona autenticada para recibir avisos del sistema operativo.
 *
 * <p>El token lo emite el servicio de avisos en el propio dispositivo; aquí solo se guarda para
 * saber a dónde mandar. No identifica a nadie por sí mismo y se retira al cerrar sesión.
 */
@Tag(name = "Me · dispositivos")
public interface MeDevicesApi {

    @Operation(summary = "Registra —o actualiza— el dispositivo actual para recibir avisos")
    @PostMapping("/devices")
    ResponseEntity<Void> register(Authentication auth, @Valid @RequestBody DeviceRegistrationDtoIn body);

    @Operation(summary = "Da de baja un dispositivo; se llama al cerrar sesión")
    @DeleteMapping("/devices/{token}")
    ResponseEntity<Void> unregister(Authentication auth, @PathVariable String token);
}
