package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.MarginType;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleScope;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PriceRuleEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.MoqMarginSettingEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.MoqMarginSettingRepository;
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
            // Una regla CON país es más específica que la equivalente sin país (margen por país gana).
            .comparingInt((PriceRuleEntity r) -> r.getCountryCode() == null ? 1 : 0)
            .thenComparing(MarginService::rangeWidth)
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
    private final MoqMarginSettingRepository moqRepository;
    private final List<PriceRuleEntity> cache = new CopyOnWriteArrayList<>();
    private volatile Instant cacheStamp = Instant.EPOCH;

    /** Ajuste MOQ cacheado (se refresca con el mismo ciclo que las reglas). Por defecto: activo al 50%. */
    private volatile boolean moqEnabled = true;
    private volatile BigDecimal moqFactorPercent = new BigDecimal("50");

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
        // Regla MOQ (13-ago-2026): si el producto exige comprar MÁS de una unidad (moq>1) y la regla está
        // activa, el margen que le corresponda se reduce a `moqFactorPercent`% (por defecto 50% = la mitad).
        boolean reduceForMoq = moqEnabled && product != null && product.getMoq() > 1;
        BigDecimal marginValue = r.getMarginValue();
        BigDecimal effectiveValue = reduceForMoq
                ? marginValue.multiply(moqFactorPercent).divide(HUNDRED, 6, RoundingMode.HALF_UP)
                : marginValue;
        BigDecimal retail = switch (r.getMarginType()) {
            case PERCENTAGE ->
                costUsd.multiply(BigDecimal.ONE.add(effectiveValue.divide(HUNDRED, 6, RoundingMode.HALF_UP)));
            case FIXED -> costUsd.add(effectiveValue);
        };
        retail = retail.setScale(4, RoundingMode.HALF_UP);
        BigDecimal appliedPct = r.getMarginType() == MarginType.PERCENTAGE
                ? effectiveValue
                : effectiveValue.multiply(HUNDRED).divide(costUsd, 2, RoundingMode.HALF_UP);
        return new PriceWithMargin(costUsd, retail, r, appliedPct);
    }

    /* ============ Resolution ============ */

    public Optional<PriceRuleEntity> resolve(ProductEntity product, ProductVariantEntity variant, BigDecimal costUsd) {
        ensureFresh();
        // Solo se consideran reglas del canal del request (STOREFRONT por defecto; INTEGRATION para apps API).
        // Así conviven el margen del storefront (p. ej. 150%) y el de integración (p. ej. 75%) sin colisionar.
        final PriceRuleChannel channel = PricingChannelHolder.get();
        // País efectivo del comprador (o null): una regla del país gana sobre la equivalente sin país.
        final String country = PricingCountryHolder.get();
        ScopeIds ids = ScopeIds.of(product, variant);

        // El orden de PriceRuleScope.values() ES la precedencia (VARIANT > PRODUCT > … > GLOBAL): en cuanto
        // un ámbito da coincidencia se devuelve, sin mirar los menos específicos.
        for (PriceRuleScope scope : PriceRuleScope.values()) {
            Optional<PriceRuleEntity> match = bestMatch(scope, targetsFor(scope, ids), channel, country, costUsd);
            if (match.isPresent())
                return match;
        }
        return Optional.empty();
    }

    /** Identificadores del producto/variante contra los que se cotejan las reglas de cada ámbito. */
    private record ScopeIds(UUID variantId, UUID productId, UUID supplierId, UUID categoryId) {

        static ScopeIds of(ProductEntity product, ProductVariantEntity variant) {
            UUID supplierId = (product != null && product.getSupplier() != null) ? product.getSupplier().getId() : null;
            UUID categoryId = (product != null && product.getCategory() != null) ? product.getCategory().getId() : null;
            return new ScopeIds(variant != null ? variant.getId() : null, product != null ? product.getId() : null,
                    supplierId, categoryId);
        }
    }

    /**
     * Ids que una regla de ese ámbito tendría que apuntar para aplicar. Un producto puede pertenecer a
     * VARIOS grupos, así que PRODUCT_GROUP casa con un CONJUNTO de scopeIds; los demás ámbitos resuelven a
     * un único id. La pertenencia a grupos solo se consulta si hay alguna regla de grupo activa, para
     * mantener el camino caliente del precio libre de consultas extra.
     */
    private Set<UUID> targetsFor(PriceRuleScope scope, ScopeIds ids) {
        return switch (scope) {
            case VARIANT -> singletonOrEmpty(ids.variantId());
            case PRODUCT -> singletonOrEmpty(ids.productId());
            case PRODUCT_GROUP -> (ids.productId() != null && hasGroupRules()) ? groupIdsOf(ids.productId()) : Set.of();
            case SUPPLIER -> singletonOrEmpty(ids.supplierId());
            case CATEGORY -> singletonOrEmpty(ids.categoryId());
            // Una regla CATEGORY_GROUP aplica si la categoría del producto pertenece a alguno de sus grupos.
            case CATEGORY_GROUP -> (ids.categoryId() != null && hasCategoryGroupRules())
                    ? categoryGroupIdsOf(ids.categoryId())
                    : Set.of();
            case GLOBAL -> Set.of();
        };
    }

    private static Set<UUID> singletonOrEmpty(UUID id) {
        return id == null ? Set.of() : Set.of(id);
    }

    /**
     * DROP-630: cuando varias reglas activas del MISMO ámbito casan con el mismo coste, el orden declarado
     * VARIANT>PRODUCT>… solo desempata entre niveles, no dentro de uno. Se elige de forma determinista:
     * rango de coste más estrecho (más específico) → menor posición → creada más recientemente → id, para
     * que la resolución nunca dependa del orden de iteración.
     */
    private Optional<PriceRuleEntity> bestMatch(PriceRuleScope scope, Set<UUID> targets, PriceRuleChannel channel,
            String country, BigDecimal costUsd) {
        return cache.stream().filter(PriceRuleEntity::isActive)
                .filter(r -> r.getChannel() == channel)
                .filter(r -> countryMatches(r, country))
                .filter(r -> r.getScope() == scope)
                .filter(r -> scope == PriceRuleScope.GLOBAL
                        || (r.getScopeId() != null && targets.contains(r.getScopeId())))
                .filter(r -> matchesCostRange(r, costUsd)).min(MOST_SPECIFIC);
    }

    /**
     * Una regla sin país (country_code null) vale para cualquier país; una regla con país solo aplica si el
     * país efectivo del comprador coincide. Sin país conocido solo casan las reglas sin país.
     */
    private static boolean countryMatches(PriceRuleEntity r, String country) {
        if (r.getCountryCode() == null) {
            return true;
        }
        return country != null && r.getCountryCode().equalsIgnoreCase(country);
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
        moqRepository.findById((short) 1).ifPresent(s -> {
            moqEnabled = s.isEnabled();
            moqFactorPercent = s.getFactorPercent();
        });
        cacheStamp = Instant.now();
    }

    /* ============ Ajuste MOQ (margen a la mitad para productos con MOQ > 1) ============ */

    /** Vista del ajuste MOQ: si está activo y a qué % se reduce el margen que corresponda. */
    public record MoqMarginView(boolean enabled, BigDecimal factorPercent) {}

    public MoqMarginView getMoqMargin() {
        ensureFresh();
        return new MoqMarginView(moqEnabled, moqFactorPercent);
    }

    /** Actualiza el ajuste MOQ (fila única id=1) y refresca la caché para que aplique al instante. */
    @Transactional
    public MoqMarginView updateMoqMargin(boolean enabled, BigDecimal factorPercent) {
        if (factorPercent == null || factorPercent.signum() < 0 || factorPercent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("El porcentaje del ajuste MOQ debe estar entre 0 y 100.");
        }
        MoqMarginSettingEntity e = moqRepository.findById((short) 1).orElseGet(() -> {
            MoqMarginSettingEntity fresh = new MoqMarginSettingEntity();
            fresh.setId((short) 1);
            return fresh;
        });
        e.setEnabled(enabled);
        e.setFactorPercent(factorPercent);
        e.setUpdatedAt(Instant.now());
        moqRepository.save(e);
        invalidateCache();
        return new MoqMarginView(enabled, factorPercent);
    }

    private void ensureFresh() {
        if (Duration.between(cacheStamp, Instant.now()).compareTo(CACHE_TTL) >= 0)
            refresh();
    }

    /** Rango [min,max] abierto por los extremos nulos: sin mínimo o sin máximo la regla no acota ese lado. */
    private static boolean matchesCostRange(PriceRuleEntity r, BigDecimal cost) {
        if (r.getMinCostUsd() != null && cost.compareTo(r.getMinCostUsd()) < 0) {
            return false;
        }
        return r.getMaxCostUsd() == null || cost.compareTo(r.getMaxCostUsd()) <= 0;
    }

    public record PriceWithMargin(BigDecimal costUsd, BigDecimal retailUsd, PriceRuleEntity appliedRule,
            BigDecimal appliedPercentage) {
    }
}
