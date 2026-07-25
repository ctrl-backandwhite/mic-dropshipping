package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Affiliate program engine (DROP-644/645/646): referral codes, click attribution (last-click,
 * cookie-based, within the attribution window), conversion capture on a confirmed order, the
 * commission state machine (PENDING → APPROVED after the return period → PAID via wallet credit,
 * or REJECTED on cancel/refund) and payouts to the affiliate's wallet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AffiliateProgramService {

    private final AffiliateJpaRepositoryAdapter affiliateRepo;
    private final AffiliateReferralCodeRepository codeRepo;
    private final AffiliateAttributionRepository attrRepo;
    private final AffiliateConversionRepository conversionRepo;
    private final AffiliateCommissionRepository commissionRepo;
    private final AffiliateProgramConfigRepository configRepo;
    private final AffiliatePayoutRepository payoutRepo;
    private final UserRepository userRepository;
    private final NotificationJpaRepositoryAdapter notificationRepo;
    private final NotificationsPublisher notificationsPublisher;
    private final WalletUseCase walletUseCase;
    private final AffiliateIndexer affiliateIndexer;

    /* ============================ Notifications (DROP-653) ============================ */

    /** Writes an in-app notification AND publishes a (marketing) email event via Kafka. */
    private void notify(UUID userId, String eventType, String title, String body) {
        notify(userId, eventType, title, body, null);
    }

    /**
     * Same as {@link #notify(UUID, String, String, String)} but attaches a structured {@code payload}
     * to the in-app notification (and mirrors it into the email event) so the frontend can render the
     * data on its own (e.g. the identity of a new affiliate) instead of parsing the body text.
     */
    private void notify(UUID userId, String eventType, String title, String body, Map<String, Object> payload) {
        if (userId == null) {
            return;
        }
        try {
            UserEntity u = userRepository
                    .findById(userId).orElse(null);
            if (u == null) {
                return;
            }
            Map<String, Object> inAppPayload = payload != null ? new HashMap<>(payload) : new HashMap<>();
            notificationRepo.save(
                    NotificationEntity.builder().user(u)
                            .eventType(eventType).title(title).body(body).channel("IN_APP")
                            .payload(inAppPayload).build());
            // Email via Kafka (notifications.dispatch → EmailDispatchConsumer). Marketing → opt-out aware.
            Map<String, Object> extra = new HashMap<>();
            extra.put("title", title);
            extra.put("body", body);
            extra.put("marketing", true);
            extra.put("ctaUrl", "/affiliate");
            extra.put("ctaLabel", "Ver mi panel de afiliado");
            if (payload != null) {
                extra.putAll(payload);
            }
            notificationsPublisher.dispatch(eventType, userId, u.getEmail(), extra,
                    u.getLanguage() != null ? u.getLanguage() : "es");
        } catch (RuntimeException e) {
            log.warn("affiliate notify failed for {}: {}", userId, e.getMessage());
        }
    }

    /** Alerts staff (admin/operator) — used for payout requests and fraud reviews (DROP-653). */
    private void notifyStaff(String eventType, String title, String body) {
        notifyStaff(eventType, title, body, null);
    }

    /** Staff alert carrying a structured {@code payload} (e.g. the full identity of a new affiliate). */
    private void notifyStaff(String eventType, String title, String body, Map<String, Object> payload) {
        try {
            userRepository.findAll().stream()
                    .filter(u -> u.getRole() != null
                            && ("ADMIN".equals(u.getRole().name()) || "OPERATOR".equals(u.getRole().name())))
                    .forEach(u -> notify(u.getId(), eventType, title, body, payload));
        } catch (RuntimeException e) {
            log.warn("affiliate notifyStaff failed: {}", e.getMessage());
        }
    }

    /** Composes a human-readable full name: displayName if set, else firstName + apellidos. */
    private String composeFullName(UserEntity u) {
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) {
            return u.getDisplayName().trim();
        }
        StringBuilder sb = new StringBuilder();
        appendNamePart(sb, u.getFirstName());
        appendNamePart(sb, u.getLastName1());
        appendNamePart(sb, u.getLastName2());
        return sb.length() > 0 ? sb.toString() : "(sin nombre)";
    }

    private void appendNamePart(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(part.trim());
        }
    }

    /**
     * Alerts staff about a new affiliate, identifying WHO joined (name, email, id, and country/company
     * when present) both in a human-readable body and as a structured payload for the frontend.
     */
    private void notifyStaffAffiliateJoined(UserEntity joiner) {
        if (joiner == null) {
            notifyStaff("AFFILIATE_JOIN", "Nuevo afiliado", "Un cliente se ha unido al programa de afiliados.");
            return;
        }
        String fullName = composeFullName(joiner);
        String email = joiner.getEmail();
        String country = joiner.getCountry() != null && !joiner.getCountry().isBlank()
                ? joiner.getCountry().trim() : null;
        String company = joiner.getCompanyName() != null && !joiner.getCompanyName().isBlank()
                ? joiner.getCompanyName().trim() : null;
        String userId = joiner.getId() != null ? joiner.getId().toString() : null;

        StringBuilder body = new StringBuilder("Nuevo afiliado: ").append(fullName);
        if (email != null && !email.isBlank()) {
            body.append(" (").append(email).append(')');
        }
        body.append('.');
        if (country != null) {
            body.append(" País: ").append(country).append('.');
        }
        if (company != null) {
            body.append(" Empresa: ").append(company).append('.');
        }
        if (userId != null) {
            body.append(" ID: ").append(userId).append('.');
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", fullName);
        payload.put("email", email);
        payload.put("userId", userId);
        if (country != null) {
            payload.put("country", country);
        }
        if (company != null) {
            payload.put("company", company);
        }
        notifyStaff("AFFILIATE_JOIN", "Nuevo afiliado", body.toString(), payload);
    }

    /* ============================ Config ============================ */

    @Transactional(readOnly = true)
    public AffiliateProgramConfigEntity config() {
        return configRepo.findFirstByOrderByCreatedAtAsc().orElseGet(() -> AffiliateProgramConfigEntity.builder()
                .defaultPercent(new BigDecimal("10.000")).attributionWindowDays(30).returnPeriodDays(14)
                .minPayoutCents(5000).currency("EUR").attributionModel("LAST_CLICK").build());
    }

    @Transactional
    public AffiliateProgramConfigEntity updateConfig(BigDecimal defaultPercent, Integer windowDays, Integer returnDays,
            Long minPayoutCents, String currency, Long maxCommissionPeriodCents) {
        AffiliateProgramConfigEntity c = configRepo.findFirstByOrderByCreatedAtAsc()
                .orElseGet(() -> configRepo.save(config()));
        if (defaultPercent != null) c.setDefaultPercent(defaultPercent);
        if (windowDays != null) c.setAttributionWindowDays(windowDays);
        if (returnDays != null) c.setReturnPeriodDays(returnDays);
        if (minPayoutCents != null) c.setMinPayoutCents(minPayoutCents);
        if (currency != null && !currency.isBlank()) c.setCurrency(currency);
        if (maxCommissionPeriodCents != null) c.setMaxCommissionPeriodCents(maxCommissionPeriodCents);
        return configRepo.save(c);
    }

    /* ============================ Affiliate + codes (DROP-644) ============================ */

    /** Returns the user's affiliate account, creating an ACTIVE one (with a first code) on demand. */
    @Transactional
    public AffiliateEntity getOrCreateForUser(UUID userId) {
        UserEntity user = userRepository.findById(userId).orElseThrow(
                () -> new NotFoundException("User not found"));
        // Data rule: admin/operator accounts cannot be affiliates.
        String role = user.getRole() != null ? user.getRole().name() : "";
        if ("ADMIN".equals(role) || "OPERATOR".equals(role)) {
            throw new BusinessException(
                    "Las cuentas de administración no pueden ser afiliados");
        }
        AffiliateEntity affiliate = affiliateRepo.findByUser_Id(userId).orElseGet(() -> {
            AffiliateEntity a = AffiliateEntity.builder().user(user).code(generateUniqueCode(user)).active(true)
                    .status("ACTIVE").build();
            return affiliateRepo.save(a);
        });
        // Ensure at least one referral code row exists.
        if (codeRepo.findByAffiliateIdOrderByCreatedAtAsc(affiliate.getId()).isEmpty()) {
            codeRepo.save(AffiliateReferralCodeEntity.builder().affiliate(affiliate)
                    .code(affiliate.getCode() != null ? affiliate.getCode() : generateUniqueCode(user))
                    .label("Primary").active(true).build());
        }
        affiliateIndexer.indexAffiliate(affiliate); // auto-sync del índice al crear/obtener el afiliado
        return affiliate;
    }

    /** DROP-650: explicit opt-in to the program (records terms acceptance, activates, notifies). */
    @Transactional
    public AffiliateEntity joinProgram(UUID userId) {
        AffiliateEntity a = getOrCreateForUser(userId);
        if (a.getAcceptedTermsAt() == null) {
            a.setAcceptedTermsAt(Instant.now());
            a.setStatus("ACTIVE");
            a.setActive(true);
            affiliateRepo.save(a);
            notify(userId, "AFFILIATE_JOINED", "Te has unido al programa de afiliados",
                    "Tu cuenta de afiliado está activa. Comparte tu enlace para empezar a ganar comisiones.");
            notifyStaffAffiliateJoined(a.getUser());
        }
        return a;
    }

    @Transactional(readOnly = true)
    public List<AffiliateReferralCodeEntity> listCodes(UUID affiliateId) {
        return codeRepo.findByAffiliateIdOrderByCreatedAtAsc(affiliateId);
    }

    @Transactional
    public AffiliateReferralCodeEntity addCode(UUID affiliateId, String label) {
        AffiliateEntity affiliate = affiliateRepo.findById(affiliateId).orElseThrow(
                () -> new NotFoundException("Affiliate not found"));
        return codeRepo.save(AffiliateReferralCodeEntity.builder().affiliate(affiliate)
                .code(generateUniqueCode(affiliate.getUser())).label(label != null ? label : "Link").active(true)
                .build());
    }

    @Transactional
    public AffiliateReferralCodeEntity setCodeActive(UUID codeId, boolean active) {
        AffiliateReferralCodeEntity c = codeRepo.findById(codeId).orElseThrow(
                () -> new NotFoundException("Code not found"));
        c.setActive(active);
        return codeRepo.save(c);
    }

    private String generateUniqueCode(UserEntity u) {
        String base = (u == null ? "ref" : (u.getDisplayName() == null ? u.getEmail() : u.getDisplayName()))
                .toLowerCase().replaceAll("[^a-z0-9]", "");
        if (base.isBlank()) base = "ref";
        if (base.length() > 8) base = base.substring(0, 8);
        for (int i = 0; i < 12; i++) {
            String candidate = base + "-" + UUID.randomUUID().toString().substring(0, 6);
            if (!codeRepo.existsByCodeIgnoreCase(candidate)) {
                return candidate;
            }
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 12);
    }

    /* ============================ Attribution (DROP-645) ============================ */

    /** Records a referral click and returns the visitor token to persist as a cookie. */
    @Transactional
    public Optional<AffiliateAttributionEntity> recordClick(String code, String visitorToken) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Optional<AffiliateReferralCodeEntity> codeOpt = codeRepo.findByCodeIgnoreCase(code.trim());
        if (codeOpt.isEmpty() || !codeOpt.get().isActive()) {
            return Optional.empty(); // unknown/inactive code → no attribution
        }
        AffiliateReferralCodeEntity rc = codeOpt.get();
        if (!"ACTIVE".equals(rc.getAffiliate().getStatus())) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        var cfg = config();
        // DROP-652: de-duplicate clicks — repeated clicks of the same code by the same visitor
        // within the dedup window refresh the existing attribution instead of inflating metrics.
        if (visitorToken != null && !visitorToken.isBlank()) {
            Instant since = now.minus(Duration.ofMinutes(Math.max(1, cfg.getClickDedupMinutes())));
            var recent = attrRepo.findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(visitorToken, now);
            if (recent.isPresent() && rc.getId().equals(recent.get().getReferralCodeId())
                    && recent.get().getClickedAt() != null && recent.get().getClickedAt().isAfter(since)) {
                return recent; // duplicate click within the window → no new attribution, no click bump
            }
        }
        rc.setClicks(rc.getClicks() + 1);
        codeRepo.save(rc);
        AffiliateAttributionEntity attr = AffiliateAttributionEntity.builder().referralCodeId(rc.getId())
                .affiliateId(rc.getAffiliate().getId()).visitorToken(visitorToken).clickedAt(now)
                .expiresAt(now.plus(Duration.ofDays(cfg.getAttributionWindowDays()))).build();
        return Optional.of(attrRepo.save(attr));
    }

    /** Binds an anonymous (cookie) attribution to a customer once they log in / register. */
    @Transactional
    public void bindVisitorToUser(String visitorToken, UUID userId) {
        if (visitorToken == null || visitorToken.isBlank() || userId == null) {
            return;
        }
        attrRepo.findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(visitorToken, Instant.now())
                .ifPresent(attr -> {
                    // Do not let an affiliate self-refer.
                    if (!isSelf(attr.getAffiliateId(), userId)) {
                        attr.setReferredUserId(userId);
                        attrRepo.save(attr);
                    }
                });
    }

    private boolean isSelf(UUID affiliateId, UUID userId) {
        return affiliateRepo.findById(affiliateId)
                .map(a -> a.getUser() != null && userId.equals(a.getUser().getId())).orElse(false);
    }

    /* ============================ Buyer referral discount ============================ */

    /** Porcentaje de descuento que obtiene el COMPRADOR por usar un código de afiliado. */
    public static final BigDecimal REFERRAL_DISCOUNT_PERCENT = new BigDecimal("10");

    public int referralDiscountPercent() {
        return REFERRAL_DISCOUNT_PERCENT.intValueExact();
    }

    /**
     * Descuento (en céntimos, misma divisa que {@code subtotalCents}) que aplica al comprador con una
     * atribución de referido viva. Reglas:
     * <ul>
     *   <li>Sin atribución viva → 0.</li>
     *   <li>Auto-referido (su propio código) → 0: el descuento solo funciona con el código de OTRO.</li>
     *   <li>Afiliado inactivo → 0.</li>
     * </ul>
     * Se calcula sobre el subtotal de PRODUCTO (sin envío ni IVA), redondeo HALF_UP al céntimo — el
     * mismo cálculo se usa en la vista previa del checkout y al crear el pedido, para que lo mostrado
     * coincida exactamente con lo cobrado.
     */
    public long referralDiscountCents(UUID userId, long subtotalCents) {
        if (userId == null || subtotalCents <= 0) {
            return 0L;
        }
        Optional<AffiliateAttributionEntity> attrOpt = attrRepo
                .findTopByReferredUserIdAndExpiresAtAfterOrderByClickedAtDesc(userId, Instant.now());
        if (attrOpt.isEmpty()) {
            return 0L;
        }
        AffiliateAttributionEntity attr = attrOpt.get();
        if (isSelf(attr.getAffiliateId(), userId)) {
            return 0L; // no hay descuento con tu propio código de afiliado
        }
        AffiliateEntity affiliate = affiliateRepo.findById(attr.getAffiliateId()).orElse(null);
        if (affiliate == null || !"ACTIVE".equals(affiliate.getStatus())) {
            return 0L;
        }
        return BigDecimal.valueOf(subtotalCents).multiply(REFERRAL_DISCOUNT_PERCENT)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValue();
    }

    /* ============================ Conversion + commission (DROP-646) ============================ */

    /**
     * Captures a conversion + PENDING commission when a customer with a live attribution places a
     * valid order. Idempotent per order; ignores self-referrals and orders without attribution.
     *
     * <p>The commission is a % of {@code subtotalCents} — the product sale amount actually charged
     * to the customer (already net of the referral discount, if any).
     */
    @Transactional
    public void onOrderPlaced(UUID orderId, UUID userId, long subtotalCents, String currency) {
        if (orderId == null || userId == null || subtotalCents <= 0) {
            return;
        }
        if (conversionRepo.existsByOrderId(orderId)) {
            return; // idempotent
        }
        Optional<AffiliateAttributionEntity> attrOpt = attrRepo
                .findTopByReferredUserIdAndExpiresAtAfterOrderByClickedAtDesc(userId, Instant.now());
        if (attrOpt.isEmpty()) {
            return; // no live attribution
        }
        AffiliateAttributionEntity attr = attrOpt.get();
        if (isSelf(attr.getAffiliateId(), userId)) {
            return;
        }
        AffiliateEntity affiliate = affiliateRepo.findById(attr.getAffiliateId()).orElse(null);
        if (affiliate == null || !"ACTIVE".equals(affiliate.getStatus())) {
            return;
        }
        String ccy = currency != null ? currency : "USD";
        AffiliateConversionEntity conv = conversionRepo.save(AffiliateConversionEntity.builder()
                .affiliateId(affiliate.getId()).referralCodeId(attr.getReferralCodeId()).referredUserId(userId)
                .orderId(orderId).baseAmountCents(subtotalCents).currency(ccy).status("CONFIRMED").build());

        var cfg = config();
        BigDecimal pct = affiliate.getCommissionPercentOverride() != null
                ? affiliate.getCommissionPercentOverride()
                : cfg.getDefaultPercent();
        long amount = BigDecimal.valueOf(subtotalCents).multiply(pct).divide(BigDecimal.valueOf(100), 0,
                RoundingMode.HALF_UP).longValue();

        // DROP-652: cap on commission per affiliate within the period → flag for manual REVIEW
        // instead of auto-approving when the limit is exceeded.
        boolean review = false;
        if (cfg.getMaxCommissionPeriodCents() > 0) {
            Instant since = Instant.now().minus(Duration.ofDays(Math.max(1, cfg.getMaxPeriodDays())));
            long periodSum = commissionRepo.findByAffiliateIdOrderByCreatedAtDesc(affiliate.getId()).stream()
                    .filter(c -> c.getCreatedAt() != null && c.getCreatedAt().isAfter(since))
                    .filter(c -> !"REJECTED".equals(c.getStatus()))
                    .mapToLong(AffiliateCommissionEntity::getAmountCents).sum();
            if (periodSum + amount > cfg.getMaxCommissionPeriodCents()) {
                review = true;
            }
        }
        String status = review ? "REVIEW" : "PENDING";
        commissionRepo.save(AffiliateCommissionEntity.builder().affiliateId(affiliate.getId())
                .conversionId(conv.getId()).amountCents(amount).currency(ccy).percentage(pct).status(status)
                .note((review ? "REVISIÓN (límite de periodo): " : "Auto: ") + pct + "% de " + subtotalCents + " " + ccy)
                .build());

        boolean firstConversion = affiliate.getReferralsCount() == 0;
        affiliate.setReferralsCount(affiliate.getReferralsCount() + 1);
        affiliate.setEarningsUsdCents(affiliate.getEarningsUsdCents() + amount);
        affiliateRepo.save(affiliate);
        log.info("::> [AFFILIATE] Conversion {} → commission {} {} ({}) for affiliate {}", conv.getId(), amount, ccy,
                status, affiliate.getId());

        // DROP-653: notify the affiliate (first sale / commission earned) and staff if flagged.
        UUID affUser = affiliate.getUser() != null ? affiliate.getUser().getId() : null;
        if (firstConversion) {
            notify(affUser, "AFFILIATE_FIRST_CONVERSION", "¡Tu primera venta referida!",
                    "Has generado tu primera conversión como afiliado. Tu comisión está en proceso.");
        } else {
            notify(affUser, "AFFILIATE_COMMISSION", "Nueva comisión",
                    "Has generado una nueva comisión por una venta referida.");
        }
        if (review) {
            notifyStaff("AFFILIATE_FRAUD_REVIEW", "Comisión en revisión",
                    "Una comisión superó el límite del periodo y quedó en revisión manual.");
        }
    }

    /** Cancels the conversion and rejects its commission (unless already paid) on order cancel/refund. */
    @Transactional
    public void rejectForOrder(UUID orderId) {
        conversionRepo.findByOrderId(orderId).ifPresent(conv -> {
            conv.setStatus("CANCELLED");
            conversionRepo.save(conv);
            commissionRepo.findByConversionId(conv.getId()).ifPresent(comm -> {
                if (!"PAID".equals(comm.getStatus())) {
                    comm.setStatus("REJECTED");
                    comm.setNote("Pedido cancelado/reembolsado");
                    commissionRepo.save(comm);
                    affiliateRepo.findById(comm.getAffiliateId()).ifPresent(a -> {
                        a.setEarningsUsdCents(Math.max(0, a.getEarningsUsdCents() - comm.getAmountCents()));
                        affiliateRepo.save(a);
                    });
                }
            });
        });
    }

    /** Moves PENDING commissions past the return period to APPROVED. Returns how many were approved. */
    @Transactional
    public int approveDueCommissions() {
        int days = config().getReturnPeriodDays();
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        int approved = 0;
        for (AffiliateCommissionEntity comm : commissionRepo.findByStatus("PENDING")) {
            if (comm.getCreatedAt() != null && comm.getCreatedAt().isBefore(cutoff)) {
                comm.setStatus("APPROVED");
                comm.setApprovedAt(Instant.now());
                commissionRepo.save(comm);
                approved++;
                affiliateRepo.findById(comm.getAffiliateId()).ifPresent(a -> notify(
                        a.getUser() != null ? a.getUser().getId() : null, "AFFILIATE_COMMISSION_APPROVED",
                        "Comisión aprobada", "Una de tus comisiones ha sido aprobada y está lista para liquidar."));
            }
        }
        return approved;
    }

    /** A commission flagged for manual REVIEW can be approved or rejected by the operator (DROP-652). */
    @Transactional
    public void resolveReview(UUID commissionId, boolean approve) {
        AffiliateCommissionEntity comm = commissionRepo.findById(commissionId).orElseThrow(
                () -> new NotFoundException("Commission not found"));
        if (!"REVIEW".equals(comm.getStatus())) {
            throw new BusinessException("La comisión no está en revisión");
        }
        if (approve) {
            comm.setStatus("APPROVED");
            comm.setApprovedAt(Instant.now());
        } else {
            comm.setStatus("REJECTED");
            comm.setNote("Rechazada en revisión anti-fraude");
            affiliateRepo.findById(comm.getAffiliateId()).ifPresent(a -> {
                a.setEarningsUsdCents(Math.max(0, a.getEarningsUsdCents() - comm.getAmountCents()));
                affiliateRepo.save(a);
            });
        }
        commissionRepo.save(comm);
    }

    /* ============================ Payout / settlement (DROP-651) ============================ */

    /** Affiliate requests a payout of their APPROVED commissions (must meet the minimum). */
    @Transactional
    public AffiliatePayoutEntity requestPayout(UUID userId) {
        AffiliateEntity affiliate = affiliateRepo.findByUser_Id(userId).orElseThrow(
                () -> new NotFoundException("Affiliate not found"));
        if (payoutRepo.existsByAffiliateIdAndStatus(affiliate.getId(), "REQUESTED")) {
            throw new BusinessException(
                    "Ya tienes una solicitud de pago pendiente");
        }
        List<AffiliateCommissionEntity> approved = commissionRepo.findByAffiliateIdAndStatus(affiliate.getId(), "APPROVED");
        long total = approved.stream().mapToLong(AffiliateCommissionEntity::getAmountCents).sum();
        var cfg = config();
        if (total < cfg.getMinPayoutCents()) {
            throw new BusinessException(
                    "Saldo aprobado por debajo del pago mínimo");
        }
        AffiliatePayoutEntity payout = payoutRepo.save(AffiliatePayoutEntity.builder().affiliateId(affiliate.getId())
                .amountCents(total).currency(cfg.getCurrency()).status("REQUESTED").method("WALLET")
                .commissionCount(approved.size()).requestedAt(Instant.now())
                .note("Solicitud del afiliado").build());
        notifyStaff("AFFILIATE_PAYOUT_REQUEST", "Solicitud de pago de afiliado",
                "Un afiliado ha solicitado el pago de sus comisiones aprobadas.");
        return payout;
    }

    /**
     * Operator approves & executes a payout: credits the affiliate's wallet and marks the APPROVED
     * commissions as PAID. No automatic payment happens without this explicit approval (DROP-651).
     */
    @Transactional
    public AffiliatePayoutEntity approvePayout(UUID payoutId) {
        AffiliatePayoutEntity payout = payoutRepo.findById(payoutId).orElseThrow(
                () -> new NotFoundException("Payout not found"));
        if ("PAID".equals(payout.getStatus())) {
            return payout; // idempotent — never pay twice
        }
        if ("REJECTED".equals(payout.getStatus())) {
            throw new BusinessException("El pago fue rechazado");
        }
        AffiliateEntity affiliate = affiliateRepo.findById(payout.getAffiliateId()).orElseThrow(
                () -> new NotFoundException("Affiliate not found"));
        List<AffiliateCommissionEntity> approved = commissionRepo.findByAffiliateIdAndStatus(affiliate.getId(), "APPROVED");
        long total = approved.stream().mapToLong(AffiliateCommissionEntity::getAmountCents).sum();
        if (total <= 0) {
            payout.setStatus("REJECTED");
            payout.setNote("Sin comisiones aprobadas que liquidar");
            return payoutRepo.save(payout);
        }
        UUID userId = affiliate.getUser().getId();
        var tx = walletUseCase.adminTopup(userId, total, "Affiliate commission payout",
                "affiliate-payout-" + payout.getId());
        UUID txId = tx != null ? tx.getId() : null;
        Instant now = Instant.now();
        for (AffiliateCommissionEntity comm : approved) {
            comm.setStatus("PAID");
            comm.setPaidAt(now);
            comm.setWalletTxId(txId);
            comm.setPayoutId(payout.getId());
            commissionRepo.save(comm);
        }
        affiliate.setPayoutUsdCents(affiliate.getPayoutUsdCents() + total);
        affiliateRepo.save(affiliate);
        payout.setStatus("PAID");
        payout.setAmountCents(total);
        payout.setWalletTxId(txId);
        payout.setProcessedAt(now);
        payout.setCommissionCount(approved.size());
        payoutRepo.save(payout);
        notify(userId, "AFFILIATE_PAYOUT_PAID", "Pago de comisiones realizado",
                "Tus comisiones se han abonado a tu wallet.");
        log.info("::> [AFFILIATE] Payout {} paid {} cents to affiliate {} (wallet tx {})", payout.getId(), total,
                affiliate.getId(), txId);
        return payout;
    }

    @Transactional
    public AffiliatePayoutEntity rejectPayout(UUID payoutId, String reason) {
        AffiliatePayoutEntity payout = payoutRepo.findById(payoutId).orElseThrow(
                () -> new NotFoundException("Payout not found"));
        if ("PAID".equals(payout.getStatus())) {
            throw new BusinessException("El pago ya se ejecutó");
        }
        payout.setStatus("REJECTED");
        payout.setProcessedAt(Instant.now());
        payout.setNote(reason != null ? reason : "Rechazado por el operador");
        return payoutRepo.save(payout);
    }

    /** Admin direct settlement: creates an approved payout and executes it in one step. */
    @Transactional
    public long payoutApproved(UUID affiliateId, boolean force) {
        AffiliateEntity affiliate = affiliateRepo.findById(affiliateId).orElseThrow(
                () -> new NotFoundException("Affiliate not found"));
        long approvedTotal = commissionRepo.findByAffiliateIdAndStatus(affiliateId, "APPROVED").stream()
                .mapToLong(AffiliateCommissionEntity::getAmountCents).sum();
        if (approvedTotal <= 0) {
            return 0;
        }
        if (!force && approvedTotal < config().getMinPayoutCents()) {
            return 0;
        }
        AffiliatePayoutEntity payout = payoutRepo.save(AffiliatePayoutEntity.builder().affiliateId(affiliateId)
                .amountCents(approvedTotal).currency(config().getCurrency()).status("APPROVED").method("WALLET")
                .requestedAt(Instant.now()).note("Pago directo del operador").build());
        AffiliatePayoutEntity done = approvePayout(payout.getId());
        return "PAID".equals(done.getStatus()) ? done.getAmountCents() : 0;
    }

    @Transactional(readOnly = true)
    public List<AffiliatePayoutEntity> payoutsForAffiliate(UUID affiliateId) {
        return payoutRepo.findByAffiliateIdOrderByCreatedAtDesc(affiliateId);
    }

    @Transactional(readOnly = true)
    public List<AffiliatePayoutEntity> pendingPayouts() {
        return payoutRepo.findByStatusOrderByCreatedAtDesc("REQUESTED");
    }

    /* ============================ Queries ============================ */

    @Transactional(readOnly = true)
    public List<AffiliateCommissionEntity> commissionsForAffiliate(UUID affiliateId) {
        return commissionRepo.findByAffiliateIdOrderByCreatedAtDesc(affiliateId);
    }

    @Transactional(readOnly = true)
    public List<AffiliateConversionEntity> conversionsForAffiliate(UUID affiliateId) {
        return conversionRepo.findByAffiliateIdOrderByCreatedAtDesc(affiliateId);
    }

    @Transactional(readOnly = true)
    public List<AffiliateEntity> allAffiliates() {
        return affiliateRepo.findAllWithUser();
    }

    /** Admin lifecycle change: PENDING / ACTIVE / SUSPENDED. */
    @Transactional
    public AffiliateEntity setAffiliateStatus(UUID affiliateId, String status) {
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!s.equals("PENDING") && !s.equals("ACTIVE") && !s.equals("SUSPENDED")) {
            throw new BusinessException("Estado de afiliado no válido");
        }
        AffiliateEntity a = affiliateRepo.findById(affiliateId).orElseThrow(
                () -> new NotFoundException("Affiliate not found"));
        a.setStatus(s);
        a.setActive("ACTIVE".equals(s));
        AffiliateEntity saved = affiliateRepo.save(a);
        affiliateIndexer.indexAffiliate(saved); // auto-sync del índice al cambiar el estado
        return saved;
    }
}
