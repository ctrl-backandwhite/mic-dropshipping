package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminCategoryApi;
import com.nexaplatform.dropshipping.api.dto.in.AdminCategoryUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminCategoryDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminCategoryMapper;
import com.nexaplatform.dropshipping.application.usecase.CategoryUseCase;
import com.nexaplatform.dropshipping.domain.model.Category;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategoryIndexer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin Categories controller. Pure implementation of {@link AdminCategoryApi}:
 * no routing/documentation annotations here (they live on the interface), no
 * business logic — maps DtoIn -> domain -> DtoOut and delegates to the use case.
 */
@RestController
@RequestMapping("/api/admin/catalog/categories")
@RequiredArgsConstructor
public class AdminCategoryController implements AdminCategoryApi {

    private final AdminCategoryMapper mapper;
    private final CategoryUseCase useCase;
    private final CategoryIndexer categoryIndexer;

    @Override
    public ResponseEntity<List<AdminCategoryDtoOut>> list() {
        return ResponseEntity.ok(mapper.toDtoOutList(useCase.findAll()));
    }

    /** Reindexa todas las categorías en OpenSearch (botón "Reindexar" del admin). */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        return ResponseEntity.ok(Map.of("indexed", categoryIndexer.reindexAll()));
    }

    @Override
    public ResponseEntity<AdminCategoryDtoOut> toggle(UUID id) {
        return ResponseEntity.ok(mapper.toDtoOut(useCase.toggle(id)));
    }

    @Override
    public ResponseEntity<AdminCategoryDtoOut> update(UUID id, AdminCategoryUpsertDtoIn req) {
        Category model = useCase.update(mapper.toDomain(req), id);
        return ResponseEntity.ok(mapper.toDtoOut(model));
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        useCase.delete(id);
        return ResponseEntity.noContent().build();
    }
}
