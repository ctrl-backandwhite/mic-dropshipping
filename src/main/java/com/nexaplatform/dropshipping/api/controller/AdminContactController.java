package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.in.ContactReplyDtoIn;
import com.nexaplatform.dropshipping.application.service.ContactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Panel admin: responder por email a una solicitud del formulario de contacto. Requiere rol ADMIN. */
@Tag(name = "Admin Contact", description = "Respuestas del administrador a solicitudes de contacto")
@RestController
@RequestMapping("/api/admin/contact")
@RequiredArgsConstructor
public class AdminContactController {

    private final ContactService contactService;

    @Operation(summary = "Responder por email a una solicitud de contacto")
    @PostMapping("/reply")
    public ResponseEntity<Map<String, Object>> reply(@Valid @RequestBody ContactReplyDtoIn body) {
        contactService.replyTo(body.getEmail(), body.getSubject(), body.getMessage());
        return ResponseEntity.ok(Map.of("sent", true));
    }
}
