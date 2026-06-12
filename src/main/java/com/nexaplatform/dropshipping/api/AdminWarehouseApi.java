package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.in.WarehouseUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.WarehouseDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

/** Admin Warehouses CRUD API. Routing/docs here; controller only delegates. */
@Tag(name = "Admin Warehouses")
public interface AdminWarehouseApi {

    @Operation(summary = "List all warehouses (active and inactive)")
    @GetMapping
    ResponseEntity<List<WarehouseDtoOut>> list();

    @Operation(summary = "Get a warehouse by id")
    @GetMapping("/{id}")
    ResponseEntity<WarehouseDtoOut> get(@PathVariable UUID id);

    @Operation(summary = "Create a warehouse")
    @PostMapping
    ResponseEntity<WarehouseDtoOut> create(@Valid @RequestBody WarehouseUpsertDtoIn req);

    @Operation(summary = "Update a warehouse")
    @PutMapping("/{id}")
    ResponseEntity<WarehouseDtoOut> update(@PathVariable UUID id, @Valid @RequestBody WarehouseUpsertDtoIn req);

    @Operation(summary = "Delete a warehouse")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id);
}
