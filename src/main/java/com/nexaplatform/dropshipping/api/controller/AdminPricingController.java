package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminPricingApi;
import com.nexaplatform.dropshipping.api.dto.in.PriceRuleDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.PriceRuleDtoOut;
import com.nexaplatform.dropshipping.api.mapper.PriceRuleDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.PriceRuleUseCase;
import com.nexaplatform.dropshipping.domain.model.PriceRule;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin Pricing rules controller. Pure implementation of {@link AdminPricingApi}:
 * injects the {@link PriceRuleDtoMapper} + {@link PriceRuleUseCase}; maps
 * DtoIn -> domain -> DtoOut; no business logic, no manual mapping.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/pricing/rules")
public class AdminPricingController implements AdminPricingApi {

    private final PriceRuleDtoMapper mapper;
    private final PriceRuleUseCase useCase;

    @Override
    public ResponseEntity<List<PriceRuleDtoOut>> list() {
        return new ResponseEntity<>(mapper.toDtoOutList(useCase.findAll()), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<PriceRuleDtoOut> create(PriceRuleDtoIn req) {
        PriceRule model = useCase.save(mapper.toDomain(req));
        return new ResponseEntity<>(mapper.toDtoOut(model), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<PriceRuleDtoOut> update(UUID id, PriceRuleDtoIn req) {
        PriceRule model = useCase.update(mapper.toDomain(req), id);
        return new ResponseEntity<>(mapper.toDtoOut(model), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        useCase.delete(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
