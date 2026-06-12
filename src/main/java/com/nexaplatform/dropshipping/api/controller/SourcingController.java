package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.SourcingApi;
import com.nexaplatform.dropshipping.api.dto.in.SourcingCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.SourcingQuoteDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.SourcingAgentDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingAgentLiteDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingQuoteDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingRequestDtoOut;
import com.nexaplatform.dropshipping.api.mapper.SourcingDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.SourcingUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * DROP-3: Sourcing requests, agents marketplace, competing quotes. Pure
 * implementation of {@link SourcingApi}: no business logic — delegates to the use
 * case and maps the returned domain models to DtoOut.
 */
@RestController
@RequestMapping("/api/me/sourcing")
@RequiredArgsConstructor
public class SourcingController implements SourcingApi {

    private final SourcingUseCase sourcingUseCase;
    private final SourcingDtoMapper sourcingDtoMapper;

    @Override
    public ResponseEntity<List<SourcingRequestDtoOut>> myRequests(Authentication auth) {
        return ResponseEntity
                .ok(sourcingDtoMapper.toRequestList(sourcingUseCase.myRequests(UUID.fromString(auth.getName()))));
    }

    @Override
    public ResponseEntity<SourcingRequestDtoOut> create(Authentication auth, SourcingCreateDtoIn body) {
        return ResponseEntity.ok(sourcingDtoMapper.toRequest(sourcingUseCase.create(UUID.fromString(auth.getName()),
                body.getUrl(), body.getTitleHint(), body.getNotes())));
    }

    @Override
    public ResponseEntity<SourcingRequestDtoOut> detail(Authentication auth, UUID id) {
        return ResponseEntity
                .ok(sourcingDtoMapper.toRequest(sourcingUseCase.detail(UUID.fromString(auth.getName()), id)));
    }

    @Override
    public ResponseEntity<SourcingRequestDtoOut> cancel(Authentication auth, UUID id) {
        return ResponseEntity
                .ok(sourcingDtoMapper.toRequest(sourcingUseCase.cancel(UUID.fromString(auth.getName()), id)));
    }

    @Override
    public ResponseEntity<Void> delete(Authentication auth, UUID id) {
        sourcingUseCase.delete(UUID.fromString(auth.getName()), id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<SourcingQuoteDtoOut>> quotes(Authentication auth, UUID id) {
        return ResponseEntity
                .ok(sourcingDtoMapper.toQuoteList(sourcingUseCase.quotes(UUID.fromString(auth.getName()), id)));
    }

    @Override
    public ResponseEntity<SourcingQuoteDtoOut> submitQuote(UUID id, SourcingQuoteDtoIn q, UUID asAgent) {
        return ResponseEntity.ok(sourcingDtoMapper.toQuote(sourcingUseCase.submitQuote(id, q.getPriceUsdCents(),
                q.getEtaDays(), q.getMoq(), q.getNotes(), asAgent)));
    }

    @Override
    public ResponseEntity<SourcingRequestDtoOut> selectQuote(Authentication auth, UUID id, UUID quoteId) {
        return ResponseEntity.ok(
                sourcingDtoMapper.toRequest(sourcingUseCase.selectQuote(UUID.fromString(auth.getName()), id, quoteId)));
    }

    @Override
    public ResponseEntity<List<SourcingAgentLiteDtoOut>> agentsList() {
        return ResponseEntity.ok(sourcingDtoMapper.toAgentLiteList(sourcingUseCase.agentsList()));
    }

    @Override
    public ResponseEntity<SourcingAgentDetailDtoOut> agentDetail(UUID id) {
        return ResponseEntity.ok(sourcingDtoMapper.toAgentDetail(sourcingUseCase.agentDetail(id)));
    }
}
