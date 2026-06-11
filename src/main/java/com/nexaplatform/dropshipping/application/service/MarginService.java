package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.MarginType;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleScope;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PriceRuleEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PriceRuleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Resolves the most specific applicable price rule for a (product, variant, costUsd) tuple
 * and applies the resulting markup. Order: VARIANT > PRODUCT > SUPPLIER > CATEGORY > GLOBAL.
 *
 * <p>Rules are cached in-memory (5 min TTL). The cache refreshes opportunistically; admin
 * mutations flush it via {@link #invalidateCache()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarginService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final PriceRuleRepository repository;
    private final List<PriceRuleEntity> cache = new CopyOnWriteArrayList<>();
    private volatile Instant cacheStamp = Instant.EPOCH;

    @PostConstruct
    public void warm() {
        refresh();
    }

    /* ============ Apply margin ============ */

    public PriceWithMargin apply(BigDecimal costUsd, ProductEntity product, ProductVariantEntity variant) {
        if (costUsd == null || costUsd.signum() <= 0) {
            return new PriceWithMargin(costUsd, costUsd, null, BigDecimal.ZERO);
        }
        Optional<PriceRuleEntity> rule = resolve(product, variant, costUsd);
        if (rule.isEmpty()) {
            return new PriceWithMargin(costUsd, costUsd, null, BigDecimal.ZERO);
        }
        PriceRuleEntity r = rule.get();
        BigDecimal retail = switch (r.getMarginType()) {
            case PERCENTAGE -> costUsd.multiply(BigDecimal.ONE.add(r.getMarginValue().divide(HUNDRED, 6, RoundingMode.HALF_UP)));
            case FIXED -> costUsd.add(r.getMarginValue());
        };
        retail = retail.setScale(4, RoundingMode.HALF_UP);
        BigDecimal appliedPct = r.getMarginType() == MarginType.PERCENTAGE
                ? r.getMarginValue()
                : r.getMarginValue().multiply(HUNDRED).divide(costUsd, 2, RoundingMode.HALF_UP);
        return new PriceWithMargin(costUsd, retail, r, appliedPct);
    }

    /* ============ Resolution ============ */

    public Optional<PriceRuleEntity> resolve(ProductEntity product, ProductVariantEntity variant, BigDecimal costUsd) {
        ensureFresh();
        UUID productId = product != null ? product.getId() : null;
        UUID variantId = variant != null ? variant.getId() : null;
        UUID supplierId = (product != null && product.getSupplier() != null) ? product.getSupplier().getId() : null;
        UUID categoryId = (product != null && product.getCategory() != null) ? product.getCategory().getId() : null;

        for (PriceRuleScope scope : PriceRuleScope.values()) {
            UUID target = switch (scope) {
                case VARIANT -> variantId;
                case PRODUCT -> productId;
                case SUPPLIER -> supplierId;
                case CATEGORY -> categoryId;
                case GLOBAL -> null;
            };
            Optional<PriceRuleEntity> match = cache.stream()
                    .filter(PriceRuleEntity::isActive)
                    .filter(r -> r.getScope() == scope)
                    .filter(r -> scope == PriceRuleScope.GLOBAL || (r.getScopeId() != null && r.getScopeId().equals(target)))
                    .filter(r -> matchesCostRange(r, costUsd))
                    .findFirst();
            if (match.isPresent()) return match;
        }
        return Optional.empty();
    }

    /* ============ Admin operations ============ */

    @Transactional
    public PriceRuleEntity save(PriceRuleEntity rule) {
        PriceRuleEntity saved = repository.save(rule);
        invalidateCache();
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        repository.deleteById(id);
        invalidateCache();
    }

    public List<PriceRuleEntity> listAll() {
        ensureFresh();
        return List.copyOf(cache);
    }

    /* ============ helpers ============ */

    public void invalidateCache() {
        cacheStamp = Instant.EPOCH;
    }

    private synchronized void refresh() {
        cache.clear();
        cache.addAll(repository.findByActiveTrueOrderByPositionAsc());
        cacheStamp = Instant.now();
    }

    private void ensureFresh() {
        if (Duration.between(cacheStamp, Instant.now()).compareTo(CACHE_TTL) >= 0) refresh();
    }

    private boolean matchesCostRange(PriceRuleEntity r, BigDecimal cost) {
        if (r.getMinCostUsd() != null && cost.compareTo(r.getMinCostUsd()) < 0) return false;
        if (r.getMaxCostUsd() != null && cost.compareTo(r.getMaxCostUsd()) > 0) return false;
        return true;
    }

    public record PriceWithMargin(
            BigDecimal costUsd,
            BigDecimal retailUsd,
            PriceRuleEntity appliedRule,
            BigDecimal appliedPercentage
    ) {}
}
