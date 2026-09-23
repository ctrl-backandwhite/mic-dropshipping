package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminPricingApi;
import com.nexaplatform.dropshipping.api.dto.in.PriceRuleDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.PriceRuleDtoOut;
import com.nexaplatform.dropshipping.api.exception.ArgumentException;
import com.nexaplatform.dropshipping.api.exception.ErrorMessages;
import com.nexaplatform.dropshipping.api.mapper.PriceRuleDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.PriceRuleUseCase;
import com.nexaplatform.dropshipping.domain.model.PriceRule;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Admin Pricing rules controller. Pure implementation of {@link AdminPricingApi}:
 * injects the {@link PriceRuleDtoMapper} + {@link PriceRuleUseCase}; maps
 * DtoIn -> domain -> DtoOut; no business logic, no manual mapping.
 *
 * <p>DROP-630: the list response is enriched with a human-readable {@code scopeName}
 * (category/supplier/product/variant) so the "Alcance" column is traceable to a concrete
 * entity instead of just showing the scope level. This is a read-only presentation lookup.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/pricing/rules")
public class AdminPricingController implements AdminPricingApi {

    private final PriceRuleDtoMapper mapper;
    private final PriceRuleUseCase useCase;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public ResponseEntity<List<PriceRuleDtoOut>> list() {
        List<PriceRuleDtoOut> dtos = mapper.toDtoOutList(useCase.findAll());
        enrichScopeNames(dtos);
        return new ResponseEntity<>(dtos, HttpStatus.OK);
    }

    @Override
    public ResponseEntity<PriceRuleDtoOut> create(PriceRuleDtoIn req) {
        PriceRule model = useCase.save(mapper.toDomain(req));
        return new ResponseEntity<>(mapper.toDtoOut(model), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<PriceRuleDtoOut> update(UUID id, PriceRuleDtoIn req) {
        PriceRule model = useCase.update(mapper.toDomain(req), id);
        return new ResponseEntity<>(mapper.toDtoOut(model), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        useCase.delete(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Override
    public ResponseEntity<PriceRuleDtoOut> toggle(UUID id) {
        List<PriceRuleDtoOut> dto = List.of(mapper.toDtoOut(useCase.toggle(id)));
        enrichScopeNames(dto);
        return new ResponseEntity<>(dto.get(0), HttpStatus.OK);
    }

    /* ===================== Bulk admin actions (per-id error reporting) ===================== */

    /** Bulk activate/deactivate the selected rules (sets active to a specific value). */
    @PutMapping("/bulk-toggle")
    public ResponseEntity<Map<String, Object>> bulkToggle(@RequestBody BulkToggleRequest req) {
        return bulkApply(req.ids(), id -> useCase.setActive(id, req.active()));
    }

    /** Bulk delete the selected rules. */
    @PostMapping("/bulk-delete")
    public ResponseEntity<Map<String, Object>> bulkDelete(@RequestBody List<UUID> ids) {
        return bulkApply(ids, useCase::delete);
    }

    public record BulkToggleRequest(List<UUID> ids, boolean active) {
    }

    /**
     * Runs an action over each id, isolating failures so one bad id never aborts the batch.
     *
     * <p>Un cuerpo vacío o sin lista de ids es una petición mal formada, no un lote de cero: se responde
     * 400. Antes se recorría la lista sin comprobar nulo y salía un 500 sin explicación.
     */
    private ResponseEntity<Map<String, Object>> bulkApply(List<UUID> ids, Consumer<UUID> action) {
        if (ids == null || ids.isEmpty()) {
            throw new ArgumentException("Selecciona al menos un elemento.");
        }
        int succeeded = 0;
        List<String> errors = new ArrayList<>();
        for (UUID id : ids) {
            try {
                action.accept(id);
                succeeded++;
            } catch (RuntimeException ex) {
                errors.add(id + ": " + ErrorMessages.humanize(ex));
            }
        }
        return ResponseEntity.ok(Map.of("succeeded", succeeded, "failed", errors.size(), "errors", errors));
    }

    /* ------------------ DROP-630: scope name resolution ------------------ */

    /** Resolves the concrete entity name for each scoped rule, in batch per scope type. */
    private void enrichScopeNames(List<PriceRuleDtoOut> dtos) {
        Map<String, List<UUID>> idsByScope = new HashMap<>();
        for (PriceRuleDtoOut d : dtos) {
            if (d.getScopeId() != null && d.getScope() != null && !"GLOBAL".equals(d.getScope())) {
                idsByScope.computeIfAbsent(d.getScope(), k -> new ArrayList<>()).add(d.getScopeId());
            }
        }
        if (idsByScope.isEmpty()) {
            return;
        }
        Map<UUID, String> names = new HashMap<>();
        names.putAll(lookup("CATEGORY", idsByScope,
                "SELECT c.id, COALESCE(" + "(SELECT t.name FROM category_translation t WHERE t.category_id = c.id "
                        + "ORDER BY CASE t.language WHEN 'es' THEN 0 WHEN 'en' THEN 1 ELSE 2 END LIMIT 1), c.slug) "
                        + "FROM category c WHERE c.id IN (%s)"));
        names.putAll(
                lookup("SUPPLIER", idsByScope, "SELECT id, COALESCE(name, name_zh) FROM supplier WHERE id IN (%s)"));
        names.putAll(lookup("PRODUCT", idsByScope,
                "SELECT id, COALESCE(NULLIF(title_zh,''), slug) FROM product WHERE id IN (%s)"));
        names.putAll(lookup("PRODUCT_GROUP", idsByScope, "SELECT id, name FROM product_group WHERE id IN (%s)"));
        names.putAll(lookup("CATEGORY_GROUP", idsByScope, "SELECT id, name FROM category_group WHERE id IN (%s)"));
        names.putAll(lookup("VARIANT", idsByScope,
                "SELECT id, COALESCE(NULLIF(title,''), sku) FROM product_variant WHERE id IN (%s)"));
        for (PriceRuleDtoOut d : dtos) {
            if (d.getScopeId() != null) {
                d.setScopeName(names.get(d.getScopeId()));
            }
        }
    }

    private Map<UUID, String> lookup(String scope, Map<String, List<UUID>> idsByScope, String sqlTemplate) {
        List<UUID> ids = idsByScope.get(scope);
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        // NOSONAR java:S2077 — no hay concatenación de datos: el formato solo inserta marcadores "?" y
        // los valores viajan como parámetros del PreparedStatement (ids.toArray()).
        String sql = String.format(sqlTemplate, placeholders); // NOSONAR
        Map<UUID, String> out = new HashMap<>();
        jdbcTemplate.query(sql, rs -> { // NOSONAR java:S2077 — solo se interpolan marcadores "?"; los valores van parametrizados
            out.put(UUID.fromString(rs.getString(1)), rs.getString(2));
        }, ids.toArray());
        return out;
    }
}
