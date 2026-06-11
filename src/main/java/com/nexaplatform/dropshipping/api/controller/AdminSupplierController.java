package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminSupplierApi;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierToggleDtoOut;
import com.nexaplatform.dropshipping.application.service.AdminSupplierService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/catalog/suppliers")
@RequiredArgsConstructor
public class AdminSupplierController implements AdminSupplierApi {

    private final AdminSupplierService adminSupplierService;

    @Override
    public ResponseEntity<List<AdminSupplierDtoOut>> list() {
        return ResponseEntity.ok(adminSupplierService.list());
    }

    /**
     * DROP-585: toggles the supplier {@code verified} flag from the admin panel.
     * Same pattern applies to {@code trustPass}.
     */
    @Override
    public ResponseEntity<AdminSupplierToggleDtoOut> toggleVerified(UUID id) {
        return ResponseEntity.ok(adminSupplierService.toggleVerified(id));
    }

    @Override
    public ResponseEntity<AdminSupplierToggleDtoOut> toggleTrustPass(UUID id) {
        return ResponseEntity.ok(adminSupplierService.toggleTrustPass(id));
    }
}
