package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.application.usecase.AdTrendUseCase;
import com.nexaplatform.dropshipping.domain.model.AdTrend;
import com.nexaplatform.dropshipping.domain.repository.AdTrendRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Ad-trend use case. Holds the listing logic that used to live in
 * {@code IntelligenceController}: pick the source-filtered or full query, then
 * cap to the requested limit (hard-capped at 100). Operates on the {@link AdTrend}
 * model and delegates persistence to the domain port.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdTrendUseCaseImpl implements AdTrendUseCase {

    private final AdTrendRepository adTrendRepository;

    @Override
    @Transactional(readOnly = true)
    public List<AdTrend> findTrends(String source, int limit) {
        List<AdTrend> rows = (source == null || source.isBlank())
                ? adTrendRepository.findAllByScoreDesc()
                : adTrendRepository.findBySourceByScoreDesc(source.toLowerCase());
        return rows.stream().limit(Math.min(limit, 100)).toList();
    }
}
