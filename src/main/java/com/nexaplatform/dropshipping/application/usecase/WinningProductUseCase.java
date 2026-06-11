package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.WinningProduct;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the catalog intelligence read projections (DROP-8): best
 * sellers (sales trends) and trend-ranked winning products. Operates on the
 * read-only {@link WinningProduct} model.
 */
public interface WinningProductUseCase {

    /**
     * Best-selling active products ranked by monthly sales, optionally filtered by
     * category and capped to {@code limit} (hard-capped at 100). Titles resolved in
     * {@code lang}.
     */
    List<WinningProduct> salesTrends(UUID categoryId, int limit, String lang);

    /**
     * Active products ranked by trend score, capped to {@code limit} (hard-capped at
     * 100). Titles resolved in {@code lang}.
     */
    List<WinningProduct> winning(int limit, String lang);
}
