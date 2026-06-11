package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.AdTrend;

import java.util.List;

/** Use-case port for ad trends (DROP-8); operates on the {@link AdTrend} domain model. */
public interface AdTrendUseCase {

    /**
     * Lists ad trends ordered by score descending, optionally filtered by source
     * and capped to {@code limit} (hard-capped at 100, mirroring the legacy view).
     */
    List<AdTrend> findTrends(String source, int limit);
}
