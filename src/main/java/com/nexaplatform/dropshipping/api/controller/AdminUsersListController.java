package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminUsersListApi;
import com.nexaplatform.dropshipping.api.dto.in.AdminUserEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminUserRoleUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserPageDtoOut;
import com.nexaplatform.dropshipping.api.mapper.UserDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

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
}
