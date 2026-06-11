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

    @Override
    @Transactional(readOnly = true)
    public List<PodBlankProduct> blanks(String lang) {
        return productRepository.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getPodEnabled()))
                .map(p -> tinyProduct(p, lang))
                .toList();
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
        model.setMockupUrl("https://cdn.nx036.local/pod/mock-"
                + UUID.randomUUID().toString().substring(0, 8) + ".webp");
        model.setStatus("RENDERED");
        PodDesign saved = podDesignRepository.save(model);
        log.info("::> [POD] Design created id={}", saved.getId());
        return saved;
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
                .mockupUrl("https://cdn.nx036.local/pod/ai-" + Math.abs(safePrompt.hashCode()) + ".webp")
                .prompt(safePrompt)
                .provider("mock")
                .build();
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
                    .filter(t -> lang.equalsIgnoreCase(t.getLanguage()) && t.getTitle() != null)
                    .map(t -> t.getTitle()).findFirst().orElse(null);
            if (title == null) title = p.getTranslations().stream()
                    .filter(t -> "en".equalsIgnoreCase(t.getLanguage()) && t.getTitle() != null)
                    .map(t -> t.getTitle()).findFirst().orElse(null);
        }
        if (title == null) title = p.getTitleZh();
        String image = null;
        if (p.getImages() != null && !p.getImages().isEmpty()) {
            var img = p.getImages().get(0);
            image = img.getCdnUrl() != null && !img.getCdnUrl().isBlank() ? img.getCdnUrl() : img.getSourceUrl();
        }
        return PodBlankProduct.builder()
                .id(p.getId())
                .slug(p.getSlug())
                .title(title)
                .mainImage(image)
                .price(p.getBasePrice())
                .build();
    }
}
