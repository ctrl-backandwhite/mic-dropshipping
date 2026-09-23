package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.LegalDocumentService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.LegalDocumentEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Edición de los textos legales desde el panel.
 *
 * <p>Guardar y publicar son acciones DISTINTAS a propósito. Guardar es trabajo en curso: quien redacta
 * necesita dejarlo a medias sin que salga un correo a toda la base de usuarios en cada pulsación.
 * Publicar sube la versión, hace visible el texto y avisa — y eso último no es opcional ni un paso que se
 * pueda olvidar: cambiar las condiciones sin decírselo al usuario lo deja vinculado por algo que no vio.
 */
@RestController
@RequestMapping("/api/admin/legal")
@RequiredArgsConstructor
@Tag(name = "Admin · Legal")
@PreAuthorize("hasRole('ADMIN')")
public class AdminLegalController {

    private final LegalDocumentService service;

    /** Cuerpo de guardado. El contenido viaja como JSON en texto, con la forma que consume el escaparate. */
    public record GuardarRequest(@NotBlank @Size(max = 200) String title, @NotBlank String body) {
    }

    /**
     * La versión es una fecha. Se valida el formato porque es lo que se guarda como constancia de qué
     * aceptó cada usuario: una versión con un valor arbitrario haría ilegible ese registro el día que
     * haya que acreditarlo.
     */
    public record PublicarRequest(
            @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "version must be YYYY-MM-DD") String version) {
    }

    @Operation(summary = "Todos los documentos legales, borradores incluidos")
    @GetMapping
    public List<Map<String, Object>> list() {
        return service.todos().stream().map(AdminLegalController::vista).toList();
    }

    @Operation(summary = "Un documento en un idioma, para editarlo")
    @GetMapping("/{docType}/{lang}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String docType, @PathVariable String lang) {
        if (!service.tipoValido(docType)) {
            return ResponseEntity.notFound().build();
        }
        return service.paraEditar(docType, lang).map(d -> ResponseEntity.ok(vista(d)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Guardar el contenido de un documento (no publica ni avisa)")
    @PutMapping("/{docType}/{lang}")
    public ResponseEntity<Map<String, Object>> save(@PathVariable String docType, @PathVariable String lang,
            @RequestBody GuardarRequest req, Authentication auth) {
        if (!service.tipoValido(docType)) {
            return ResponseEntity.notFound().build();
        }
        String quien = auth != null ? auth.getName() : "admin";
        return ResponseEntity.ok(vista(service.guardar(docType, lang, req.title(), req.body(), quien)));
    }

    @Operation(summary = "Publicar TODOS los documentos con una versión nueva y avisar a los usuarios")
    @PostMapping("/publish")
    public ResponseEntity<Map<String, Object>> publish(@RequestBody PublicarRequest req, Authentication auth) {
        String quien = auth != null ? auth.getName() : "admin";
        int avisados = service.publicar(req.version(), quien);
        // Se devuelve a cuántos se avisó, no cuántos documentos se publicaron: lo segundo es siempre el
        // total y no dice nada; lo primero es lo que el admin quiere confirmar al pulsar el botón.
        return ResponseEntity.ok(Map.of("version", req.version(), "notified", avisados));
    }

    private static Map<String, Object> vista(LegalDocumentEntity d) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("docType", d.getDocType());
        m.put("lang", d.getLang());
        m.put("title", d.getTitle());
        m.put("body", d.getBody());
        m.put("version", d.getVersion());
        m.put("published", d.isPublished());
        m.put("updatedAt", d.getUpdatedAt().toString());
        // El editor necesita saber si hay cambios sin publicar y, si los hay, editar sobre ellos y no
        // sobre lo publicado — o el segundo guardado perdería el primero.
        m.put("hasDraft", d.getDraftBody() != null);
        m.put("draftTitle", d.getDraftTitle());
        m.put("draftBody", d.getDraftBody());
        return m;
    }
}
