package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.CurrencyApi;
import com.nexaplatform.dropshipping.api.dto.in.UpdateRateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.CurrencyDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CurrencySyncResultDtoOut;
import com.nexaplatform.dropshipping.application.service.CurrencyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Currency controller. Pure implementation of {@link CurrencyApi}: no business
 * logic and no manual mapping — delegates to {@link CurrencyService} and wraps
 * the result in a {@link ResponseEntity}.
 */
@RestController
@RequiredArgsConstructor
public class CurrencyController implements CurrencyApi {

    private final CurrencyService service;

    @Override
    public ResponseEntity<List<CurrencyDtoOut>> listActive() {
        return new ResponseEntity<>(service.listActive(), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<CurrencyDtoOut> one(String code) {
        return new ResponseEntity<>(service.one(code), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<CurrencyDtoOut> updateRate(String code, UpdateRateDtoIn req) {
        return new ResponseEntity<>(service.updateRate(code, req), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<CurrencySyncResultDtoOut> sync() {
        return new ResponseEntity<>(service.sync(), HttpStatus.OK);
    }
}
