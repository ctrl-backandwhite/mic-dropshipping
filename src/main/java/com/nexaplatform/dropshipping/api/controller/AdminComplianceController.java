package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.EuComplianceService;
import com.nexaplatform.dropshipping.application.service.EuComplianceService.ResponsiblePersonView;
import com.nexaplatform.dropshipping.application.service.EuComplianceService.SafetyWarningView;
import com.nexaplatform.dropshipping.domain.enums.EuOperatorRole;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductComplianceRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductComplianceRepository.ProductoSinFabricante;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Configuración del cumplimiento de la UE desde el panel: quién es el operador económico establecido en la
 * Unión (art. 16 del Reglamento (UE) 2023/988) y en qué figura del art. 4.2 del Reglamento (UE) 2019/1020
 * actúa. La autorización la da {@code BffSecurityConfig}, que exige ADMIN en todo {@code /api/admin/**}.
 */
@Tag(name = "Admin Compliance")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/compliance")
public class AdminComplianceController {

    private final EuComplianceService complianceService;
    private final ProductComplianceRepository productComplianceRepository;

    /**
     * Datos del operador económico.
     *
     * <p>El país se valida como ISO 3166-1 alfa-2 y la figura contra {@link EuOperatorRole}; el resto de
     * comprobaciones de fondo —si la dirección está completa— las hace el servicio y viajan en
     * {@code complete}, porque un dato incompleto debe poder guardarse como borrador sin publicarse.
     */
    public record ResponsiblePersonDtoIn(@NotNull Boolean enabled, @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 300) String addressLine, @Size(max = 20) String postalCode,
            @NotBlank @Size(max = 120) String city, @Size(max = 120) String region,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$", message = "country must be an ISO 3166-1 alpha-2 code") String country,
            @NotBlank @Email @Size(max = 200) String email, @Size(max = 40) String phone, @NotBlank String role) {
    }

    /** Una figura del art. 4.2, con su etiqueta ya traducida, para poblar el desplegable del panel. */
    public record OperatorRoleView(String code, String label) {
    }

    @Operation(summary = "Get the EU responsible economic operator (admin view, includes incomplete data)")
    @GetMapping("/responsible-person")
    public ResponseEntity<ResponsiblePersonView> get(@RequestParam(defaultValue = "es") String lang) {
        return complianceService.responsible(lang).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "Create or update the EU responsible economic operator")
    @PutMapping("/responsible-person")
    public ResponseEntity<ResponsiblePersonView> update(@Valid @RequestBody ResponsiblePersonDtoIn req,
            @RequestParam(defaultValue = "es") String lang) {
        ResponsiblePersonView datos = new ResponsiblePersonView(req.name(), req.addressLine(), req.postalCode(),
                req.city(), req.region(), req.country(), req.email(), req.phone(), req.role(), null,
                Boolean.TRUE.equals(req.enabled()), false);
        return ResponseEntity.ok(complianceService.updateResponsible(datos, SecurityUtils.currentSubject(), lang));
    }

    @Operation(summary = "List the four economic operator roles of Reg. (EU) 2019/1020 art. 4(2)")
    @GetMapping("/operator-roles")
    public ResponseEntity<List<OperatorRoleView>> roles(@RequestParam(defaultValue = "es") String lang) {
        return ResponseEntity.ok(Arrays.stream(EuOperatorRole.values())
                .map(r -> new OperatorRoleView(r.name(), r.label(lang))).toList());
    }

    /**
     * Estado de cumplimiento del catálogo.
     *
     * @param responsiblePersonReady si hay operador económico publicable (habilitado y completo)
     * @param activeProducts         referencias activas, para leer el hueco como proporción
     * @param missingManufacturer    activas a las que les falta algún dato del fabricante (art. 19.a)
     */
    public record ComplianceStatusView(boolean responsiblePersonReady, long activeProducts, long missingManufacturer) {
    }

    @Operation(summary = "Catalog compliance status: responsible operator + products missing manufacturer")
    @GetMapping("/status")
    public ResponseEntity<ComplianceStatusView> status(@RequestParam(defaultValue = "es") String lang) {
        return ResponseEntity.ok(new ComplianceStatusView(complianceService.publishedResponsible(lang).isPresent(),
                productComplianceRepository.contarActivas(), productComplianceRepository.contarActivasSinFabricante()));
    }

    /** Una referencia activa sin la identidad completa del fabricante, para listarla en el panel. */
    public record MissingManufacturerView(UUID id, String slug, String title, String manufacturerName,
            String manufacturerAddress, String manufacturerEmail) {
    }

    @Operation(summary = "Active products missing the manufacturer identity required by art. 19(a)")
    @GetMapping("/products/missing-manufacturer")
    public ResponseEntity<Page<MissingManufacturerView>> missingManufacturer(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "es") String lang) {
        Page<ProductoSinFabricante> encontrados = productComplianceRepository.buscarActivasSinFabricante(lang,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200)));
        return ResponseEntity.ok(encontrados.map(p -> new MissingManufacturerView(p.getId(), p.getSlug(), p.getTitle(),
                p.getManufacturerName(), p.getManufacturerAddress(), p.getManufacturerEmail())));
    }

    /** Alta o corrección de una advertencia de seguridad de una categoría (art. 19.d). */
    public record SafetyWarningDtoIn(
            @NotBlank @Size(max = 60) @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "code must be alphanumeric with underscores") String code,
            Integer position, Boolean active, @NotNull Map<String, String> texts) {
    }

    @Operation(summary = "Safety warnings declared on a category (not inherited)")
    @GetMapping("/categories/{categoryId}/warnings")
    public ResponseEntity<List<SafetyWarningView>> warnings(@PathVariable UUID categoryId) {
        return ResponseEntity.ok(complianceService.warningsOfCategory(categoryId));
    }

    @Operation(summary = "Create or update a safety warning on a category (upsert by code)")
    @PutMapping("/categories/{categoryId}/warnings")
    public ResponseEntity<SafetyWarningView> upsertWarning(@PathVariable UUID categoryId,
            @Valid @RequestBody SafetyWarningDtoIn req) {
        return ResponseEntity.ok(complianceService.upsertWarning(categoryId, req.code(), req.position(), req.active(),
                req.texts(), SecurityUtils.currentSubject()));
    }

    @Operation(summary = "Delete a safety warning")
    @DeleteMapping("/warnings/{warningId}")
    public ResponseEntity<Void> deleteWarning(@PathVariable UUID warningId) {
        complianceService.deleteWarning(warningId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Advertencias que REALMENTE verá el comprador en una categoría, ya heredadas de sus ancestros y en un
     * idioma. Existe aparte del listado de arriba porque son cosas distintas: una es lo declarado aquí y la
     * otra lo que se publica, que es lo que hay que revisar antes de dar una categoría por cubierta.
     */
    @Operation(summary = "Effective (inherited) safety warnings a shopper sees for a category")
    @GetMapping("/categories/{categoryId}/warnings/effective")
    public ResponseEntity<List<String>> effectiveWarnings(@PathVariable UUID categoryId,
            @RequestParam(defaultValue = "es") String lang) {
        return ResponseEntity.ok(complianceService.safetyWarnings(categoryId, lang));
    }

}
