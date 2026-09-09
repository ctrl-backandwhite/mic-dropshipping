package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeDevicesApi;
import com.nexaplatform.dropshipping.api.dto.in.DeviceRegistrationDtoIn;
import com.nexaplatform.dropshipping.domain.repository.UserDeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Alta y baja del dispositivo que recibe los avisos.
 *
 * <p>La baja solo alcanza a los dispositivos de quien la pide: sin ese filtro, cualquiera con una
 * sesión podría dejar sin avisos a otra persona con solo conocer su token.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeDevicesController implements MeDevicesApi {

    private final UserDeviceRepository devices;

    @Override
    public ResponseEntity<Void> register(Authentication auth, DeviceRegistrationDtoIn body) {
        devices.registra(UUID.fromString(auth.getName()), body.getToken().trim(), body.getPlatform());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> unregister(Authentication auth, String token) {
        devices.olvida(UUID.fromString(auth.getName()), token);
        return ResponseEntity.noContent().build();
    }
}
