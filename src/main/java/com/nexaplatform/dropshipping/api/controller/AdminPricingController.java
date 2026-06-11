package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminPricingApi;
import com.nexaplatform.dropshipping.api.dto.in.PriceRuleDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.PriceRuleDtoOut;
import com.nexaplatform.dropshipping.application.service.MarginService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin Pricing rules controller. Pure implementation of {@link AdminPricingApi}:
 * no business logic and no manual mapping — delegates everything to
 * {@link MarginService} (which uses the MapStruct mapper) and wraps results
 * in a standardized {@link ResponseEntity}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/pricing/rules")
public class AdminPricingController implements AdminPricingApi {

    private final MarginService marginService;

    @Override
    public ResponseEntity<List<PriceRuleDtoOut>> list() {
        return new ResponseEntity<>(marginService.listRules(), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<PriceRuleDtoOut> create(PriceRuleDtoIn req) {
        return new ResponseEntity<>(marginService.createRule(req), HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<PriceRuleDtoOut> update(UUID id, PriceRuleDtoIn req) {
        return new ResponseEntity<>(marginService.updateRule(id, req), HttpStatus.OK);
    }

    @Override
    public ResponseEntity<Void> delete(UUID id) {
        marginService.delete(id);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
}
