package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminUserApi;
import com.nexaplatform.dropshipping.api.dto.in.CreateAdminUserDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserCreatedDtoOut;
import com.nexaplatform.dropshipping.api.mapper.UserDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin Users creation controller. Pure implementation of {@link AdminUserApi}:
 * injects the {@link UserDtoMapper} + {@link UserUseCase}; maps the request to
 * the domain model, delegates the creation and maps the result back to the
 * DtoOut. No business logic, no manual mapping.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController implements AdminUserApi {

    private final UserDtoMapper mapper;
    private final UserUseCase useCase;

    @Override
    public ResponseEntity<AdminUserCreatedDtoOut> create(CreateAdminUserDtoIn req) {
        User created = useCase.createAdminUser(mapper.toDomain(req), req.getPassword(), req.getRole());
        return new ResponseEntity<>(mapper.toCreatedDtoOut(created), HttpStatus.CREATED);
    }
}
