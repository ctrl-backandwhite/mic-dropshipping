package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.MarginType;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleScope;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PriceRuleEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PriceRuleRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryGroupMemberRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductGroupMemberRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /** Half-bounded ranges are less specific than fully bounded ones; "any cost" is the least specific. */
    private static final BigDecimal HALF_BOUNDED_WIDTH = new BigDecimal("1000000");
    private static final BigDecimal UNBOUNDED_WIDTH = new BigDecimal("1000000000");

    /**
     * DROP-630 deterministic tie-break among same-scope rules matching the same cost:
     * narrowest cost range first, then lowest admin position, then most recently created,
     * then id — a total order so the winner never depends on stream/DB iteration order.
     */
    private static final Comparator<PriceRuleEntity> MOST_SPECIFIC = Comparator
            .comparing(MarginService::rangeWidth)
            .thenComparingInt(PriceRuleEntity::getPosition)
            .thenComparing(r -> r.getCreatedAt() != null ? r.getCreatedAt() : Instant.EPOCH,
                    Comparator.reverseOrder())
            .thenComparing(r -> r.getId() != null ? r.getId().toString() : "");

    private static BigDecimal rangeWidth(PriceRuleEntity r) {
        boolean hasMin = r.getMinCostUsd() != null;
        boolean hasMax = r.getMaxCostUsd() != null;
        if (hasMin && hasMax) {
            return r.getMaxCostUsd().subtract(r.getMinCostUsd()).abs();
        }
        if (hasMin || hasMax) {
            return HALF_BOUNDED_WIDTH;
        }
        return UNBOUNDED_WIDTH;
    }

    private final PriceRuleRepository repository;
    private final ProductGroupMemberRepository groupMemberRepository;
    private final CategoryGroupMemberRepository categoryGroupMemberRepository;
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
            case PERCENTAGE ->
                costUsd.multiply(BigDecimal.ONE.add(r.getMarginValue().divide(HUNDRED, 6, RoundingMode.HALF_UP)));
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
        // Solo se consideran reglas del canal del request (STOREFRONT por defecto; INTEGRATION para apps API).
        // Así conviven el margen del storefront (p. ej. 150%) y el de integración (p. ej. 75%) sin colisionar.
        final PriceRuleChannel channel = PricingChannelHolder.get();
        UUID productId = product != null ? product.getId() : null;
        UUID variantId = variant != null ? variant.getId() : null;
        UUID supplierId = (product != null && product.getSupplier() != null) ? product.getSupplier().getId() : null;
        UUID categoryId = (product != null && product.getCategory() != null) ? product.getCategory().getId() : null;

        for (PriceRuleScope scope : PriceRuleScope.values()) {
            // A product can belong to several groups, so PRODUCT_GROUP matches a SET of scopeIds; the
            // other scopes resolve to a single id. The group membership is only queried when there is at
            // least one active PRODUCT_GROUP rule (keeps the pricing hot path free of extra queries).
            final Set<UUID> targets = switch (scope) {
                case VARIANT -> variantId != null ? Set.of(variantId) : Set.of();
                case PRODUCT -> productId != null ? Set.of(productId) : Set.of();
                case PRODUCT_GROUP -> (productId != null && hasGroupRules()) ? groupIdsOf(productId) : Set.of();
                case SUPPLIER -> supplierId != null ? Set.of(supplierId) : Set.of();
                case CATEGORY -> categoryId != null ? Set.of(categoryId) : Set.of();
                // Una regla CATEGORY_GROUP aplica si la categoría del producto pertenece a alguno de sus grupos.
                case CATEGORY_GROUP -> (categoryId != null && hasCategoryGroupRules())
                        ? categoryGroupIdsOf(categoryId)
                        : Set.of();
                case GLOBAL -> Set.of();
            };
            // DROP-630: when several active rules of the SAME scope match the same cost,
            // the declared VARIANT>PRODUCT>… order only disambiguates across levels, not within
            // one. Pick deterministically: narrowest cost range (most specific) → lowest
            // position → most recently created → id, so resolution is never order-dependent.
            Optional<PriceRuleEntity> match = cache.stream().filter(PriceRuleEntity::isActive)
                    .filter(r -> r.getChannel() == channel)
                    .filter(r -> r.getScope() == scope)
                    .filter(r -> scope == PriceRuleScope.GLOBAL
                            || (r.getScopeId() != null && targets.contains(r.getScopeId())))
                    .filter(r -> matchesCostRange(r, costUsd)).min(MOST_SPECIFIC);
            if (match.isPresent())
                return match;
        }
        return Optional.empty();
    }

    /** Whether any cached rule targets a product group (gates the membership query). */
    private boolean hasGroupRules() {
        return cache.stream().anyMatch(r -> r.isActive() && r.getScope() == PriceRuleScope.PRODUCT_GROUP);
    }

    private Set<UUID> groupIdsOf(UUID productId) {
        return Set.copyOf(groupMemberRepository.findGroupIdsByProductId(productId));
    }

    /** Whether any cached rule targets a category group (gates the membership query). */
    private boolean hasCategoryGroupRules() {
        return cache.stream().anyMatch(r -> r.isActive() && r.getScope() == PriceRuleScope.CATEGORY_GROUP);
    }

    private Set<UUID> categoryGroupIdsOf(UUID categoryId) {
        return Set.copyOf(categoryGroupMemberRepository.findGroupIdsByCategoryId(categoryId));
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
        if (Duration.between(cacheStamp, Instant.now()).compareTo(CACHE_TTL) >= 0)
            refresh();
    }

    private boolean matchesCostRange(PriceRuleEntity r, BigDecimal cost) {
        if (r.getMinCostUsd() != null && cost.compareTo(r.getMinCostUsd()) < 0)
            return false;
        if (r.getMaxCostUsd() != null && cost.compareTo(r.getMaxCostUsd()) > 0)
            return false;
        return true;
    }

    public record PriceWithMargin(BigDecimal costUsd, BigDecimal retailUsd, PriceRuleEntity appliedRule,
            BigDecimal appliedPercentage) {
    }
}
