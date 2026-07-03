package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.RateLimitDocsApi;
import com.nexaplatform.dropshipping.api.dto.out.RateLimitPolicyDtoOut;
import com.nexaplatform.dropshipping.application.service.RateLimitDocsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoint público que expone las políticas de rate-limit en formato máquina-legible.
 * Sirve como fuente de verdad para clientes que quieren auto-configurar throttle/backoff,
 * y replica lo declarado en la extensión `x-rate-limit` del OpenAPI.
 */
@RestController
@RequestMapping("/api/v1/rate-limits")
@RequiredArgsConstructor
public class RateLimitDocsController implements RateLimitDocsApi {

    private final RateLimitDocsService service;

    @Override
    public ResponseEntity<List<RateLimitPolicyDtoOut>> policies() {
        return new ResponseEntity<>(service.listPolicies(), HttpStatus.OK);
    }
}
