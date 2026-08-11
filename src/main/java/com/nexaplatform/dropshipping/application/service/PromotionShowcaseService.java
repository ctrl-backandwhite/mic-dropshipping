package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.LivePromotionView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.domain.enums.PromotionScope;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionTargetEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Las rebajas vivas tal y como se anuncian en la portada.
 *
 * <p>Solo salen las automáticas: un cupón exige teclear un código, y anunciarlo en el escaparate lo
 * convertiría en un descuento para todo el mundo.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromotionShowcaseService {

    /** Cuántos productos acompañan a cada rebaja en el carrusel. Suficientes para llenar la tira. */
    private static final int ESCAPARATE = 12;

    private final PromotionRepository promotionRepository;
    private final PromotionTargetRepository targetRepository;
    private final CatalogStorefrontReadService storefrontRead;

    /**
     * Las rebajas vigentes ahora, de la que más descuenta a la que menos.
     *
     * <p>Cada una viene con una muestra de los productos a los que alcanza, para que el banner pueda
     * enseñar mercancía y no solo un cartel.
     */
    @Transactional(readOnly = true)
    public List<LivePromotionView> live(String lang) {
        Instant now = Instant.now();
        List<PromotionEntity> vivas = promotionRepository.findAll().stream()
                .filter(p -> p.getCode() == null || p.getCode().isBlank())
                .filter(p -> p.isLiveAt(now))
                .sorted(Comparator.comparing(this::descuentoAparente).reversed())
                .toList();

        List<LivePromotionView> out = new ArrayList<>();
        for (PromotionEntity p : vivas) {
            out.add(new LivePromotionView(p.getId(), p.getName(),
                    p.getPercentOff() != null ? p.getPercentOff().intValue() : null,
                    p.getEndsAt() != null ? p.getEndsAt().toString() : null,
                    p.getScope() != null ? p.getScope().name() : null,
                    muestraDe(p, lang)));
        }
        return out;
    }

    /**
     * Con qué fuerza rebaja, para ordenar el carrusel.
     *
     * <p>Un descuento de importe fijo no es comparable con uno porcentual sin conocer el precio, así
     * que va al final: en el banner interesa destacar el «-40 %», no el «-2 €».
     */
    private int descuentoAparente(PromotionEntity p) {
        return p.getPercentOff() != null ? p.getPercentOff().intValue() : 0;
    }

    private List<ProductSummaryView> muestraDe(PromotionEntity p, String lang) {
        try {
            if (p.getScope() == PromotionScope.PRODUCT) {
                List<UUID> ids = targetRepository.findByPromotionId(p.getId()).stream()
                        .map(PromotionTargetEntity::getProductId).filter(Objects::nonNull).toList();
                return storefrontRead.favorites(ids, 0, ESCAPARATE, lang).items();
            }
            if (p.getScope() == PromotionScope.CATEGORY) {
                UUID categoria = targetRepository.findByPromotionId(p.getId()).stream()
                        .map(PromotionTargetEntity::getCategoryId).filter(Objects::nonNull).findFirst().orElse(null);
                if (categoria == null) {
                    return List.of();
                }
                return storefrontRead.productList(0, ESCAPARATE, lang, null, categoria, null, null, null, "trending")
                        .items();
            }
            return storefrontRead.productList(0, ESCAPARATE, lang, null, null, null, null, null, "trending")
                    .items();
        } catch (RuntimeException e) {
            // El banner es decoración: si el catálogo falla, se anuncia la rebaja sin fotos antes que
            // tumbar la portada entera.
            log.warn("No se pudo cargar la muestra de productos de la promoción {}: {}", p.getId(), e.toString());
            return List.of();
        }
    }
}
