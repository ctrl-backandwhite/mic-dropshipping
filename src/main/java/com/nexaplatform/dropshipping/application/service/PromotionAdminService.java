package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.in.AdminPromotionDtoIn;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.domain.enums.PromotionKind;
import com.nexaplatform.dropshipping.domain.enums.PromotionScope;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionTargetEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRICING_AMOUNT;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_DETAIL;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_LIST;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_PRODUCT_SUMMARY;
import static com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig.CACHE_SEARCH;

/**
 * Alta, edición y anuncio de promociones.
 *
 * <p>Separado de {@link PromotionService} a propósito: aquel resuelve precios y lo llama el escaparate
 * en cada listado, así que conviene que no arrastre la maquinaria de crear ni de notificar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionAdminService {

    /** Código de error de negocio; el detalle concreto viaja en `detail` para que llegue al admin. */
    private static final String BR = "BR001";

    private final PromotionRepository promotionRepository;
    private final PromotionTargetRepository targetRepository;
    private final CategoryRepository categoryRepository;
    private final NotificationUseCase notificationUseCase;

    @Transactional(readOnly = true)
    public List<PromotionEntity> list() {
        return promotionRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<PromotionTargetEntity> targetsOf(UUID promotionId) {
        return targetRepository.findByPromotionId(promotionId);
    }

    /**
     * Toda promoción que cambia tira las cachés de precio.
     *
     * <p>El precio de escaparate está cacheado por slug+idioma+moneda, así que sin esto una rebaja
     * recién creada no aparece hasta que la entrada expira: se crea la promoción, se mira la tienda y
     * el producto sigue al precio de antes. Mismo tratamiento que las reglas de margen, por el mismo
     * motivo — comprobado en la certificación, donde la rebaja solo salió tras reiniciar.
     */
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true),
            @CacheEvict(value = CACHE_SEARCH, allEntries = true)})
    public PromotionEntity create(AdminPromotionDtoIn req) {
        validate(req, null);
        PromotionEntity p = PromotionEntity.builder()
                .name(req.getName().trim())
                .code(normalizeCode(req.getCode()))
                .kind(parseKind(req.getKind()))
                .scope(parseScope(req.getScope()))
                .percentOff(req.getPercentOff())
                .amountOffCents(req.getAmountOffCents())
                .startsAt(req.getStartsAt())
                .endsAt(req.getEndsAt())
                .active(req.getActive() == null || req.getActive())
                .priority(req.getPriority() == null ? 0 : req.getPriority())
                .maxUses(req.getMaxUses())
                .usedCount(0)
                .minOrderCents(req.getMinOrderCents())
                .createdAt(Instant.now())
                .build();
        p = promotionRepository.save(p);
        replaceTargets(p, req);
        if (Boolean.TRUE.equals(req.getNotifyUsers())) {
            announce(p);
        }
        log.info("Promoción creada: «{}» ({}), alcance {}", p.getName(), describeDiscount(p), p.getScope());
        return p;
    }

    /**
     * Toda promoción que cambia tira las cachés de precio.
     *
     * <p>El precio de escaparate está cacheado por slug+idioma+moneda, así que sin esto una rebaja
     * recién creada no aparece hasta que la entrada expira: se crea la promoción, se mira la tienda y
     * el producto sigue al precio de antes. Mismo tratamiento que las reglas de margen, por el mismo
     * motivo — comprobado en la certificación, donde la rebaja solo salió tras reiniciar.
     */
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true),
            @CacheEvict(value = CACHE_SEARCH, allEntries = true)})
    public PromotionEntity update(UUID id, AdminPromotionDtoIn req) {
        PromotionEntity p = require(id);
        validate(req, p);
        p.setName(req.getName().trim());
        p.setCode(normalizeCode(req.getCode()));
        p.setKind(parseKind(req.getKind()));
        p.setScope(parseScope(req.getScope()));
        p.setPercentOff(req.getPercentOff());
        p.setAmountOffCents(req.getAmountOffCents());
        p.setStartsAt(req.getStartsAt());
        p.setEndsAt(req.getEndsAt());
        if (req.getActive() != null) {
            p.setActive(req.getActive());
        }
        if (req.getPriority() != null) {
            p.setPriority(req.getPriority());
        }
        p.setMaxUses(req.getMaxUses());
        p.setMinOrderCents(req.getMinOrderCents());
        p.setUpdatedAt(Instant.now());
        p = promotionRepository.save(p);
        replaceTargets(p, req);
        if (Boolean.TRUE.equals(req.getNotifyUsers())) {
            announce(p);
        }
        return p;
    }

    /** Activa o pausa. Devolver la fila permite al admin refrescar sin volver a pedir la lista. */
    /**
     * Toda promoción que cambia tira las cachés de precio.
     *
     * <p>El precio de escaparate está cacheado por slug+idioma+moneda, así que sin esto una rebaja
     * recién creada no aparece hasta que la entrada expira: se crea la promoción, se mira la tienda y
     * el producto sigue al precio de antes. Mismo tratamiento que las reglas de margen, por el mismo
     * motivo — comprobado en la certificación, donde la rebaja solo salió tras reiniciar.
     */
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true),
            @CacheEvict(value = CACHE_SEARCH, allEntries = true)})
    public PromotionEntity toggle(UUID id) {
        PromotionEntity p = require(id);
        p.setActive(!p.isActive());
        p.setUpdatedAt(Instant.now());
        return promotionRepository.save(p);
    }

    /**
     * Toda promoción que cambia tira las cachés de precio.
     *
     * <p>El precio de escaparate está cacheado por slug+idioma+moneda, así que sin esto una rebaja
     * recién creada no aparece hasta que la entrada expira: se crea la promoción, se mira la tienda y
     * el producto sigue al precio de antes. Mismo tratamiento que las reglas de margen, por el mismo
     * motivo — comprobado en la certificación, donde la rebaja solo salió tras reiniciar.
     */
    @Transactional
    @Caching(evict = {@CacheEvict(value = CACHE_PRICING_AMOUNT, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_DETAIL, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_SUMMARY, allEntries = true),
            @CacheEvict(value = CACHE_PRODUCT_LIST, allEntries = true),
            @CacheEvict(value = CACHE_SEARCH, allEntries = true)})
    public void delete(UUID id) {
        targetRepository.deleteByPromotionId(id);
        promotionRepository.deleteById(id);
    }

    /**
     * Avisa a los usuarios de que la promoción arranca.
     *
     * <p>El aviso in-app va a TODOS: es información sobre la tienda, no correo comercial. El email
     * promocional es otra cosa y solo puede salir a quien lo consintió — mandarlo a los demás
     * infringe el RGPD. Por eso aquí solo se crea la notificación en plataforma; la campaña por
     * correo se lanza desde Newsletter, que ya respeta el opt-out.
     */
    @Transactional
    public int announce(PromotionEntity p) {
        if (p.getKind() != null && !p.getKind().isAutomatic()) {
            // Anunciar un cupón a todo el mundo lo convierte en un descuento general con pasos de más.
            log.debug("Promoción «{}» es un cupón: no se anuncia en masa", p.getName());
            return 0;
        }
        String titulo = p.getName();
        String cuerpo = "Ya está activa: " + describeDiscount(p) + " en " + describeScope(p)
                + (p.getEndsAt() != null ? ". Hasta el " + p.getEndsAt().toString().substring(0, 10) : "") + ".";
        int enviadas = notificationUseCase.sendAdminNotification("all", titulo, cuerpo);
        log.info("Promoción «{}» anunciada a {} usuario(s)", p.getName(), enviadas);
        return enviadas;
    }

    private void replaceTargets(PromotionEntity p, AdminPromotionDtoIn req) {
        targetRepository.deleteByPromotionId(p.getId());
        if (p.getScope() == PromotionScope.ALL) {
            return;
        }
        List<PromotionTargetEntity> filas = new ArrayList<>();
        if (p.getScope() == PromotionScope.CATEGORY && req.getCategoryIds() != null) {
            req.getCategoryIds().forEach(c -> filas.add(
                    PromotionTargetEntity.builder().promotionId(p.getId()).categoryId(c).build()));
        }
        if (p.getScope() == PromotionScope.PRODUCT && req.getProductIds() != null) {
            req.getProductIds().forEach(pr -> filas.add(
                    PromotionTargetEntity.builder().promotionId(p.getId()).productId(pr).build()));
        }
        targetRepository.saveAll(filas);
    }

    /**
     * Comprueba lo que la base de datos también impide, pero con un mensaje que se entienda.
     *
     * <p>Sin esto el admin recibiría un error de violación de restricción, que no dice qué corregir.
     */
    private void validate(AdminPromotionDtoIn req, PromotionEntity existing) {
        boolean hasPercent = req.getPercentOff() != null;
        boolean hasAmount = req.getAmountOffCents() != null;
        if (hasPercent == hasAmount) {
            throw new BusinessException(BR, List.of("Indica un porcentaje O un importe fijo, no ambos ni ninguno"));
        }
        if (hasPercent && (req.getPercentOff().compareTo(BigDecimal.ZERO) <= 0
                || req.getPercentOff().compareTo(BigDecimal.valueOf(100)) >= 0)) {
            throw new BusinessException(BR, List.of("El porcentaje tiene que estar entre 1 y 99"));
        }
        if (hasAmount && req.getAmountOffCents() <= 0) {
            throw new BusinessException(BR, List.of("El importe del descuento tiene que ser mayor que cero"));
        }
        if (req.getStartsAt() != null && req.getEndsAt() != null && !req.getEndsAt().isAfter(req.getStartsAt())) {
            throw new BusinessException(BR, List.of("La fecha de fin tiene que ser posterior a la de inicio"));
        }
        PromotionScope scope = parseScope(req.getScope());
        if (scope == PromotionScope.CATEGORY && (req.getCategoryIds() == null || req.getCategoryIds().isEmpty())) {
            throw new BusinessException(BR, List.of("Elige al menos una categoría"));
        }
        if (scope == PromotionScope.PRODUCT && (req.getProductIds() == null || req.getProductIds().isEmpty())) {
            throw new BusinessException(BR, List.of("Elige al menos un producto"));
        }
        String code = normalizeCode(req.getCode());
        if (code != null) {
            promotionRepository.findByCodeIgnoreCase(code)
                    .filter(other -> existing == null || !other.getId().equals(existing.getId()))
                    .ifPresent(other -> {
                        throw new BusinessException(BR, List.of("Ya existe un cupón con el código " + code));
                    });
        }
    }

    /** En mayúsculas para que «verano25» y «VERANO25» sean el mismo cupón. */
    private static String normalizeCode(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    private static PromotionKind parseKind(String v) {
        try {
            return v == null || v.isBlank() ? PromotionKind.SEASONAL
                    : PromotionKind.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(BR, List.of("Tipo de promoción no válido: " + v));
        }
    }

    private static PromotionScope parseScope(String v) {
        try {
            return v == null || v.isBlank() ? PromotionScope.ALL
                    : PromotionScope.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(BR, List.of("Alcance no válido: " + v));
        }
    }

    private String describeDiscount(PromotionEntity p) {
        if (p.getPercentOff() != null) {
            return "-" + p.getPercentOff().stripTrailingZeros().toPlainString() + "%";
        }
        return "-" + BigDecimal.valueOf(p.getAmountOffCents()).movePointLeft(2).toPlainString();
    }

    private String describeScope(PromotionEntity p) {
        if (p.getScope() == PromotionScope.ALL) {
            return "toda la tienda";
        }
        List<PromotionTargetEntity> targets = targetRepository.findByPromotionId(p.getId());
        if (p.getScope() == PromotionScope.PRODUCT) {
            return targets.size() + (targets.size() == 1 ? " producto" : " productos");
        }
        // Los nombres de las categorías, que es lo que el usuario reconoce: «5 categorías» no le dice
        // si la rebaja le interesa.
        List<String> nombres = targets.stream().map(PromotionTargetEntity::getCategoryId)
                .map(id -> categoryRepository.findById(id).map(c -> c.getNameZh() != null ? c.getNameZh()
                        : c.getSlug()).orElse(null))
                .filter(java.util.Objects::nonNull).limit(4).toList();
        return nombres.isEmpty() ? "categorías seleccionadas" : String.join(", ", nombres);
    }

    private PromotionEntity require(UUID id) {
        return promotionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("No existe la promoción " + id));
    }
}
