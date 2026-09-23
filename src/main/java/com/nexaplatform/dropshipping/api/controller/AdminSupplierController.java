package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminSupplierApi;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.AdminSupplierUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierToggleDtoOut;
import com.nexaplatform.dropshipping.api.exception.ErrorMessages;
import com.nexaplatform.dropshipping.api.mapper.AdminSupplierMapper;
import com.nexaplatform.dropshipping.application.usecase.SupplierUseCase;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierIndexer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Admin Suppliers controller. Pure implementation of {@link AdminSupplierApi}:
 * injects the {@link AdminSupplierMapper} + {@link SupplierUseCase}; maps the
 * domain model to DtoOut; no business logic, no manual mapping.
 */
@RestController
@RequestMapping("/api/admin/catalog/suppliers")
@RequiredArgsConstructor
public class AdminSupplierController implements AdminSupplierApi {

    private final AdminSupplierMapper mapper;
    private final SupplierUseCase useCase;
    private final SupplierIndexer supplierIndexer;

    /** Reindexa todos los proveedores en OpenSearch (botón "Reindexar" del admin). */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        return ResponseEntity.ok(Map.of("indexed", supplierIndexer.reindexAll()));
    }

    @Override
    public ResponseEntity<PageResponse<AdminSupplierDtoOut>> list(String q, String country, Boolean verified, int page,
            int size) {
        SupplierUseCase.SupplierPage p = useCase.pageAdmin(q, country, verified, page, size);
        return ResponseEntity.ok(new PageResponse<>(mapper.toDtoOutList(p.items()), p.page(), p.size(), p.total(),
                (int) Math.ceil((double) p.total() / Math.max(1, p.size()))));
    }

    /**
     * DROP-585: toggles the supplier {@code verified} flag from the admin panel.
     * Same pattern applies to {@code trustPass}.
     */
    @Override
    public ResponseEntity<AdminSupplierToggleDtoOut> toggleVerified(UUID id) {
        return ResponseEntity.ok(mapper.toVerifiedToggle(useCase.toggleVerified(id)));
    }

    @Override
    public ResponseEntity<AdminSupplierToggleDtoOut> toggleTrustPass(UUID id) {
        return ResponseEntity.ok(mapper.toTrustPassToggle(useCase.toggleTrustPass(id)));
    }

    @Override
    public ResponseEntity<AdminSupplierDtoOut> create(AdminSupplierUpsertDtoIn req) {
        return new ResponseEntity<>(mapper.toDtoOut(useCase.create(mapper.toDomain(req))), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<AdminSupplierDtoOut> update(UUID id, AdminSupplierUpsertDtoIn req) {
        return ResponseEntity.ok(mapper.toDtoOut(useCase.update(id, mapper.toDomain(req))));
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        useCase.delete(id);
        return ResponseEntity.noContent().build();
    }

    /* ===================== Bulk admin actions (per-id error reporting) ===================== */

    /** Bulk verify/unverify the selected suppliers (sets the flag to a specific value). */
    @PutMapping("/bulk-verify")
    public ResponseEntity<Map<String, Object>> bulkVerify(@RequestBody BulkVerifyRequest req) {
        return bulkApply(req.ids(), id -> useCase.setVerified(id, req.verified()));
    }

    /** Bulk delete the selected suppliers (each refused if it still has products). */
    @PostMapping("/bulk-delete")
    public ResponseEntity<Map<String, Object>> bulkDelete(@RequestBody List<UUID> ids) {
        return bulkApply(ids, useCase::delete);
    }

    public record BulkVerifyRequest(List<UUID> ids, boolean verified) {
    }

    /** Runs an action over each id, isolating failures so one bad id never aborts the batch. */
    private ResponseEntity<Map<String, Object>> bulkApply(List<UUID> ids, Consumer<UUID> action) {
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
}
