package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.application.usecase.WinningProductUseCase;
import com.nexaplatform.dropshipping.domain.model.WinningProduct;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Catalog-intelligence read use case (DROP-8). Holds the ranking/projection logic
 * that used to live in {@code IntelligenceController}: best sellers by monthly
 * sales (optionally category-filtered) and winning products by trend score. Builds
 * the read-only {@link WinningProduct} model, resolving the title in the user's
 * language and picking the primary image.
 */
@Service
@RequiredArgsConstructor
public class WinningProductUseCaseImpl implements WinningProductUseCase {

    private final ProductRepository productRepository;

    @Override
    @Transactional(readOnly = true)
    public List<WinningProduct> salesTrends(UUID categoryId, int limit, String lang) {
        return productRepository.findAll().stream()
                .filter(p -> "ACTIVE".equals(p.getStatus() == null ? null : p.getStatus().name()))
                .filter(p -> categoryId == null
                        || (p.getCategory() != null && categoryId.equals(p.getCategory().getId())))
                .sorted((a, b) -> Integer.compare(b.getMonthlySales(), a.getMonthlySales())).limit(Math.min(limit, 100))
                .map(p -> toWin(p, lang)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WinningProduct> winning(int limit, String lang) {
        return productRepository.findAll().stream()
                .filter(p -> "ACTIVE".equals(p.getStatus() == null ? null : p.getStatus().name())).sorted((a, b) -> {
                    BigDecimal sa = a.getTrendScore() == null ? BigDecimal.ZERO : a.getTrendScore();
                    BigDecimal sb = b.getTrendScore() == null ? BigDecimal.ZERO : b.getTrendScore();
                    return sb.compareTo(sa);
                }).limit(Math.min(limit, 100)).map(p -> toWin(p, lang)).toList();
    }

    // DROP-541: usa la traducción en el idioma del usuario en lugar del título chino.
    private WinningProduct toWin(ProductEntity p, String lang) {
        String img = null;
        try {
            if (p.getImages() != null && !p.getImages().isEmpty()) {
                ProductImageEntity im = p.getImages().get(0);
                img = im.getCdnUrl() != null && !im.getCdnUrl().isBlank() ? im.getCdnUrl() : im.getSourceUrl();
            }
        } catch (Exception ignored) {
            // La galería del producto es una colección perezosa: si la sesión ya está cerrada, el
            // producto ganador se muestra sin foto en lugar de tumbar la lista entera.
        }
        String title = null;
        if (p.getTranslations() != null) {
            title = p.getTranslations().stream()
                    .filter(t -> lang.equalsIgnoreCase(t.getLanguage()) && t.getTitle() != null).map(t -> t.getTitle())
                    .findFirst().orElse(null);
            if (title == null) {
                title = p.getTranslations().stream()
                        .filter(t -> "en".equalsIgnoreCase(t.getLanguage()) && t.getTitle() != null)
                        .map(t -> t.getTitle()).findFirst().orElse(null);
            }
        }
        if (title == null) {
            title = p.getTitleZh();
        }
        // `basePrice` es lo que le pagamos al proveedor, en yuanes. Solo sale para ADMIN, igual que en el
        // listado y en la ficha (ProductMapper): publicarlo junto al precio de venta que el escaparate ya
        // enseña permite a cualquiera calcular la ganancia exacta por producto. Y este camino cuelga de
        // /api/me/intelligence/**, que solo exige tener cuenta: lo veía cualquier cliente registrado.
        boolean admin = SecurityUtils.isAdmin();
        return WinningProduct.builder().slug(p.getSlug()).title(title).monthlySales(p.getMonthlySales())
                .trendScore(p.getTrendScore()).mainImage(img).price(admin ? p.getBasePrice() : null).build();
    }
}
