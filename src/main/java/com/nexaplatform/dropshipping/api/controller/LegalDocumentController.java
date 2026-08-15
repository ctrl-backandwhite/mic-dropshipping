package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.LegalDocumentService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.LegalDocumentEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Los textos legales, para el escaparate. Público: son documentos que cualquiera debe poder leer antes de
 * registrarse — exigir cuenta para leer las condiciones que uno va a aceptar no tendría ningún sentido.
 */
@RestController
@RequestMapping("/api/legal")
@RequiredArgsConstructor
@Tag(name = "Legal")
public class LegalDocumentController {

    private final LegalDocumentService service;

    @Operation(summary = "Documento legal publicado, en el idioma pedido (con reserva al español)")
    @GetMapping("/{docType}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String docType,
            @RequestParam(defaultValue = "es") String lang) {
        if (!service.tipoValido(docType)) {
            return ResponseEntity.notFound().build();
        }
        return service.publicado(docType, lang)
                .map(d -> ResponseEntity.ok()
                        // Cinco minutos: un texto legal cambia muy de tarde en tarde, pero cuando cambia
                        // conviene que llegue pronto — el usuario acaba de recibir un correo diciéndoselo.
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                        .body(cuerpo(d)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Map<String, Object> cuerpo(LegalDocumentEntity d) {
        return Map.of("docType", d.getDocType(), "lang", d.getLang(), "title", d.getTitle(),
                "version", d.getVersion(), "body", d.getBody());
    }
}
