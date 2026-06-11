package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeAddressApi;
import com.nexaplatform.dropshipping.api.dto.in.AddressDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AddressDtoOut;
import com.nexaplatform.dropshipping.api.mapper.MeAddressDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.UserAddressUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated user's addresses controller. Pure implementation of
 * {@link MeAddressApi}: no business logic. Each method maps the DTO to the
 * domain model, delegates to {@link UserAddressUseCase} (scoped to the
 * authenticated user) and maps the result back to a {@link AddressDtoOut}.
 */
@RestController
@RequestMapping("/api/me/addresses")
@RequiredArgsConstructor
public class MeAddressController implements MeAddressApi {

    private final MeAddressDtoMapper mapper;
    private final UserAddressUseCase useCase;

    @Override
    public ResponseEntity<List<AddressDtoOut>> list(Authentication auth) {
        return new ResponseEntity<>(
                mapper.toDtoOutList(useCase.findAll(UUID.fromString(auth.getName()))), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AddressDtoOut> create(Authentication auth, AddressDtoIn req) {
        return new ResponseEntity<>(
                mapper.toDtoOut(useCase.save(UUID.fromString(auth.getName()), mapper.toDomain(req))),
                HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<AddressDtoOut> update(Authentication auth, UUID id, AddressDtoIn req) {
        return new ResponseEntity<>(
                mapper.toDtoOut(useCase.update(UUID.fromString(auth.getName()), id, mapper.toDomain(req))),
                HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> delete(Authentication auth, UUID id) {
        useCase.delete(UUID.fromString(auth.getName()), id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
