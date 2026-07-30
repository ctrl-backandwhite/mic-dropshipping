package com.nexaplatform.dropshipping.api.controller;

import org.springframework.data.domain.Page;
import com.nexaplatform.dropshipping.api.AdminCategoryApi;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.AdminCategoryUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminCategoryDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminCategoryMapper;
import com.nexaplatform.dropshipping.application.usecase.CategoryUseCase;
import com.nexaplatform.dropshipping.domain.model.Category;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategoryIndexer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @Override
    public ResponseEntity<PageResponse<AdminCategoryDtoOut>> listPaged(String q, Boolean hasProducts, int page,
            int size) {
        // Ordered by position then slug — covered by the (position) / (parent_id, position) indexes (v57).
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.max(1, size),
                Sort.by(Sort.Order.asc("position"), Sort.Order.asc("slug")));
        Page<Category> result = useCase.findAllPaged(q, hasProducts, pageable);
        return ResponseEntity.ok(PageResponse.map(result, mapper::toDtoOut));
    }

    @Override
    public ResponseEntity<List<AdminCategoryDtoOut>> listWithProducts() {
        return ResponseEntity.ok(mapper.toDtoOutList(useCase.findWithProducts()));
    }

    /** Activa/desactiva varias categorías a la vez (botones de acción masiva del admin). */
    @PutMapping("/bulk-active")
    public ResponseEntity<Map<String, Object>> bulkActive(@RequestBody BulkActiveRequest req) {
        return ResponseEntity.ok(Map.of("updated", useCase.setActiveBulk(req.ids(), req.active())));
    }

    public record BulkActiveRequest(List<UUID> ids, boolean active) {
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
