package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminSupplierApi;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierToggleDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdminSupplierMapper;
import com.nexaplatform.dropshipping.application.usecase.SupplierUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

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

    @Override
    public ResponseEntity<List<AdminSupplierDtoOut>> list() {
        return ResponseEntity.ok(mapper.toDtoOutList(useCase.findAll()));
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
}
