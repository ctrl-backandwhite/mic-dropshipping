package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.WelcomeExamplesService;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Qué productos ilustran la guía de bienvenida.
 *
 * <p>Por defecto los elige el sistema: dos que compartan partida arancelaria y uno de otra, que es lo que
 * hace visible la regla —al sumar unidades del mismo el arancel no se mueve; al añadir el otro, sí—. Este
 * controlador existe para que el admin pueda destacar otros productos sin tocar código.
 *
 * <p>Al fijarlos a mano conviene respetar ese emparejamiento: con tres productos de partidas distintas la
 * guía sigue funcionando, pero deja de enseñar la mitad de lo que quiere enseñar.
 */
@Slf4j
@Tag(name = "Admin · Guía de bienvenida")
@RestController
@RequestMapping("/api/admin/catalog/welcome-examples")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminWelcomeExamplesController {

    private final WelcomeExamplesService service;

    /** Los que se están usando ahora, sean fijados o elegidos por el sistema. */
    @Operation(summary = "Productos que ilustran hoy la guía de bienvenida")
    @GetMapping
    public ResponseEntity<List<WelcomeExampleAdminView>> current() {
        return ResponseEntity.ok(service.examples().stream()
                .map(p -> new WelcomeExampleAdminView(p.getId(), p.getSlug(), p.getTitleZh(),
                        p.getHsCode(), service.dutyGroupOf(p)))
                .toList());
    }

    /** Fija los ejemplos. Con la lista vacía se devuelve el control a la elección automática. */
    @Operation(summary = "Fijar los productos de la guía (lista vacía = elección automática)")
    @PutMapping
    public ResponseEntity<Void> fijar(@RequestBody @Size(max = 3) List<UUID> productIds) {
        service.fijar(productIds, SecurityUtils.currentSubject());
        log.info("::> [WELCOME] ejemplos de la guía fijados: {}", productIds == null ? 0 : productIds.size());
        return ResponseEntity.noContent().build();
    }

    /**
     * Lo que el panel necesita para elegir con criterio: además del producto, su partida, para que se vea
     * cuáles la comparten.
     */
    public record WelcomeExampleAdminView(UUID id, String slug, String titleZh, String hsCode,
            String dutyGroup) {
    }
}
