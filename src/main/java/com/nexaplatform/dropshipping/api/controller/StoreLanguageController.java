package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.dto.out.StoreLanguageDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.StoreLanguageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.StoreLanguageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registro de idiomas de la tienda. El endpoint público lista los idiomas activos (para el selector
 * de idioma y las pestañas del editor de contenido); el admin gestiona el alta/baja (ilimitado).
 */
@RestController
@RequiredArgsConstructor
public class StoreLanguageController {

    private final StoreLanguageRepository repository;

    /** Público: idiomas activos, ordenados (los que el comprador puede elegir y en los que hay contenido). */
    @GetMapping("/api/languages")
    @Transactional(readOnly = true)
    public List<StoreLanguageDtoOut> publicLanguages() {
        return repository.findByActiveTrueOrderByPositionAsc().stream().map(this::toDto).toList();
    }

    /** Admin: todos los idiomas (activos e inactivos). */
    @GetMapping("/api/admin/languages")
    @Transactional(readOnly = true)
    public List<StoreLanguageDtoOut> adminLanguages() {
        return repository.findAllByOrderByPositionAsc().stream().map(this::toDto).toList();
    }

    /** Admin: alta o actualización de un idioma por código. */
    @PostMapping("/api/admin/languages")
    @Transactional
    public ResponseEntity<StoreLanguageDtoOut> upsert(@RequestBody Map<String, Object> body) {
        String code = body.get("code") != null ? body.get("code").toString().trim().toLowerCase() : null;
        if (code == null || code.isEmpty()) {
            throw new BusinessException("code es obligatorio");
        }
        StoreLanguageEntity e = repository.findByCodeIgnoreCase(code).orElseGet(StoreLanguageEntity::new);
        e.setCode(code);
        if (body.get("label") != null) {
            e.setLabel(body.get("label").toString());
        }
        if (e.getLabel() == null || e.getLabel().isBlank()) {
            e.setLabel(code.toUpperCase());
        }
        if (body.get("flag") != null) {
            e.setFlag(body.get("flag").toString());
        }
        if (body.get("position") instanceof Number n) {
            e.setPosition(n.intValue());
        }
        e.setActive(body.get("active") == null || Boolean.parseBoolean(body.get("active").toString()));
        if (body.get("isDefault") != null) {
            e.setDefault(Boolean.parseBoolean(body.get("isDefault").toString()));
        }
        return ResponseEntity.ok(toDto(repository.save(e)));
    }

    @DeleteMapping("/api/admin/languages/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private StoreLanguageDtoOut toDto(StoreLanguageEntity e) {
        return new StoreLanguageDtoOut(e.getId(), e.getCode(), e.getLabel(), e.getFlag(), e.getPosition(),
                e.isActive(), e.isDefault());
    }
}
