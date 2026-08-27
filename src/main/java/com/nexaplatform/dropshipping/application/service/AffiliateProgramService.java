package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.model.WalletTransaction;
import com.nexaplatform.dropshipping.infrastructure.integration.search.AffiliateIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.*;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.PayoutProfileUpdateRequest;
import com.nexaplatform.dropshipping.api.dto.AffiliateDtos.PayoutProfileView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
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

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String AFFILIATE_NOT_FOUND = "Affiliate not found";
    private static final String REQUESTED = "REQUESTED";
    private static final String REJECTED = "REJECTED";
    private static final String APPROVED = "APPROVED";
    private static final String PENDING = "PENDING";
    private static final String ACTIVE = "ACTIVE";
    private static final String WALLET = "WALLET";

    /**
     * Correo de cobro. Los cuantificadores son POSESIVOS ({@code ++}): con el codicioso, una cadena
     * larga sin arroba hace que el motor pruebe todos los repartos posibles antes de rendirse, y el
     * correo llega desde el formulario del afiliado.
     */
    private static final Pattern PAYOUT_EMAIL = Pattern.compile("^[^@\\s]++@[^@\\s.]++(\\.[^@\\s.]++)++$");

    private final AffiliateJpaRepositoryAdapter affiliateRepo;
    private final AffiliateReferralCodeRepository codeRepo;
    private final AffiliateAttributionRepository attrRepo;
    private final AffiliateConversionRepository conversionRepo;
    private final AffiliateCommissionRepository commissionRepo;
    private final AffiliateProgramConfigRepository configRepo;
    private final AffiliatePayoutRepository payoutRepo;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
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
        return !sb.isEmpty() ? sb.toString() : "(sin nombre)";
    }

    private void appendNamePart(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (!sb.isEmpty()) {
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
        return currentConfig();
    }

    /**
     * Cuerpo de {@link #config()} SIN anotar, que es al que llaman el resto de métodos de esta clase. Una
     * llamada interna no pasa por el proxy de Spring, así que un {@code @Transactional} aquí sería una
     * anotación que no se aplica nunca; la transacción la abre el método público de entrada.
     */
    private AffiliateProgramConfigEntity currentConfig() {
        return configRepo.findFirstByOrderByCreatedAtAsc().orElseGet(() -> AffiliateProgramConfigEntity.builder()
                .defaultPercent(new BigDecimal("10.000")).attributionWindowDays(30).returnPeriodDays(14)
                .minPayoutCents(5000).currency("EUR").attributionModel("LAST_CLICK").build());
    }

    @Transactional
    public AffiliateProgramConfigEntity updateConfig(BigDecimal defaultPercent, Integer windowDays, Integer returnDays,
            Long minPayoutCents, String currency, Long maxCommissionPeriodCents) {
        AffiliateProgramConfigEntity c = configRepo.findFirstByOrderByCreatedAtAsc()
                .orElseGet(() -> configRepo.save(currentConfig()));
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
        return getOrCreateAffiliate(userId);
    }

    /** Cuerpo sin anotar de {@link #getOrCreateForUser(UUID)}: es al que llaman los métodos de esta clase. */
    private AffiliateEntity getOrCreateAffiliate(UUID userId) {
        UserEntity user = userRepository.findById(userId).orElseThrow(
                () -> new NotFoundException("User not found"));
        // Data rule: admin/operator accounts cannot be affiliates.
        String role = user.getRole() != null ? user.getRole().name() : "";
        if ("ADMIN".equals(role) || "OPERATOR".equals(role)) {
            throw new BusinessException(
                    "Las cuentas de administración no pueden ser afiliados");
        }
        AffiliateEntity affiliate = affiliateRepo.findByUser_Id(userId).orElseGet(() -> {
            // Nace PENDING/inactivo: hasta que un admin lo apruebe no gana comisiones ni cuenta clics (la
            // atribución exige status ACTIVE). La aprobación la hace el admin tras la solicitud (joinProgram).
            AffiliateEntity a = AffiliateEntity.builder().user(user).code(generateUniqueCode(user)).active(false)
                    .status(PENDING).build();
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

    /**
     * SOLICITUD de acceso al programa de afiliados: registra la aceptación de términos y deja la cuenta en
     * PENDING (inactiva). NO gana comisiones ni cuenta clics hasta que un ADMIN la apruebe (la atribución y
     * las comisiones exigen status ACTIVE). Avisa al usuario de que está pendiente y al staff para revisar.
     */
    @Transactional
    public AffiliateEntity joinProgram(UUID userId) {
        AffiliateEntity a = getOrCreateAffiliate(userId);
        if (a.getAcceptedTermsAt() == null) {
            a.setAcceptedTermsAt(Instant.now());
            a.setStatus(PENDING);
            a.setActive(false);
            affiliateRepo.save(a);
            notify(userId, "AFFILIATE_APPLIED", "Solicitud de afiliado recibida",
                    "Hemos recibido tu solicitud para el programa de afiliados. Un administrador la revisará y te "
                            + "avisaremos cuando esté aprobada.");
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
                () -> new NotFoundException(AFFILIATE_NOT_FOUND));
        return codeRepo.save(AffiliateReferralCodeEntity.builder().affiliate(affiliate)
                .code(generateUniqueCode(affiliate.getUser())).label(label != null ? label : "Link").active(true)
                .build());
    }

    /**
     * Activa o desactiva un código de referido, comprobando que pertenece a QUIEN lo pide.
     *
     * <p>Sin esa comprobación bastaba estar autenticado y conocer un identificador de código para apagar
     * el enlace de otro afiliado, y con él sus comisiones futuras: los clics con ese código dejarían de
     * atribuirse. El código ajeno se responde como inexistente y no como prohibido, porque un 403
     * confirmaría al atacante que ese identificador existe.
     */
    @Transactional
    public AffiliateReferralCodeEntity setCodeActive(UUID userId, UUID codeId, boolean active) {
        AffiliateReferralCodeEntity c = codeRepo.findById(codeId).orElseThrow(
                () -> new NotFoundException("Code not found"));
        UUID owner = c.getAffiliate() != null ? c.getAffiliate().getId() : null;
        UUID mine = affiliateRepo.findByUser_Id(userId).map(AffiliateEntity::getId).orElse(null);
        if (owner == null || !owner.equals(mine)) {
            throw new NotFoundException("Code not found");
        }
        c.setActive(active);
        return codeRepo.save(c);
    }

    /**
     * Semilla legible del código de referido. El orden importa: se prefiere el nombre visible por ser
     * lo que el afiliado reconoce en su enlace y solo se cae al email si no tiene ninguno; sin usuario
     * queda el genérico {@code "ref"}.
     */
    private static String codeSeed(UserEntity u) {
        if (u == null) {
            return "ref";
        }
        return u.getDisplayName() == null ? u.getEmail() : u.getDisplayName();
    }

    private String generateUniqueCode(UserEntity u) {
        String base = codeSeed(u).toLowerCase().replaceAll("[^a-z0-9]", "");
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
        if (!ACTIVE.equals(rc.getAffiliate().getStatus())) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        AffiliateProgramConfigEntity cfg = currentConfig();
        // DROP-652: de-duplicate clicks — repeated clicks of the same code by the same visitor
        // within the dedup window refresh the existing attribution instead of inflating metrics.
        if (visitorToken != null && !visitorToken.isBlank()) {
            Instant since = now.minus(Duration.ofMinutes(Math.max(1, cfg.getClickDedupMinutes())));
            Optional<AffiliateAttributionEntity> recent = attrRepo
                    .findTopByVisitorTokenAndExpiresAtAfterOrderByClickedAtDesc(visitorToken, now);
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
        if (affiliate == null || !ACTIVE.equals(affiliate.getStatus())) {
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
        if (affiliate == null || !ACTIVE.equals(affiliate.getStatus())) {
            return;
        }
        String ccy = currency != null ? currency : "USD";
        AffiliateConversionEntity conv = conversionRepo.save(AffiliateConversionEntity.builder()
                .affiliateId(affiliate.getId()).referralCodeId(attr.getReferralCodeId()).referredUserId(userId)
                .orderId(orderId).baseAmountCents(subtotalCents).currency(ccy).status("CONFIRMED").build());

        AffiliateProgramConfigEntity cfg = currentConfig();
        BigDecimal pct = affiliate.getCommissionPercentOverride() != null
                ? affiliate.getCommissionPercentOverride()
                : cfg.getDefaultPercent();
        long amount = BigDecimal.valueOf(subtotalCents).multiply(pct).divide(BigDecimal.valueOf(100), 0,
                RoundingMode.HALF_UP).longValue();

        boolean review = exceedsPeriodCap(cfg, affiliate.getId(), amount);
        String status = review ? "REVIEW" : PENDING;
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

        notifyConversion(affiliate, firstConversion, review);
    }

    /**
     * DROP-652: ¿la comisión nueva pasa del tope acumulado del periodo? Si lo pasa, la comisión queda en
     * REVIEW para revisión manual en vez de auto-aprobarse (freno anti-fraude).
     *
     * <p>Las RECHAZADAS no cuentan para el acumulado: si contaran, un afiliado marcado por fraude
     * arrastraría su historial rechazado y nunca volvería a cobrar automáticamente. Un tope a cero
     * significa "sin límite" y se cortocircuita sin consultar la base de datos.
     */
    private boolean exceedsPeriodCap(AffiliateProgramConfigEntity cfg, UUID affiliateId, long amount) {
        if (cfg.getMaxCommissionPeriodCents() <= 0) {
            return false;
        }
        Instant since = Instant.now().minus(Duration.ofDays(Math.max(1, cfg.getMaxPeriodDays())));
        long periodSum = commissionRepo.findByAffiliateIdOrderByCreatedAtDesc(affiliateId).stream()
                .filter(c -> c.getCreatedAt() != null && c.getCreatedAt().isAfter(since))
                .filter(c -> !REJECTED.equals(c.getStatus()))
                .mapToLong(AffiliateCommissionEntity::getAmountCents).sum();
        return periodSum + amount > cfg.getMaxCommissionPeriodCents();
    }

    /** DROP-653: avisa al afiliado (primera venta / comisión nueva) y al staff si quedó en revisión. */
    private void notifyConversion(AffiliateEntity affiliate, boolean firstConversion, boolean review) {
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
                    comm.setStatus(REJECTED);
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
        int days = currentConfig().getReturnPeriodDays();
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        int approved = 0;
        for (AffiliateCommissionEntity comm : commissionRepo.findByStatus(PENDING)) {
            if (comm.getCreatedAt() != null && comm.getCreatedAt().isBefore(cutoff)) {
                comm.setStatus(APPROVED);
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
            comm.setStatus(APPROVED);
            comm.setApprovedAt(Instant.now());
        } else {
            comm.setStatus(REJECTED);
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
        return createPayoutRequest(userId, WALLET);
    }

    /**
     * Affiliate requests a payout of their APPROVED commissions via the given {@code method}
     * (WALLET/BANK/PAYPAL), validating that the corresponding payout details are configured and
     * snapshotting the destination onto the created {@link AffiliatePayoutEntity}.
     */
    @Transactional
    public AffiliatePayoutEntity requestPayout(UUID userId, String method) {
        return createPayoutRequest(userId, method);
    }

    /** Cuerpo sin anotar que comparten las dos sobrecargas públicas de {@code requestPayout}. */
    private AffiliatePayoutEntity createPayoutRequest(UUID userId, String method) {
        String m = method == null ? WALLET : method.toUpperCase();
        if (!m.matches("WALLET|BANK|PAYPAL")) {
            throw new BusinessException("INVALID_PAYOUT_METHOD", "Método de cobro no válido");
        }
        AffiliateEntity affiliate = affiliateRepo.findByUser_Id(userId).orElseThrow(
                () -> new NotFoundException(AFFILIATE_NOT_FOUND));
        if ("BANK".equals(m) && (affiliate.getBankIban() == null || affiliate.getBankHolder() == null)) {
            throw new BusinessException("PAYOUT_DETAILS_MISSING", "Configura tus datos bancarios primero");
        }
        if ("PAYPAL".equals(m) && (affiliate.getPaypalEmail() == null || affiliate.getPaypalEmail().isBlank())) {
            throw new BusinessException("PAYOUT_DETAILS_MISSING", "Configura tu PayPal primero");
        }
        if (payoutRepo.existsByAffiliateIdAndStatus(affiliate.getId(), REQUESTED)) {
            throw new BusinessException(
                    "Ya tienes una solicitud de pago pendiente");
        }
        List<AffiliateCommissionEntity> approved = commissionRepo.findByAffiliateIdAndStatus(affiliate.getId(), APPROVED);
        long total = approved.stream().mapToLong(AffiliateCommissionEntity::getAmountCents).sum();
        AffiliateProgramConfigEntity cfg = currentConfig();
        if (total < cfg.getMinPayoutCents()) {
            throw new BusinessException(
                    "Saldo aprobado por debajo del pago mínimo");
        }
        AffiliatePayoutEntity payout = payoutRepo.save(AffiliatePayoutEntity.builder().affiliateId(affiliate.getId())
                .amountCents(total).currency(cfg.getCurrency()).status(REQUESTED).method(m)
                .commissionCount(approved.size()).requestedAt(Instant.now()).note("Solicitud del afiliado")
                .destHolder("BANK".equals(m) ? affiliate.getBankHolder() : null)
                .destIban("BANK".equals(m) ? affiliate.getBankIban() : null)
                .destBic("BANK".equals(m) ? affiliate.getBankBic() : null)
                .destPaypalEmail("PAYPAL".equals(m) ? affiliate.getPaypalEmail() : null)
                .build());
        notifyStaff("AFFILIATE_PAYOUT_REQUEST", "Solicitud de pago de afiliado",
                "Un afiliado ha solicitado el pago de sus comisiones aprobadas (" + m + ").");
        return payout;
    }

    /**
     * Operator approves & executes a WALLET payout: credits the affiliate's wallet and marks the
     * APPROVED commissions as PAID. No automatic payment happens without this explicit approval
     * (DROP-651). Ejecuta el mismo pago que {@link #approvePayout(UUID, UUID, String)} pero sin admin ni
     * referencia externa.
     */
    @Transactional
    public AffiliatePayoutEntity approvePayout(UUID payoutId) {
        return executePayout(payoutId, null, null);
    }

    /**
     * Operator approves & executes a payout. For {@code WALLET} method, credits the affiliate's
     * wallet (as before). For an external method ({@code BANK}/{@code PAYPAL}) the payment was
     * already executed by the admin OUTSIDE the app (bank transfer / PayPal payout) — this only
     * records it: marks the payout PAID with the given {@code reference} and {@code adminUserId},
     * WITHOUT touching the wallet. Either way the APPROVED commissions move to PAID (Task 7).
     */
    @Transactional
    public AffiliatePayoutEntity approvePayout(UUID payoutId, UUID adminUserId, String reference) {
        return executePayout(payoutId, adminUserId, reference);
    }

    /**
     * Cuerpo sin anotar del pago: lo comparten las dos sobrecargas públicas y el pago directo del
     * operador. Al llamarse dentro de la misma instancia no pasaría por el proxy, así que la transacción
     * la abre siempre el método público de entrada.
     */
    private AffiliatePayoutEntity executePayout(UUID payoutId, UUID adminUserId, String reference) {
        AffiliatePayoutEntity payout = payoutRepo.findById(payoutId).orElseThrow(
                () -> new NotFoundException("Payout not found"));
        if ("PAID".equals(payout.getStatus())) {
            return payout; // idempotent — never pay twice
        }
        if (REJECTED.equals(payout.getStatus())) {
            throw new BusinessException("El pago fue rechazado");
        }
        AffiliateEntity affiliate = affiliateRepo.findById(payout.getAffiliateId()).orElseThrow(
                () -> new NotFoundException(AFFILIATE_NOT_FOUND));
        List<AffiliateCommissionEntity> approved = commissionRepo.findByAffiliateIdAndStatus(affiliate.getId(), APPROVED);
        long total = approved.stream().mapToLong(AffiliateCommissionEntity::getAmountCents).sum();
        if (total <= 0) {
            payout.setStatus(REJECTED);
            payout.setNote("Sin comisiones aprobadas que liquidar");
            return payoutRepo.save(payout);
        }
        UUID userId = affiliate.getUser().getId();
        UUID txId = null;
        if (WALLET.equals(payout.getMethod())) {
            WalletTransaction tx = walletUseCase.adminTopup(userId, total, "Affiliate commission payout",
                    "affiliate-payout-" + payout.getId());
            txId = tx != null ? tx.getId() : null;
            payout.setWalletTxId(txId);
        } else {
            // Pago EXTERNO ya ejecutado por el ADMIN fuera de la app: solo se registra.
            payout.setPaidReference(reference);
            payout.setPaidBy(adminUserId);
        }
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
        payout.setProcessedAt(now);
        payout.setCommissionCount(approved.size());
        payoutRepo.save(payout);
        notify(userId, "AFFILIATE_PAYOUT_PAID", "Pago de comisiones realizado",
                WALLET.equals(payout.getMethod()) ? "Tus comisiones se han abonado a tu wallet."
                        : "Tus comisiones han sido pagadas.");
        log.info("::> [AFFILIATE] Payout {} paid {} cents to affiliate {} (method {}, wallet tx {})", payout.getId(),
                total, affiliate.getId(), payout.getMethod(), txId);
        return payout;
    }

    @Transactional
    public AffiliatePayoutEntity rejectPayout(UUID payoutId, String reason) {
        AffiliatePayoutEntity payout = payoutRepo.findById(payoutId).orElseThrow(
                () -> new NotFoundException("Payout not found"));
        if ("PAID".equals(payout.getStatus())) {
            throw new BusinessException("El pago ya se ejecutó");
        }
        payout.setStatus(REJECTED);
        payout.setProcessedAt(Instant.now());
        payout.setNote(reason != null ? reason : "Rechazado por el operador");
        return payoutRepo.save(payout);
    }

    /** Admin direct settlement: creates an approved payout and executes it in one step. */
    @Transactional
    public long payoutApproved(UUID affiliateId, boolean force) {
        // Guard de existencia: 404 antes de tocar comisiones. No se guarda la entidad porque quien
        // ejecuta el pago es approvePayout, que la relee por su cuenta.
        if (affiliateRepo.findById(affiliateId).isEmpty()) {
            throw new NotFoundException(AFFILIATE_NOT_FOUND);
        }
        long approvedTotal = commissionRepo.findByAffiliateIdAndStatus(affiliateId, APPROVED).stream()
                .mapToLong(AffiliateCommissionEntity::getAmountCents).sum();
        if (approvedTotal <= 0) {
            return 0;
        }
        if (!force && approvedTotal < currentConfig().getMinPayoutCents()) {
            return 0;
        }
        AffiliatePayoutEntity payout = payoutRepo.save(AffiliatePayoutEntity.builder().affiliateId(affiliateId)
                .amountCents(approvedTotal).currency(currentConfig().getCurrency()).status(APPROVED).method(WALLET)
                .requestedAt(Instant.now()).note("Pago directo del operador").build());
        AffiliatePayoutEntity done = executePayout(payout.getId(), null, null);
        return "PAID".equals(done.getStatus()) ? done.getAmountCents() : 0;
    }

    @Transactional(readOnly = true)
    public List<AffiliatePayoutEntity> payoutsForAffiliate(UUID affiliateId) {
        return payoutRepo.findByAffiliateIdOrderByCreatedAtDesc(affiliateId);
    }

    @Transactional(readOnly = true)
    public List<AffiliatePayoutEntity> pendingPayouts() {
        return payoutRepo.findByStatusOrderByCreatedAtDesc(REQUESTED);
    }

    /* ============================ Payout profile (Task 5) ============================ */

    /** Returns the affiliate's payout profile with the IBAN masked (last 4 digits only). */
    @Transactional(readOnly = true)
    public PayoutProfileView getPayoutProfile(UUID userId) {
        AffiliateEntity a = affiliateRepo.findByUser_Id(userId).orElseThrow(
                () -> new NotFoundException(AFFILIATE_NOT_FOUND));
        return new PayoutProfileView(a.getPayoutMethod(), a.getBankHolder(), maskIban(a.getBankIban()),
                a.getBankBic(), a.getPaypalEmail(),
                a.getBankHolder() != null && a.getBankIban() != null,
                a.getPaypalEmail() != null && !a.getPaypalEmail().isBlank());
    }

    /** Updates the affiliate's payout profile; requires the caller's current password to confirm. */
    @Transactional
    public void updatePayoutProfile(UUID userId, PayoutProfileUpdateRequest req) {
        UserEntity user = userRepository.findById(userId).orElseThrow(
                () -> new NotFoundException("User not found"));
        if (req.password() == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BusinessException("INVALID_PASSWORD", "Contraseña incorrecta");
        }
        AffiliateEntity a = affiliateRepo.findByUser_Id(userId).orElseThrow(
                () -> new NotFoundException(AFFILIATE_NOT_FOUND));
        String iban = normalizedIban(req.iban());
        String email = normalizedPaypalEmail(req.paypalEmail());
        mergePayoutProfile(a, req, iban, email);
        affiliateRepo.save(a);
    }

    /** IBAN sin espacios y en mayúsculas; si viene con contenido y no valida, se rechaza la operación. */
    private static String normalizedIban(String raw) {
        String iban = raw == null ? null : raw.replaceAll("\\s", "").toUpperCase();
        if (hasText(iban) && !IbanValidator.isValid(iban)) {
            throw new BusinessException("INVALID_IBAN", "IBAN no válido");
        }
        return iban;
    }

    /** Email de PayPal recortado; si viene con contenido y no valida, se rechaza la operación. */
    private static String normalizedPaypalEmail(String raw) {
        String email = raw == null ? null : raw.trim();
        if (hasText(email) && !PAYOUT_EMAIL.matcher(email).matches()) {
            throw new BusinessException("INVALID_EMAIL", "Email de PayPal no válido");
        }
        return email;
    }

    /**
     * Semántica de MERGE (no reemplazo total): solo se actualiza el campo que llega con valor. Así, si el
     * afiliado reguarda su perfil sin reteclear el IBAN (que se relee enmascarado), su IBAN NO se borra.
     */
    private static void mergePayoutProfile(AffiliateEntity a, PayoutProfileUpdateRequest req, String iban,
            String email) {
        if (hasText(req.bankHolder())) {
            a.setBankHolder(req.bankHolder().trim());
        }
        if (hasText(iban)) {
            a.setBankIban(iban);
        }
        if (hasText(req.bic())) {
            a.setBankBic(req.bic().trim());
        }
        if (hasText(email)) {
            a.setPaypalEmail(email);
        }
        if (req.preferredMethod() != null && req.preferredMethod().matches("WALLET|BANK|PAYPAL")) {
            a.setPayoutMethod(req.preferredMethod());
        }
    }

    /** Un campo "llega con valor" cuando no es nulo y no está en blanco: lo demás se ignora en el merge. */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String maskIban(String iban) {
        if (iban == null || iban.length() < 4) {
            return iban;
        }
        return "****" + iban.substring(iban.length() - 4);
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
        if (!s.equals(PENDING) && !s.equals(ACTIVE) && !s.equals("SUSPENDED")) {
            throw new BusinessException("Estado de afiliado no válido");
        }
        AffiliateEntity a = affiliateRepo.findById(affiliateId).orElseThrow(
                () -> new NotFoundException(AFFILIATE_NOT_FOUND));
        boolean wasPending = PENDING.equals(a.getStatus());
        a.setStatus(s);
        a.setActive(ACTIVE.equals(s));
        AffiliateEntity saved = affiliateRepo.save(a);
        affiliateIndexer.indexAffiliate(saved); // auto-sync del índice al cambiar el estado
        // Avisa al usuario cuando el admin APRUEBA su solicitud (PENDING → ACTIVE).
        if (wasPending && ACTIVE.equals(s) && a.getUser() != null) {
            notify(a.getUser().getId(), "AFFILIATE_APPROVED", "Tu solicitud de afiliado ha sido aprobada",
                    "¡Enhorabuena! Ya puedes usar el programa de afiliados: comparte tu enlace para empezar a "
                            + "ganar comisiones.");
        }
        return saved;
    }
}
