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
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
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
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository productRepository;

    /**
     * Claves con las que se guarda, DENTRO de la petición en curso, lo que no cambia durante ella.
     *
     * <p>Se probó antes con una memoria de unos segundos y estaba mal, por un motivo que las pruebas
     * unitarias no podían ver: una promoción metida por SQL —una migración, un arreglo a mano, o los
     * propios tests de integración, que insertan con {@code jdbcTemplate}— no pasa por el panel y por
     * tanto no puede avisar de que hay que refrescar. La lista se quedaba vieja sin que nadie lo
     * supiera. Cinco pruebas de cupones lo destaparon.
     *
     * <p>Con el alcance de la PETICIÓN no hay nada que invalidar y no hay dato viejo posible: cada
     * petición pregunta una vez y reutiliza esa respuesta para todos sus productos, que es exactamente
     * donde estaba el problema — un listado pinta hasta 5.000 fichas y una ficha hasta 280 variantes,
     * y todas preguntaban lo mismo.
     */
    private static final String MEMO_VIVAS = PromotionService.class.getName() + ".vivas";
    private static final String MEMO_DESTINOS = PromotionService.class.getName() + ".destinos";
    private static final String MEMO_ANCESTROS = PromotionService.class.getName() + ".ancestros";

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

        /** Sin rebaja: el precio se queda como está (canal de integración, o producto sin promoción). */
        public static Discounted none(BigDecimal price) {
            return new Discounted(price, price, BigDecimal.ZERO, null, null);
        }
    }

    /**
     * Aplica al precio la mejor promoción AUTOMÁTICA del producto.
     *
     * @param floor suelo por debajo del cual no se baja (coste + envío). Nulo = sin suelo.
     */
    @Transactional(readOnly = true)
    public Discounted applyAutomatic(ProductEntity product, BigDecimal price, BigDecimal floor) {
        List<PromotionEntity> live = automaticasVivas();
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
        List<PromotionEntity> candidates = new ArrayList<>(automaticasVivas());
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
        boolean recortada = false;
        if (floor != null && floor.signum() > 0 && bestPrice.compareTo(floor) < 0) {
            log.debug("Promoción «{}» recortada al suelo: {} -> {}", best.getName(), bestPrice, floor);
            bestPrice = floor;
            recortada = true;
        }
        if (bestPrice.compareTo(price) >= 0) {
            return none(price);   // el suelo se comió la rebaja entera
        }
        // El porcentaje que se ANUNCIA es el de la regla —el «-30%» que configuró el admin—, no el que
        // sale de dividir importes ya redondeados al céntimo: con precios pequeños ese cálculo daba
        // -29% en unos productos y -30% en otros con la MISMA promoción, y el escaparate parecía
        // aplicar descuentos distintos a cada uno.
        //
        // La excepción es el suelo: si ha recortado la rebaja, el descuento REAL es menor que el
        // nominal y hay que decir el real. Anunciar el -90% de la regla cuando solo se aplicó un -35%
        // sería mentir en el escaparate.
        BigDecimal percent;
        if (recortada || best.getPercentOff() == null) {
            percent = price.subtract(bestPrice).multiply(BigDecimal.valueOf(100))
                    .divide(price, 0, RoundingMode.DOWN);
        } else {
            percent = best.getPercentOff().setScale(0, RoundingMode.DOWN);
        }
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
        List<PromotionTargetEntity> targets = destinosDe(p.getId());
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

    /**
     * Céntimos de precio de tarifa (gross) del carrito que un cupón REALMENTE alcanza.
     *
     * <p>Para cupones con alcance PRODUCT/CATEGORY solo cuentan las líneas de productos que entran en el
     * cupón; para alcance global (ALL) cuenta todo el carrito. Es la base sobre la que el checkout calcula
     * el descuento del cupón, para que un cupón de "producto X / categoría Y" NO rebaje el carrito entero.
     */
    @Transactional(readOnly = true)
    public int reachableGrossCents(PromotionEntity coupon, Map<UUID, Integer> grossByProduct) {
        if (coupon == null || grossByProduct == null || grossByProduct.isEmpty()) {
            return 0;
        }
        if (coupon.getScope() == PromotionScope.ALL) {
            return grossByProduct.values().stream().mapToInt(Integer::intValue).sum();
        }
        int base = 0;
        for (Map.Entry<UUID, Integer> e : grossByProduct.entrySet()) {
            ProductEntity product = productRepository.findById(e.getKey()).orElse(null);
            if (product != null && reaches(coupon, product)) {
                base += e.getValue();
            }
        }
        return base;
    }

    /**
     * Un predicado «¿este producto entra en la promoción X?», para listar en el catálogo solo los
     * productos de una rebaja concreta (el botón «Ver los productos» del banner).
     *
     * <p>Vacío si la promoción no existe o no está viva: la vista, al no recibir filtro, cae a mostrar
     * el catálogo entero en vez de una lista vacía sin explicación. Los targets se cargan UNA vez y los
     * ancestros de categoría se memoizan, para no consultar por cada uno de los miles de productos.
     */
    @Transactional(readOnly = true)
    public Optional<java.util.function.Predicate<ProductEntity>> reachFilter(UUID promotionId) {
        if (promotionId == null) {
            return Optional.empty();
        }
        Optional<PromotionEntity> found = promotionRepository.findById(promotionId)
                .filter(p -> p.isLiveAt(Instant.now()));
        // Promoción global: alcanza a TODO el catálogo, así que no hay nada que filtrar y se evita el
        // barrido en memoria. Devolver un predicado «siempre true» funcionaría, pero pagaría el scan.
        if (found.isEmpty() || found.get().getScope() == PromotionScope.ALL) {
            return Optional.empty();
        }
        return found
                .map(p -> {
                    List<PromotionTargetEntity> targets = targetRepository.findByPromotionId(p.getId());
                    if (p.getScope() == PromotionScope.PRODUCT) {
                        Set<UUID> ids = targets.stream().map(PromotionTargetEntity::getProductId)
                                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                        return product -> ids.contains(product.getId());
                    }
                    Set<UUID> wanted = targets.stream().map(PromotionTargetEntity::getCategoryId)
                            .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
                    Map<UUID, Set<UUID>> ancestorCache = new java.util.HashMap<>();
                    return product -> product.getCategory() != null && ancestorCache
                            .computeIfAbsent(product.getCategory().getId(), this::ancestorsOf).stream()
                            .anyMatch(wanted::contains);
                });
    }

    /** La categoría del producto y todas sus ascendientes hasta la raíz. */
    @SuppressWarnings("unchecked")
    private Set<UUID> ancestorsOf(UUID categoryId) {
        Map<UUID, Set<UUID>> memo = (Map<UUID, Set<UUID>>) recordar(MEMO_ANCESTROS, HashMap::new);
        return memo.computeIfAbsent(categoryId, this::cadenaDeAncestros);
    }

    private Set<UUID> cadenaDeAncestros(UUID categoryId) {
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
        return Discounted.none(price);
    }

    /* ============ Memoria dentro de la petición ============ */

    /**
     * Las promociones automáticas vigentes: se preguntan UNA vez por petición, no una por producto.
     *
     * <p>Esto es lo que hacía lento el escaparate. El listado con filtro de precio barre hasta 5.000
     * fichas y de cada una calcula el precio, y calcular un precio preguntaba a la base de datos qué
     * promociones están vivas: la misma respuesta, cinco mil veces. Medido en PRE el 5-sep-2026 con el
     * catálogo real (7.710 referencias) y con CERO promociones dadas de alta, una página de 24
     * productos tardaba <b>78 segundos</b> — todo el tiempo se iba en preguntar una y otra vez por una
     * tabla vacía. En la ficha pasa lo mismo por VARIANTE: 22,7 de media en producción, 280 la peor.
     *
     * <p>Fuera de una petición HTTP —un consumidor de Kafka, una tarea programada— no hay dónde
     * guardar nada y se consulta como siempre. Ahí no hay miles de productos seguidos, así que no
     * hace falta.
     */
    @SuppressWarnings("unchecked")
    private List<PromotionEntity> automaticasVivas() {
        return (List<PromotionEntity>) recordar(MEMO_VIVAS, this::consultaAutomaticasVivas);
    }

    private List<PromotionEntity> consultaAutomaticasVivas() {
        return promotionRepository.findLive(Instant.now()).stream()
                .filter(p -> p.getKind().isAutomatic())
                .toList();
    }

    /** Los destinos de una promoción (productos o categorías), preguntados una vez por petición. */
    @SuppressWarnings("unchecked")
    private List<PromotionTargetEntity> destinosDe(UUID promotionId) {
        Map<UUID, List<PromotionTargetEntity>> memo =
                (Map<UUID, List<PromotionTargetEntity>>) recordar(MEMO_DESTINOS, HashMap::new);
        return memo.computeIfAbsent(promotionId, targetRepository::findByPromotionId);
    }

    /**
     * Guarda algo en la petición en curso y lo reutiliza mientras dure.
     *
     * <p>Se usa el alcance de la PETICIÓN y no una ventana de tiempo a propósito. Con una ventana, una
     * promoción metida por SQL —una migración, un arreglo a mano, los tests de integración— no tiene
     * forma de avisar de que hay que refrescar, y la lista se queda vieja sin que nadie lo note. Aquí
     * no hay nada que invalidar: la petición siguiente vuelve a preguntar.
     */
    private Object recordar(String clave, Supplier<Object> calcula) {
        RequestAttributes peticion = RequestContextHolder.getRequestAttributes();
        if (peticion == null) {
            return calcula.get();
        }
        Object guardado = peticion.getAttribute(clave, RequestAttributes.SCOPE_REQUEST);
        if (guardado == null) {
            guardado = calcula.get();
            peticion.setAttribute(clave, guardado, RequestAttributes.SCOPE_REQUEST);
        }
        return guardado;
    }

    /** Promociones automáticas vivas, para que el admin vea de un vistazo qué está corriendo. */
    @Transactional(readOnly = true)
    public Map<UUID, PromotionEntity> liveById() {
        return promotionRepository.findLive(Instant.now()).stream()
                .collect(Collectors.toMap(PromotionEntity::getId, p -> p));
    }
}
