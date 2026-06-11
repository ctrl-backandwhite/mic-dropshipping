package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.MeAddressApi;
import com.nexaplatform.dropshipping.api.dto.in.AddressDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AddressDtoOut;
import com.nexaplatform.dropshipping.application.service.MeAddressService;
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
 * {@link MeAddressApi}: no business logic and no manual mapping — delegates to
 * {@link MeAddressService} and wraps the result in a {@link ResponseEntity}.
 */
@RestController
@RequestMapping("/api/me/addresses")
@RequiredArgsConstructor
public class MeAddressController implements MeAddressApi {

    private final MeAddressService service;

    @Override
    public ResponseEntity<List<AddressDtoOut>> list(Authentication auth) {
        return new ResponseEntity<>(service.list(UUID.fromString(auth.getName())), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<AddressDtoOut> create(Authentication auth, AddressDtoIn req) {
        return new ResponseEntity<>(service.create(UUID.fromString(auth.getName()), req), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<AddressDtoOut> update(Authentication auth, UUID id, AddressDtoIn req) {
        return new ResponseEntity<>(service.update(UUID.fromString(auth.getName()), id, req), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> delete(Authentication auth, UUID id) {
        service.delete(UUID.fromString(auth.getName()), id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
