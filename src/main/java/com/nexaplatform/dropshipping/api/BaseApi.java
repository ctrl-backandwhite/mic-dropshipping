package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.dto.OperationResponseDtoOut;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Base REST contract for CRUD controllers (mirrors the core's {@code BaseApi}).
 * Controllers implement only the operations they expose; the rest default to
 * {@code 501 NOT_IMPLEMENTED}.
 *
 * @param <I> input DtoIn type
 * @param <O> output DtoOut type
 * @param <K> identifier type
 */
public interface BaseApi<I, O, K> {

    // ---------------- CREATE ----------------
    @Operation(summary = "Create record", description = "Creates a new record.")
    default ResponseEntity<O> create(@Valid @RequestBody I dto) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    @Operation(summary = "Create multiple records", description = "Creates multiple records at once.")
    default ResponseEntity<OperationResponseDtoOut> createAll(@Valid @RequestBody List<I> dtos) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    // ---------------- UPDATE ----------------
    @Operation(summary = "Update record", description = "Updates a record by ID.")
    default ResponseEntity<O> update(@Valid @RequestBody I dto, @PathVariable K id) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    @Operation(summary = "Update multiple records", description = "Updates multiple records at once.")
    default ResponseEntity<OperationResponseDtoOut> updateAll(@Valid @RequestBody List<I> dtos) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    // ---------------- DELETE ----------------
    @Operation(summary = "Delete record", description = "Deletes a record by ID.")
    default ResponseEntity<Void> delete(@PathVariable K id) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    @Operation(summary = "Delete multiple records", description = "Deletes multiple records by IDs.")
    default ResponseEntity<Void> deleteAll(@RequestBody List<K> ids) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    // ---------------- READ ----------------
    @Operation(summary = "Get record by ID", description = "Retrieves a record by its ID.")
    default ResponseEntity<O> getById(@PathVariable K id) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    @Operation(summary = "Get records by IDs", description = "Retrieves multiple records by a list of IDs.")
    default ResponseEntity<List<O>> getByIds(@RequestParam List<K> ids) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    @Operation(summary = "Get all records", description = "Retrieves all records.")
    default ResponseEntity<List<O>> findAll() {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    @Operation(summary = "Get paginated records", description = "Retrieves records with pagination and sorting.")
    default ResponseEntity<List<O>> findAllPagedAndSorted(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "true") boolean ascending) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }

    @Operation(summary = "Get filtered records", description = "Retrieves records filtered by parameters.")
    default ResponseEntity<List<O>> findAllFiltered(@RequestParam Map<String, Object> filters) {
        return new ResponseEntity<>(HttpStatus.NOT_IMPLEMENTED);
    }
}
