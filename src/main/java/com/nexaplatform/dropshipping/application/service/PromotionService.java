package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.PromotionScope;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionRedemptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionTargetEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionRedemptionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Decide qué rebaja se aplica a un producto y cuánto descuenta.
 *
 * <p>Tres reglas de negocio gobiernan todo lo demás, y las tres se decidieron a propósito:
 *
 * <ol>
 *   <li><b>Gana la mayor, no se acumulan.</b> Con una rebaja de temporada y un cupón, se aplica el
 *       descuento más ventajoso para el cliente, pero solo uno. Encadenarlos (-40% y luego -20% sobre
 *       el ya rebajado) da un -52% real que nadie ha decidido.</li>
 *   <li><b>Nunca por debajo de coste.</b> Si el descuento dejara el precio por debajo de lo que cuesta
 *       el producto más su envío, se recorta hasta ese suelo. Un cero de más al teclear un porcentaje
 *       no puede convertirse en ventas a pérdida.</li>
 *   <li><b>Los cupones no se anuncian.</b> Solo las promociones automáticas rebajan el precio del
 *       escaparate; un cupón aparece cuando el cliente lo teclea, no antes.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionService {

    private final PromotionRepository promotionRepository;
    private final PromotionTargetRepository targetRepository;
    private final CategoryRepository categoryRepository;
    private final PromotionRedemptionRepository redemptionRepository;

    /**
     * Precio rebajado y de dónde sale la rebaja.
     *
     * @param original    lo que costaría sin promoción
     * @param finalAmount lo que se cobra
     * @param percentOff  descuento REAL aplicado, ya recortado por el suelo de coste: es el que se
     *                    enseña, porque anunciar el −50% nominal cuando el suelo dejó un −30% sería
     *                    mentir en el escaparate
     */
    public record Discounted(BigDecimal original, BigDecimal finalAmount, BigDecimal percentOff,
                             String promotionName, UUID promotionId) {

        /** ¿Hay rebaja de verdad? Un descuento que el suelo dejó en nada no se enseña. */
        public boolean applies() {
            return percentOff != null && percentOff.signum() > 0;
        }
    }

    /**
     * Aplica al precio la mejor promoción AUTOMÁTICA del producto.
     *
     * @param floor suelo por debajo del cual no se baja (coste + envío). Nulo = sin suelo.
     */
    @Transactional(readOnly = true)
    public Discounted applyAutomatic(ProductEntity product, BigDecimal price, BigDecimal floor) {
        List<PromotionEntity> live = promotionRepository.findLive(Instant.now()).stream()
                .filter(p -> p.getKind().isAutomatic())
                .toList();
        return applyBest(product, price, floor, live);
    }

    /**
     * Aplica la mejor entre las automáticas y un cupón concreto.
     *
     * <p>Aquí es donde se materializa el «no se acumulan»: el cupón entra como un candidato más y gana
     * el que más descuente. Si el cupón es peor que la rebaja que ya tenía el producto, el cliente se
     * queda con la rebaja —nunca sale perdiendo por canjear un código.
     */
    @Transactional(readOnly = true)
    public Discounted applyWithCoupon(ProductEntity product, BigDecimal price, BigDecimal floor,
                                      PromotionEntity coupon) {
        List<PromotionEntity> candidates = new ArrayList<>(promotionRepository.findLive(Instant.now()).stream()
                .filter(p -> p.getKind().isAutomatic())
                .toList());
        if (coupon != null) {
            candidates.add(coupon);
        }
        return applyBest(product, price, floor, candidates);
    }

    /** Resuelve un cupón por código; vacío si no existe o no está vigente. */
    @Transactional(readOnly = true)
    public Optional<PromotionEntity> findLiveCoupon(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return promotionRepository.findByCodeIgnoreCase(code.trim())
                .filter(p -> p.isLiveAt(Instant.now()));
    }

    /** Por qué un cupón no se puede usar, en un texto que el cliente entienda. */
    public record CouponCheck(boolean valid, String reason, PromotionEntity promotion) {
    }

    /**
     * Comprueba un cupón para UNA persona y UN pedido.
     *
     * <p>Cuatro condiciones, y las cuatro tienen que decir por qué fallan: un «cupón no válido» a secas
     * hace que el cliente lo reintente, escriba a soporte o abandone el carrito.
     */
    @Transactional(readOnly = true)
    public CouponCheck checkCoupon(String code, UUID userId, int subtotalCents) {
        Optional<PromotionEntity> found = promotionRepository.findByCodeIgnoreCase(
                code == null ? "" : code.trim());
        if (found.isEmpty()) {
            return new CouponCheck(false, "Ese código no existe", null);
        }
        PromotionEntity p = found.get();
        Instant now = Instant.now();
        if (!p.isActive()) {
            return new CouponCheck(false, "Ese cupón ya no está disponible", p);
        }
        if (p.getStartsAt() != null && now.isBefore(p.getStartsAt())) {
            return new CouponCheck(false, "Ese cupón todavía no ha empezado", p);
        }
        if (p.getEndsAt() != null && !now.isBefore(p.getEndsAt())) {
            return new CouponCheck(false, "Ese cupón ha caducado", p);
        }
        if (p.getMaxUses() != null && p.getUsedCount() >= p.getMaxUses()) {
            return new CouponCheck(false, "Ese cupón se ha agotado", p);
        }
        // Cupón nominativo: comprobarlo evita que se comparta y lo canjee cualquiera.
        if (p.getUserId() != null && !p.getUserId().equals(userId)) {
            return new CouponCheck(false, "Ese cupón no está disponible para tu cuenta", p);
        }
        if (p.getMaxUsesPerUser() != null && userId != null
                && redemptionRepository.countByPromotionIdAndUserId(p.getId(), userId) >= p.getMaxUsesPerUser()) {
            return new CouponCheck(false, "Ya has usado ese cupón", p);
        }
        if (p.getMinOrderCents() != null && subtotalCents < p.getMinOrderCents()) {
            BigDecimal min = BigDecimal.valueOf(p.getMinOrderCents()).movePointLeft(2);
            return new CouponCheck(false, "Ese cupón necesita un pedido mínimo de " + min, p);
        }
        return new CouponCheck(true, null, p);
    }

    /**
     * Deja constancia del canje.
     *
     * <p>Sube el contador global Y guarda la fila del canje: sin la fila no hay forma de saber cuántas
     * veces lo ha usado UNA persona, que es lo que sostiene el tope por usuario.
     */
    @Transactional
    public void recordUse(UUID promotionId, UUID userId, UUID orderId, int amountCents) {
        promotionRepository.findById(promotionId).ifPresent(p -> {
            if (orderId != null && redemptionRepository.existsByPromotionIdAndOrderId(promotionId, orderId)) {
                return;   // reintento de pago: el descuento ya estaba anotado
            }
            p.setUsedCount(p.getUsedCount() + 1);
            p.setUpdatedAt(Instant.now());
            promotionRepository.save(p);
            if (userId != null) {
                redemptionRepository.save(PromotionRedemptionEntity.builder()
                        .promotionId(promotionId).userId(userId).orderId(orderId)
                        .amountCents(amountCents).redeemedAt(Instant.now()).build());
            }
        });
    }

    private Discounted applyBest(ProductEntity product, BigDecimal price, BigDecimal floor,
                                 List<PromotionEntity> candidates) {
        if (price == null || price.signum() <= 0 || candidates.isEmpty()) {
            return none(price);
        }
        List<PromotionEntity> aplicables = candidates.stream()
                .filter(p -> reaches(p, product))
                .toList();
        if (aplicables.isEmpty()) {
            return none(price);
        }
        PromotionEntity best = null;
        BigDecimal bestPrice = price;
        for (PromotionEntity p : aplicables) {
            BigDecimal candidate = priceAfter(price, p);
            // Mayor descuento = precio más bajo. Empate: manda la prioridad configurada.
            int cmp = candidate.compareTo(bestPrice);
            if (cmp < 0 || (cmp == 0 && best != null && p.getPriority() > best.getPriority())) {
                best = p;
                bestPrice = candidate;
            }
        }
        if (best == null) {
            return none(price);
        }
        // El suelo se aplica DESPUÉS de elegir, no antes: si no, una promoción agresiva quedaría
        // recortada al suelo y perdería frente a otra menor que no llega a tocarlo.
        if (floor != null && floor.signum() > 0 && bestPrice.compareTo(floor) < 0) {
            log.debug("Promoción «{}» recortada al suelo de coste: {} -> {}", best.getName(), bestPrice, floor);
            bestPrice = floor;
        }
        if (bestPrice.compareTo(price) >= 0) {
            return none(price);   // el suelo se comió la rebaja entera
        }
        BigDecimal percent = price.subtract(bestPrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(price, 0, RoundingMode.DOWN);
        return new Discounted(price, bestPrice, percent, best.getName(), best.getId());
    }

    /** Precio tras aplicar UNA promoción, sin tocar el suelo todavía. */
    private BigDecimal priceAfter(BigDecimal price, PromotionEntity p) {
        if (p.getPercentOff() != null) {
            BigDecimal factor = BigDecimal.ONE.subtract(
                    p.getPercentOff().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            return price.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        }
        if (p.getAmountOffCents() != null) {
            BigDecimal off = BigDecimal.valueOf(p.getAmountOffCents()).movePointLeft(2);
            BigDecimal out = price.subtract(off);
            return out.signum() > 0 ? out.setScale(2, RoundingMode.HALF_UP) : price;
        }
        return price;
    }

    /**
     * ¿Esta promoción alcanza a este producto?
     *
     * <p>Con alcance CATEGORY cuenta también la jerarquía: una rebaja sobre «Ropa de mujer» tiene que
     * alcanzar a «Vestidos», que cuelga de ella. Si no, habría que listar cada subcategoría a mano y
     * cualquier categoría nueva se quedaría fuera sin que nadie se entere.
     */
    private boolean reaches(PromotionEntity p, ProductEntity product) {
        if (p.getScope() == PromotionScope.ALL) {
            return true;
        }
        List<PromotionTargetEntity> targets = targetRepository.findByPromotionId(p.getId());
        if (p.getScope() == PromotionScope.PRODUCT) {
            return targets.stream().anyMatch(t -> product.getId().equals(t.getProductId()));
        }
        Set<UUID> wanted = targets.stream().map(PromotionTargetEntity::getCategoryId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (wanted.isEmpty() || product.getCategory() == null) {
            return false;
        }
        return ancestorsOf(product.getCategory().getId()).stream().anyMatch(wanted::contains);
    }

    /** La categoría del producto y todas sus ascendientes hasta la raíz. */
    private Set<UUID> ancestorsOf(UUID categoryId) {
        Set<UUID> chain = new HashSet<>();
        UUID current = categoryId;
        // Tope de profundidad: una jerarquía con un ciclo por un dato mal metido colgaría el listado.
        for (int depth = 0; current != null && depth < 12 && chain.add(current); depth++) {
            current = categoryRepository.findById(current)
                    .map(CategoryEntity::getParent).map(CategoryEntity::getId).orElse(null);
        }
        return chain;
    }

    private static Discounted none(BigDecimal price) {
        return new Discounted(price, price, BigDecimal.ZERO, null, null);
    }

    /** Promociones automáticas vivas, para que el admin vea de un vistazo qué está corriendo. */
    @Transactional(readOnly = true)
    public Map<UUID, PromotionEntity> liveById() {
        return promotionRepository.findLive(Instant.now()).stream()
                .collect(Collectors.toMap(PromotionEntity::getId, p -> p));
    }
}
