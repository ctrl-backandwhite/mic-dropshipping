package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminUserApi;
import com.nexaplatform.dropshipping.api.dto.AuthDtos.CreateAdminUserRequest;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserCreatedDtoOut;
import com.nexaplatform.dropshipping.application.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Users creation controller. Pure implementation of {@link AdminUserApi}:
 * no business logic and no manual mapping — delegates to {@link AuthService}
 * (which maps to the DTO) and wraps the result in a standardized
 * {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController implements AdminUserApi {

    private final AuthService authService;

    @Override
    public ResponseEntity<AdminUserCreatedDtoOut> create(CreateAdminUserRequest req) {
        return new ResponseEntity<>(authService.createAdminUserDto(req), HttpStatus.CREATED);
    }
}
