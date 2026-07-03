package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.ContactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Formulario público "Contáctanos": crea una notificación en la bandeja de los administradores. */
@Tag(name = "Contact", description = "Formulario de contacto público")
@RestController
@RequestMapping("/api/contact")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    @Operation(summary = "Enviar una solicitud de contacto (notifica a los administradores)")
    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, String> body) {
        contactService.submit(body.get("name"), body.get("email"), body.get("subject"), body.get("message"));
        return ResponseEntity.ok(Map.of("received", true));
    }
}
