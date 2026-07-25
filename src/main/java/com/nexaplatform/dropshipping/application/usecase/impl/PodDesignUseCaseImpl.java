package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.PodDesignUseCase;
import com.nexaplatform.dropshipping.domain.model.PodAiResult;
import com.nexaplatform.dropshipping.domain.model.PodBlankProduct;
import com.nexaplatform.dropshipping.domain.model.PodDesign;
import com.nexaplatform.dropshipping.domain.repository.PodDesignRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Print-on-demand use case (DROP-6). Operates on the {@link PodDesign} model and
 * delegates persistence to the domain port. Holds the logic that used to live in
 * {@code PlatformExtrasService}: building the storefront blank-product cards from
 * the catalog (translation-resolved title and main image), rendering a design and
 * the mocked AI generation. The legacy {@link ProductRepository} is kept as a
 * read-side collaborator for the catalog projection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PodDesignUseCaseImpl implements PodDesignUseCase {

    private final PodDesignRepository podDesignRepository;
    private final ProductRepository productRepository;

    // DROP-641: a "blank product" must be an imprintable/customizable article (apparel, mugs,
    // totes, phone cases, posters, hoodies...). The seed sets pod_enabled=true across every
    // category, so that flag alone leaks non-imprintable items (kitchen knife sets, cosmetics/
    // serums, electronics, kitchen scenes). We additionally gate blanks by a category-slug
    // allowlist of POD-compatible categories. Slugs not present today (mugs/totes/posters/...)
    // are kept so future printable categories work without another code change.
    private static final Set<String> POD_BLANK_CATEGORY_SLUGS = Set.of(
            "fashion-apparel", // T-shirts, hoodies, caps, socks, scarves, tote bags
            "apparel", "ropa",
            "mugs", "tazas",
            "totes", "bags", "bolsas",
            "phone-cases", "cases", "fundas",
            "posters", "posters-prints",
            "hoodies", "t-shirts", "tshirts");

    private static boolean isPodBlankCategory(ProductEntity p) {
        return p.getCategory() != null && p.getCategory().getSlug() != null
                && POD_BLANK_CATEGORY_SLUGS.contains(p.getCategory().getSlug().toLowerCase());
    }

    // Real, reachable apparel/product mockup photos (DROP-599: the old cdn.nx036.local URLs were
    // fictional and rendered broken). One is picked deterministically so a design keeps its mockup.
    private static final String[] MOCKUPS = {
            "https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?w=600",
            "https://images.unsplash.com/photo-1556821840-3a63f95609a7?w=600",
            "https://images.unsplash.com/photo-1591561954557-26941169b49e?w=600",
            "https://images.unsplash.com/photo-1583743814966-8936f5b7be1a?w=600",
            "https://images.unsplash.com/photo-1514228742587-6b1558fcca3d?w=600",
            "https://images.unsplash.com/photo-1503341960582-b45751874cf0?w=600" };

    private static String mockupFor(Object key) {
        return MOCKUPS[Math.floorMod(Objects.hashCode(key), MOCKUPS.length)];
    }

    @Override
    @Transactional(readOnly = true)
    public List<PodBlankProduct> blanks(String lang) {
        return productRepository.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getPodEnabled()))
                .filter(PodDesignUseCaseImpl::isPodBlankCategory)
                .map(p -> tinyProduct(p, lang)).toList();
    }

    @Override
    @Transactional
    public PodDesign create(UUID userId, PodDesign model) {
        if (!productRepository.existsById(model.getProductId())) {
            throw new NotFoundException("Product");
        }
        model.setUserId(userId);
        if (model.getCanvasJson() == null) {
            model.setCanvasJson(new HashMap<>());
        }
        model.setMockupUrl(mockupFor(model.getName() != null ? model.getName() : UUID.randomUUID()));
        model.setStatus("RENDERED");
        PodDesign saved = podDesignRepository.save(model);
        log.info("::> [POD] Design created id={}", saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public void deleteDesign(UUID userId, UUID id) {
        PodDesign design = podDesignRepository.getById(id);
        if (design == null || !userId.equals(design.getUserId())) {
            throw new NotFoundException("Design");
        }
        podDesignRepository.delete(id);
        log.info("::> [POD] Design deleted id={}", id);
    }

    @Override
    @Transactional
    public PodDesign renameDesign(UUID userId, UUID id, String name) {
        PodDesign design = podDesignRepository.getById(id);
        if (design == null || !userId.equals(design.getUserId())) {
            throw new NotFoundException("Design");
        }
        design.setName(name);
        return podDesignRepository.update(design);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PodDesign> myDesigns(UUID userId) {
        return podDesignRepository.findByUserId(userId);
    }

    @Override
    public PodAiResult aiGenerate(String prompt) {
        // Mock: real implementation would call an image-gen API (e.g. SDXL / DALL·E).
        String safePrompt = prompt == null ? "" : prompt;
        return PodAiResult.builder()
                .mockupUrl(mockupFor(safePrompt))
                .prompt(safePrompt).provider("mock").build();
    }

    /**
     * Build a lightweight POD blank-product card. DROP-540: prefer the
     * translation for the user's language, fall back to EN, then to the
     * Chinese title as a last resort; also expose the real main image
     * (cdnUrl / sourceUrl) so cards do not show a placeholder.
     */
    private PodBlankProduct tinyProduct(ProductEntity p, String lang) {
        String title = null;
        if (p.getTranslations() != null) {
            title = p.getTranslations().stream()
                    .filter(t -> lang.equalsIgnoreCase(t.getLanguage()) && t.getTitle() != null).map(t -> t.getTitle())
                    .findFirst().orElse(null);
            if (title == null)
                title = p.getTranslations().stream()
                        .filter(t -> "en".equalsIgnoreCase(t.getLanguage()) && t.getTitle() != null)
                        .map(t -> t.getTitle()).findFirst().orElse(null);
        }
        if (title == null)
            title = p.getTitleZh();
        String image = null;
        if (p.getImages() != null && !p.getImages().isEmpty()) {
            var img = p.getImages().get(0);
            image = img.getCdnUrl() != null && !img.getCdnUrl().isBlank() ? img.getCdnUrl() : img.getSourceUrl();
        }
        return PodBlankProduct.builder().id(p.getId()).slug(p.getSlug()).title(title).mainImage(image)
                .price(p.getBasePrice()).build();
    }
}
