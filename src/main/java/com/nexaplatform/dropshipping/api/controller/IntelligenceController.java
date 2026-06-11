package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.IntelligenceApi;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AdTrendEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.IntelligenceAlertEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DROP-8: Ad trends, sales trends, winning products and alerts. */
@RestController
@RequestMapping("/api/me/intelligence")
@RequiredArgsConstructor
public class IntelligenceController implements IntelligenceApi {

    private final AdTrendRepository adRepo;
    private final IntelligenceAlertRepository alertRepo;
    private final UserRepository userRepo;
    private final ProductRepository productRepo;
    private final CategoryRepository categoryRepo;

    public record TrendRow(UUID id, String source, String headline, String productSlug,
                           Long impressions, Long engagement, BigDecimal score,
                           String region, Instant capturedAt) {}
    public record WinningProduct(String slug, String title, int monthlySales, BigDecimal trendScore,
                                 String mainImage, BigDecimal price) {}
    public record AlertRequest(String keyword, UUID categoryId, String channel, BigDecimal thresholdScore) {}
    public record AlertView(UUID id, String keyword, UUID categoryId, String categoryName, String channel,
                            BigDecimal thresholdScore, boolean active, Instant createdAt) {}

    @Override
    @Transactional(readOnly = true)
    public List<TrendRow> adTrends(String source,
                                   int limit) {
        var rows = (source == null || source.isBlank())
                ? adRepo.findAllByOrderByScoreDesc()
                : adRepo.findBySourceOrderByScoreDesc(source.toLowerCase());
        return rows.stream().limit(Math.min(limit, 100)).map(this::toRow).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WinningProduct> salesTrends(UUID categoryId,
                                            int limit,
                                            String lang) {
        return productRepo.findAll().stream()
                .filter(p -> "ACTIVE".equals(p.getStatus() == null ? null : p.getStatus().name()))
                .filter(p -> categoryId == null || (p.getCategory() != null && categoryId.equals(p.getCategory().getId())))
                .sorted((a, b) -> Integer.compare(b.getMonthlySales(), a.getMonthlySales()))
                .limit(Math.min(limit, 100))
                .map(p -> toWin(p, lang)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WinningProduct> winning(int limit,
                                        String lang) {
        return productRepo.findAll().stream()
                .filter(p -> "ACTIVE".equals(p.getStatus() == null ? null : p.getStatus().name()))
                .sorted((a, b) -> {
                    BigDecimal sa = a.getTrendScore() == null ? BigDecimal.ZERO : a.getTrendScore();
                    BigDecimal sb = b.getTrendScore() == null ? BigDecimal.ZERO : b.getTrendScore();
                    return sb.compareTo(sa);
                })
                .limit(Math.min(limit, 100))
                .map(p -> toWin(p, lang)).toList();
    }

    /* ---------- alerts (DROP-71) ---------- */

    @Override
    @Transactional(readOnly = true)
    public List<AlertView> alerts(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return alertRepo.findByUser_IdAndActiveTrue(userId).stream().map(this::toAlert).toList();
    }

    @Override
    @Transactional
    public AlertView createAlert(Authentication auth, AlertRequest req) {
        UserEntity u = userRepo.findById(UUID.fromString(auth.getName())).orElseThrow();
        IntelligenceAlertEntity a = IntelligenceAlertEntity.builder()
                .user(u).keyword(req.keyword())
                .category(req.categoryId() == null ? null : categoryRepo.findById(req.categoryId()).orElse(null))
                .channel(req.channel() == null ? "EMAIL" : req.channel())
                .thresholdScore(req.thresholdScore()).active(true).build();
        return toAlert(alertRepo.save(a));
    }

    @Override
    @Transactional
    public void deleteAlert(UUID id) {
        alertRepo.findById(id).ifPresent(a -> { a.setActive(false); alertRepo.save(a); });
    }

    /* ---------- helpers ---------- */

    private TrendRow toRow(AdTrendEntity t) {
        return new TrendRow(t.getId(), t.getSource(), t.getHeadline(), t.getProductSlug(),
                t.getImpressions(), t.getEngagement(), t.getScore(), t.getRegion(), t.getCapturedAt());
    }

    private WinningProduct toWin(ProductEntity p) { return toWin(p, "es"); }
    // DROP-541: usa la traducción en el idioma del usuario en lugar del título chino.
    private WinningProduct toWin(ProductEntity p, String lang) {
        String img = null;
        try { if (p.getImages() != null && !p.getImages().isEmpty()) {
            var im = p.getImages().get(0);
            img = im.getCdnUrl() != null && !im.getCdnUrl().isBlank() ? im.getCdnUrl() : im.getSourceUrl();
        } } catch (Exception ignored) {}
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
        return new WinningProduct(p.getSlug(), title, p.getMonthlySales(), p.getTrendScore(),
                img, p.getBasePrice());
    }

    private AlertView toAlert(IntelligenceAlertEntity a) {
        String catName = a.getCategory() == null ? null : a.getCategory().getNameZh();
        return new AlertView(a.getId(), a.getKeyword(),
                a.getCategory() == null ? null : a.getCategory().getId(),
                catName, a.getChannel(), a.getThresholdScore(), a.isActive(), a.getCreatedAt());
    }
}
