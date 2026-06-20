package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminUsersListApi;
import com.nexaplatform.dropshipping.api.dto.in.AdminUserEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminUserInviteDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminUserRoleUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserPageDtoOut;
import com.nexaplatform.dropshipping.api.exception.ErrorMessages;
import com.nexaplatform.dropshipping.api.mapper.UserDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
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
 * Admin Users list/lock/role controller. Pure implementation of
 * {@link AdminUsersListApi}: injects the {@link UserDtoMapper} + {@link UserUseCase};
 * delegates every operation and maps the domain models to the DtoOuts. No
 * business logic, no manual mapping.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUsersListController implements AdminUsersListApi {

    private final UserDtoMapper mapper;
    private final UserUseCase useCase;

    @Override
    public ResponseEntity<AdminUserPageDtoOut> list(String role, String q, String country, int page, int size) {
        AdminUserPageDtoOut body = mapper.toAdminPageDtoOut(useCase.listUsers(role, q, country, page, size),
                useCase.countUsers(role, q, country), page, size);
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AdminUserDtoOut> changeRole(UUID id, AdminUserRoleUpdateDtoIn body) {
        return new ResponseEntity<>(mapper.toAdminDtoOut(useCase.changeRole(id, body.getRole())), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AdminUserDtoOut> editUser(UUID id, AdminUserEditDtoIn body) {
        return new ResponseEntity<>(mapper.toAdminDtoOut(useCase.editUser(id, mapper.toDomain(body), body.getActive())),
                HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AdminUserDtoOut> lock(UUID id, int minutes) {
        return new ResponseEntity<>(mapper.toAdminDtoOut(useCase.lock(id, minutes)), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AdminUserDtoOut> unlock(UUID id) {
        return new ResponseEntity<>(mapper.toAdminDtoOut(useCase.unlock(id)), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AdminUserDtoOut> forceActivate(UUID id) {
        return new ResponseEntity<>(mapper.toAdminDtoOut(useCase.forceActivate(id)), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> resetPassword(UUID id) {
        useCase.adminResetPassword(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        useCase.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AdminUserDtoOut> invite(AdminUserInviteDtoIn body) {
        return new ResponseEntity<>(mapper.toAdminDtoOut(useCase.inviteUser(body.getEmail(), body.getRole())),
                HttpStatus.CREATED);
    }

    /* ===================== Bulk admin actions (per-id error reporting) ===================== */

    /** Bulk force-activate the selected users. */
    @PostMapping("/bulk-activate")
    public ResponseEntity<Map<String, Object>> bulkActivate(@RequestBody List<UUID> ids) {
        return bulkApply(ids, useCase::forceActivate);
    }

    /** Bulk lock the selected users for 60 minutes (same default as the single-user lock). */
    @PostMapping("/bulk-lock")
    public ResponseEntity<Map<String, Object>> bulkLock(@RequestBody List<UUID> ids) {
        return bulkApply(ids, id -> useCase.lock(id, 60));
    }

    /** Bulk unlock the selected users. */
    @PostMapping("/bulk-unlock")
    public ResponseEntity<Map<String, Object>> bulkUnlock(@RequestBody List<UUID> ids) {
        return bulkApply(ids, useCase::unlock);
    }

    /** Bulk change the role of the selected users. */
    @PutMapping("/bulk-role")
    public ResponseEntity<Map<String, Object>> bulkRole(@RequestBody BulkRoleRequest req) {
        return bulkApply(req.ids(), id -> useCase.changeRole(id, req.role()));
    }

    /** Bulk delete the selected user accounts. */
    @PostMapping("/bulk-delete")
    public ResponseEntity<Map<String, Object>> bulkDelete(@RequestBody List<UUID> ids) {
        return bulkApply(ids, useCase::deleteUser);
    }

    public record BulkRoleRequest(List<UUID> ids, String role) {
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
