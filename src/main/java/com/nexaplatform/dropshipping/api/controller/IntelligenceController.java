package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.IntelligenceApi;
import com.nexaplatform.dropshipping.api.dto.in.AlertRequestDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AlertViewDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.TrendRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WinningProductDtoOut;
import com.nexaplatform.dropshipping.api.mapper.AdTrendDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.IntelligenceAlertDtoMapper;
import com.nexaplatform.dropshipping.api.mapper.WinningProductDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.AdTrendUseCase;
import com.nexaplatform.dropshipping.application.usecase.IntelligenceAlertUseCase;
import com.nexaplatform.dropshipping.application.usecase.WinningProductUseCase;
import com.nexaplatform.dropshipping.domain.model.IntelligenceAlert;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Intelligence controller (DROP-8). Pure implementation of {@link IntelligenceApi}:
 * injects the per-aggregate DtoMappers + use cases; maps DtoIn -> domain -> DtoOut
 * and delegates. No business logic, no manual mapping.
 */
@RestController
@RequestMapping("/api/me/intelligence")
@RequiredArgsConstructor
public class IntelligenceController implements IntelligenceApi {

    private final AdTrendDtoMapper adTrendDtoMapper;
    private final WinningProductDtoMapper winningProductDtoMapper;
    private final IntelligenceAlertDtoMapper intelligenceAlertDtoMapper;

    private final AdTrendUseCase adTrendUseCase;
    private final WinningProductUseCase winningProductUseCase;
    private final IntelligenceAlertUseCase intelligenceAlertUseCase;

    @Override
    public List<TrendRowDtoOut> adTrends(String source, int limit) {
        return adTrendDtoMapper.toDtoOutList(adTrendUseCase.findTrends(source, limit));
    }

    @Override
    public List<WinningProductDtoOut> salesTrends(UUID categoryId, int limit, String lang) {
        return winningProductDtoMapper.toDtoOutList(winningProductUseCase.salesTrends(categoryId, limit, lang));
    }

    @Override
    public List<WinningProductDtoOut> winning(int limit, String lang) {
        return winningProductDtoMapper.toDtoOutList(winningProductUseCase.winning(limit, lang));
    }

    /* ---------- alerts (DROP-71) ---------- */

    @Override
    public List<AlertViewDtoOut> alerts(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return intelligenceAlertDtoMapper.toDtoOutList(intelligenceAlertUseCase.findActiveForUser(userId));
    }

    @Override
    public AlertViewDtoOut createAlert(Authentication auth, AlertRequestDtoIn req) {
        UUID userId = UUID.fromString(auth.getName());
        IntelligenceAlert model = intelligenceAlertUseCase.create(userId, intelligenceAlertDtoMapper.toDomain(req));
        return intelligenceAlertDtoMapper.toDtoOut(model);
    }

    @Override
    public void deleteAlert(Authentication auth, UUID id) {
        UUID userId = UUID.fromString(auth.getName());
        intelligenceAlertUseCase.deactivate(userId, id);
    }
}
