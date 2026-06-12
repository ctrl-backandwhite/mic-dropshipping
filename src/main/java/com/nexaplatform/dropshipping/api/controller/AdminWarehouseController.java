package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminWarehouseApi;
import com.nexaplatform.dropshipping.api.dto.in.WarehouseUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.WarehouseDtoOut;
import com.nexaplatform.dropshipping.api.mapper.WarehouseDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.WarehouseUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Admin Warehouses controller — injects the mapper + use case, no business logic. */
@RestController
@RequestMapping("/api/admin/warehouses")
@RequiredArgsConstructor
public class AdminWarehouseController implements AdminWarehouseApi {

    private final WarehouseDtoMapper mapper;
    private final WarehouseUseCase useCase;

    @Override
    public ResponseEntity<List<WarehouseDtoOut>> list() {
        return ResponseEntity.ok(mapper.toDtoOutList(useCase.listAll()));
    }

    @Override
    public ResponseEntity<WarehouseDtoOut> get(UUID id) {
        return ResponseEntity.ok(mapper.toDtoOut(useCase.getById(id)));
    }

    @Override
    public ResponseEntity<WarehouseDtoOut> create(WarehouseUpsertDtoIn req) {
        return new ResponseEntity<>(mapper.toDtoOut(useCase.create(mapper.toDomain(req))), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<WarehouseDtoOut> update(UUID id, WarehouseUpsertDtoIn req) {
        return ResponseEntity.ok(mapper.toDtoOut(useCase.update(id, mapper.toDomain(req))));
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        useCase.delete(id);
        return ResponseEntity.noContent().build();
    }
}
