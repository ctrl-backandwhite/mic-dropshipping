package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.CurrencyApi;
import com.nexaplatform.dropshipping.api.dto.in.CurrencyBulkActiveDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.UpdateRateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.CurrencyDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CurrencySyncResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CurrencySyncStatusDtoOut;
import com.nexaplatform.dropshipping.api.mapper.CurrencyDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.CurrencyRateUseCase;
import com.nexaplatform.dropshipping.domain.model.CurrencyRate;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Currency controller. Pure implementation of {@link CurrencyApi}: injects the
 * {@link CurrencyDtoMapper} + {@link CurrencyRateUseCase}; maps domain -> DtoOut;
 * no business logic, no manual mapping.
 */
@RestController
@RequiredArgsConstructor
public class CurrencyController implements CurrencyApi {

    private final CurrencyDtoMapper mapper;
    private final CurrencyRateUseCase useCase;

    @Override
    public ResponseEntity<List<CurrencyDtoOut>> listActive() {
        return new ResponseEntity<>(mapper.toDtoOutList(useCase.listActive()), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<List<CurrencyDtoOut>> listAll() {
        return new ResponseEntity<>(mapper.toDtoOutList(useCase.listAll()), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<CurrencyDtoOut> setActive(String code, boolean active) {
        return new ResponseEntity<>(mapper.toDtoOut(useCase.setActive(code, active)), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Map<String, Object>> bulkActive(CurrencyBulkActiveDtoIn req) {
        int changed = useCase.bulkSetActive(req.getCodes(), req.isActive());
        return ResponseEntity.ok(Map.of("changed", changed));
    }

    @Override
    public ResponseEntity<CurrencyDtoOut> one(String code) {
        return new ResponseEntity<>(mapper.toDtoOut(useCase.one(code)), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<CurrencyDtoOut> updateRate(String code, UpdateRateDtoIn req) {
        CurrencyRate model = useCase.updateRate(code, req.getRateVsUsd(), req.getActive());
        return new ResponseEntity<>(mapper.toDtoOut(model), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<CurrencySyncResultDtoOut> sync() {
        return new ResponseEntity<>(mapper.toDtoOut(useCase.sync()), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<CurrencySyncStatusDtoOut> syncStatus() {
        CurrencyRateUseCase.SyncStatus s = useCase.syncStatus();
        return ResponseEntity.ok(new CurrencySyncStatusDtoOut(s.lastSyncedAt(), s.nextSyncAt(), s.cron()));
    }
}
