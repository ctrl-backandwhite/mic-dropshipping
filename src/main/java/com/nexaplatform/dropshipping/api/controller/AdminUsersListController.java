package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminUsersListApi;
import com.nexaplatform.dropshipping.api.dto.in.AdminUserEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminUserRoleUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserPageDtoOut;
import com.nexaplatform.dropshipping.application.service.AdminUserQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin Users list/lock/role controller. Pure implementation of
 * {@link AdminUsersListApi}: no business logic and no manual mapping —
 * delegates to {@link AdminUserQueryService} and wraps every result in a
 * standardized {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUsersListController implements AdminUsersListApi {

    private final AdminUserQueryService adminUserQueryService;

    @Override
    public ResponseEntity<AdminUserPageDtoOut> list(String role, String q, String country, int page, int size) {
        return new ResponseEntity<>(adminUserQueryService.listUsers(role, q, country, page, size), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AdminUserDtoOut> changeRole(UUID id, AdminUserRoleUpdateDtoIn body) {
        return new ResponseEntity<>(adminUserQueryService.changeRole(id, body), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AdminUserDtoOut> editUser(UUID id, AdminUserEditDtoIn body) {
        return new ResponseEntity<>(adminUserQueryService.editUser(id, body), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AdminUserDtoOut> lock(UUID id, int minutes) {
        return new ResponseEntity<>(adminUserQueryService.lock(id, minutes), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AdminUserDtoOut> unlock(UUID id) {
        return new ResponseEntity<>(adminUserQueryService.unlock(id), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AdminUserDtoOut> forceActivate(UUID id) {
        return new ResponseEntity<>(adminUserQueryService.forceActivate(id), HttpStatus.OK);
    }
}
