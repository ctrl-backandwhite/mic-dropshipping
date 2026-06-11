package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.in.OdmProjectCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.OdmStatusUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PodAiGenerateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.PodDesignCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.ShippingCalculatorDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.SupportTicketCreateDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.SupportTicketResolveDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.CarbonFootprintDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.OdmProjectDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PlatformNotificationDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PodAiGenerateDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PodBlankProductDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PodDesignDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ShippingRateDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SupportTicketDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.UnreadCountDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WarehouseDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WarehouseStockDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.PlatformExtrasMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OdmProjectEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PodDesignEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupportTicketEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.NotificationRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OdmProjectRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PodDesignRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductWarehouseStockRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupportTicketRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Use-case service for the DROP-6 (POD), DROP-7 (ODM), DROP-11
 * (Tickets / Notifications) and DROP-13 (Warehouses / Shipping calculator / ESG)
 * features. Holds all logic previously living in {@code PlatformExtrasController}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformExtrasService {

    private final PodDesignRepository podRepo;
    private final OdmProjectRepository odmRepo;
    private final SupportTicketRepository ticketRepo;
    private final NotificationRepository notifRepo;
    private final WarehouseRepository warehouseRepo;
    private final ProductWarehouseStockRepository stockRepo;
    private final UserRepository userRepo;
    private final ProductRepository productRepo;
    private final OrderRepository orderRepo;
    private final PlatformExtrasMapper mapper;

    /* ============================== DROP-6 POD ============================== */

    @Transactional(readOnly = true)
    public List<PodBlankProductDtoOut> podBlanks(String lang) {
        return productRepo.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getPodEnabled()))
                .map(p -> tinyProduct(p, lang))
                .toList();
    }

    @Transactional
    public PodDesignDtoOut createDesign(UUID userId, PodDesignCreateDtoIn req) {
        UserEntity u = userRepo.findById(userId).orElseThrow();
        ProductEntity p = productRepo.findById(req.getProductId())
                .orElseThrow(() -> new NotFoundException("Product"));
        PodDesignEntity d = PodDesignEntity.builder()
                .user(u).product(p).name(req.getName())
                .canvasJson(req.getCanvasJson() == null ? new HashMap<>() : req.getCanvasJson())
                .aiPrompt(req.getAiPrompt())
                .mockupUrl("https://cdn.nx036.local/pod/mock-" + UUID.randomUUID().toString().substring(0, 8) + ".webp")
                .status("RENDERED").build();
        return mapper.toPodDesignDto(podRepo.save(d));
    }

    @Transactional(readOnly = true)
    public List<PodDesignDtoOut> myDesigns(UUID userId) {
        return mapper.toPodDesignDtos(podRepo.findByUser_IdOrderByCreatedAtDesc(userId));
    }

    public PodAiGenerateDtoOut aiGenerate(PodAiGenerateDtoIn req) {
        // Mock: real implementation would call an image-gen API (e.g. SDXL / DALL·E).
        String prompt = req.getPrompt() == null ? "" : req.getPrompt();
        return PodAiGenerateDtoOut.builder()
                .mockupUrl("https://cdn.nx036.local/pod/ai-" + Math.abs(prompt.hashCode()) + ".webp")
                .prompt(prompt)
                .provider("mock")
                .build();
    }

    /* ============================== DROP-7 ODM/OEM ============================== */

    @Transactional
    public OdmProjectDtoOut createOdm(UUID userId, OdmProjectCreateDtoIn req) {
        UserEntity u = userRepo.findById(userId).orElseThrow();
        int sla = switch (req.getKind()) {
            case "ODM_PAID" -> 7;
            case "OEM" -> 30;
            case "CUSTOM_PACKAGING" -> 14;
            default -> 21;  // ODM_FREE
        };
        OdmProjectEntity p = OdmProjectEntity.builder()
                .user(u).kind(req.getKind()).title(req.getTitle()).brief(req.getBrief())
                .budgetUsdCents(req.getBudgetUsdCents()).slaDays(sla).status("INTAKE").build();
        return mapper.toOdmDto(odmRepo.save(p));
    }

    @Transactional(readOnly = true)
    public List<OdmProjectDtoOut> myOdm(UUID userId) {
        return mapper.toOdmDtos(odmRepo.findByUser_IdOrderByCreatedAtDesc(userId));
    }

    @Transactional(readOnly = true)
    public List<OdmProjectDtoOut> adminOdm(String status) {
        var src = (status == null || status.isBlank()) ? odmRepo.findAll()
                : odmRepo.findByStatusOrderByCreatedAtDesc(status);
        return mapper.toOdmDtos(src);
    }

    @Transactional
    public OdmProjectDtoOut setOdmStatus(UUID id, OdmStatusUpdateDtoIn req) {
        OdmProjectEntity p = odmRepo.findById(id).orElseThrow(() -> new NotFoundException("ODM project"));
        if (req.getStatus() != null) p.setStatus(req.getStatus());
        return mapper.toOdmDto(odmRepo.save(p));
    }

    /* ============================== DROP-11 Tickets ============================== */

    @Transactional
    public SupportTicketDtoOut openTicket(UUID userId, SupportTicketCreateDtoIn req) {
        UserEntity u = userRepo.findById(userId).orElseThrow();
        CustomerOrderEntity order = req.getOrderId() == null ? null : orderRepo.findById(req.getOrderId()).orElse(null);
        SupportTicketEntity t = SupportTicketEntity.builder()
                .user(u).kind(req.getKind()).subject(req.getSubject()).body(req.getBody())
                .order(order).priority(req.getPriority() == null ? "NORMAL" : req.getPriority())
                .status("OPEN").build();
        return mapper.toTicketDto(ticketRepo.save(t));
    }

    @Transactional(readOnly = true)
    public List<SupportTicketDtoOut> myTickets(UUID userId) {
        return mapper.toTicketDtos(ticketRepo.findByUser_IdOrderByCreatedAtDesc(userId));
    }

    @Transactional(readOnly = true)
    public List<SupportTicketDtoOut> adminTickets(String status) {
        var src = (status == null || status.isBlank()) ? ticketRepo.findAll()
                : ticketRepo.findByStatusOrderByCreatedAtDesc(status);
        return mapper.toTicketDtos(src);
    }

    @Transactional
    public SupportTicketDtoOut resolve(UUID id, SupportTicketResolveDtoIn req) {
        SupportTicketEntity t = ticketRepo.findById(id).orElseThrow(() -> new NotFoundException("Ticket"));
        t.setStatus("RESOLVED");
        t.setResolution(req.getResolution());
        return mapper.toTicketDto(ticketRepo.save(t));
    }

    /* ============================== DROP-11 Notifications ============================== */

    @Transactional(readOnly = true)
    public List<PlatformNotificationDtoOut> notifications(UUID userId) {
        return mapper.toNotificationDtos(notifRepo.findByUser_IdOrderByCreatedAtDesc(userId));
    }

    @Transactional(readOnly = true)
    public UnreadCountDtoOut unreadCount(UUID userId) {
        return UnreadCountDtoOut.builder()
                .count(notifRepo.countByUser_IdAndReadAtIsNull(userId))
                .build();
    }

    @Transactional
    public void markRead(UUID id) {
        notifRepo.findById(id).ifPresent(n -> {
            if (n.getReadAt() == null) {
                n.setReadAt(Instant.now());
                notifRepo.save(n);
            }
        });
    }

    @Transactional
    public void markAllRead(UUID userId) {
        notifRepo.findByUser_IdOrderByCreatedAtDesc(userId).forEach(n -> {
            if (n.getReadAt() == null) {
                n.setReadAt(Instant.now());
                notifRepo.save(n);
            }
        });
    }

    /* ============================== DROP-13 Warehouses / Shipping calc / ESG ============================== */

    @Transactional(readOnly = true)
    public List<WarehouseDtoOut> warehouses() {
        return mapper.toWarehouseDtos(warehouseRepo.findByActiveTrueOrderByCountryAsc());
    }

    @Transactional(readOnly = true)
    public List<WarehouseStockDtoOut> stockPerWarehouse(UUID productId) {
        return mapper.toStockDtos(stockRepo.findByProduct_Id(productId));
    }

    public List<ShippingRateDtoOut> shippingCalculator(ShippingCalculatorDtoIn req) {
        // Synthetic estimate that mirrors our seeded rate table so the calculator is offline-friendly.
        double kg = (req.effectiveWeight() * req.effectiveQty()) / 1000.0;
        return List.of(
                rate("STANDARD", "CJPacket", 3.50 + 8.0 * kg, 7, 14),
                rate("EXPRESS", "DHL Express", 12.00 + 25.0 * kg, 3, 6),
                rate("AIR", "China Post Air", 7.00 + 15.0 * kg, 5, 10),
                rate("SEA", "Sea LCL", 15.00 + 4.0 * kg, 25, 45));
    }

    public CarbonFootprintDtoOut carbonFootprint(ShippingCalculatorDtoIn req) {
        // Very rough kg CO2 estimate per kg shipped: SEA 0.1 / AIR 1.5 / EXPRESS 2.5 / STANDARD 0.4.
        double kg = (req.effectiveWeight() * req.effectiveQty()) / 1000.0;
        BigDecimal sea = money(kg * 0.1);
        // Offset price ~ $20 per ton (industry baseline) -> $0.02 per kg CO2.
        BigDecimal offset = money(kg * 0.1 * 0.02);
        return CarbonFootprintDtoOut.builder()
                .carbonKg(sea)
                .offsetUsd(offset)
                .greenestMethod("SEA")
                .build();
    }

    /* ---------- helpers ---------- */

    private static ShippingRateDtoOut rate(String method, String carrier, double cost, int min, int max) {
        return ShippingRateDtoOut.builder()
                .method(method).carrier(carrier).cost(money(cost))
                .transitMin(min).transitMax(max).build();
    }

    /**
     * Build a lightweight POD blank-product card. DROP-540: prefer the
     * translation for the user's language, fall back to EN, then to the
     * Chinese title as a last resort; also expose the real main image
     * (cdnUrl / sourceUrl) so cards do not show a placeholder.
     */
    private PodBlankProductDtoOut tinyProduct(ProductEntity p, String lang) {
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
        return PodBlankProductDtoOut.builder()
                .id(p.getId())
                .slug(p.getSlug())
                .title(title)
                .mainImage(image)
                .price(p.getBasePrice())
                .build();
    }

    private static BigDecimal money(double d) {
        return new BigDecimal(d).setScale(2, RoundingMode.HALF_UP);
    }
}
