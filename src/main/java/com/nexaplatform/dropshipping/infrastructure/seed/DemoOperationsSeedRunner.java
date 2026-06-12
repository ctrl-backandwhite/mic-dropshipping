package com.nexaplatform.dropshipping.infrastructure.seed;

import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.enums.MarginType;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleScope;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Seeds the platform with ~50 customers, ~15 partners, addresses, orders in every stage of
 * the dropshipping pipeline (PENDING → DELIVERED), wallet recharges/payments, partner
 * subscriptions and pricing rules. Runs once on first boot after the catalog seed.
 * Disable with {@code nexadrop.demo-seed.enabled=false}.
 */
@Slf4j
@Component
@Order(20) // run after DemoCatalogSeedRunner + plan seed
@ConditionalOnProperty(prefix = "nexadrop.demo-seed", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DemoOperationsSeedRunner {

    private final UserRepository userRepository;
    private final UserAddressRepository addressRepository;
    private final AddressRepository orderAddressRepository;
    private final WalletRepository walletRepository;
    private final ProductSpecificationRepository specRepository;
    private final ProductAttributeRepository attrRepository;
    private final ProductTagRepository tagRepository;
    private final ShippingZoneRepository zoneRepository;
    private final ShippingRateRepository rateRepository;
    private final ProductHistoryRepository historyRepository;
    private final com.nexaplatform.dropshipping.application.usecase.CatalogUseCase catalogService;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.AgentProfileRepository agentProfileRepo;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.AcademyCourseRepository courseRepo;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.MentorProfileRepository mentorProfileRepo;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.WarehouseRepository warehouseRepo;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.AdTrendRepository adTrendRepo;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.NotificationRepository notificationRepo;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductWarehouseStockRepository pwsRepo;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository shopConnectionRepo;
    private final WalletUseCase walletUseCase;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;
    private final SubscriptionPlanRepository planRepository;
    private final CustomerSubscriptionRepository subscriptionRepository;
    private final PriceRuleRepository priceRuleRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final PasswordEncoder passwordEncoder;

    private final Random rnd = new Random(424242L);

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        log.info("NX036 demo ops seed running (idempotent per-section)");

        List<UserEntity> customers = userRepository.findByRoleOrderByCreatedAtDesc(UserRole.USER);
        if (countDemo(customers) < 40) {
            customers = mergeDistinct(customers, seedCustomers(50));
        }
        List<UserEntity> partners = userRepository.findByRoleOrderByCreatedAtDesc(UserRole.PARTNER);
        if (countDemo(partners) < 10) {
            partners = mergeDistinct(partners, seedPartners(15));
        }
        List<UserEntity> operators = userRepository.findByRoleOrderByCreatedAtDesc(UserRole.OPERATOR);
        if (operators.size() < 5)
            seedOperators(5);

        // demo-only users (skip the canonical admin/partner/operator/customer demo accounts)
        List<UserEntity> demoCustomers = customers.stream()
                .filter(u -> u.getEmail() != null && u.getEmail().contains("@demo.nx036.local")).toList();
        List<UserEntity> demoPartners = partners.stream()
                .filter(u -> u.getEmail() != null && u.getEmail().contains("@partners.nx036.local")).toList();

        if (addressRepository.count() < 50)
            seedAddressesFor(demoCustomers, demoPartners);
        if (paymentRepository.count() < 50)
            seedWalletDeposits(demoCustomers, demoPartners);
        if (orderRepository.count() < 30)
            seedOrders(demoCustomers, 60);
        if (subscriptionRepository.count() < 5)
            seedSubscriptions(demoPartners);
        if (priceRuleRepository.count() < 5)
            seedPriceRules();
        if (specRepository.count() < 50)
            seedSpecifications();
        if (attrRepository.count() < 50)
            seedAttributesAndTags();
        if (zoneRepository.count() < 20)
            seedShipping();
        seedProductExtras();
        seedExtraCategories();
        seedProductFlagsAndVideos();
        if (historyRepository.count() < 50)
            seedPriceHistory();
        if (agentProfileRepo.count() == 0)
            seedAgents();
        if (courseRepo.count() == 0)
            seedCourses();
        if (mentorProfileRepo.count() == 0)
            seedMentors(demoPartners);
        if (warehouseRepo.count() == 0)
            seedWarehouses();
        if (adTrendRepo.count() == 0)
            seedAdTrends();
        if (notificationRepo.count() < 20)
            seedNotifications(demoCustomers);
        if (shopConnectionRepo.count() == 0)
            seedShopConnections(demoCustomers);
        seedProductBrandsAndReadyToShip();

        log.info("NX036 demo ops seed finished — users={} orders={} payments={} subs={} priceRules={}",
                userRepository.count(), orderRepository.count(), paymentRepository.count(),
                subscriptionRepository.count(), priceRuleRepository.count());
    }

    private static int countDemo(List<UserEntity> us) {
        return (int) us.stream().filter(u -> u.getEmail() != null && u.getEmail().contains("@demo.nx036.local")
                || u.getEmail() != null && u.getEmail().contains("@partners.nx036.local")).count();
    }

    private static List<UserEntity> mergeDistinct(List<UserEntity> a, List<UserEntity> b) {
        Map<UUID, UserEntity> m = new LinkedHashMap<>();
        a.forEach(u -> m.put(u.getId(), u));
        b.forEach(u -> m.put(u.getId(), u));
        return new ArrayList<>(m.values());
    }

    /* ================================ users ================================ */

    private List<UserEntity> seedCustomers(int n) {
        String[][] persons = {{"María", "García", "ES"}, {"Carlos", "López", "MX"}, {"Sofía", "Martínez", "AR"},
                {"Diego", "Rodríguez", "CO"}, {"Lucía", "Sánchez", "CL"}, {"Mateo", "Fernández", "ES"},
                {"Camila", "González", "PE"}, {"Sebastián", "Pérez", "ES"}, {"Valentina", "Hernández", "MX"},
                {"Daniela", "Díaz", "BR"}, {"James", "Smith", "US"}, {"Olivia", "Brown", "GB"},
                {"Liam", "Wilson", "CA"}, {"Emma", "Anderson", "AU"}, {"Noah", "Thomas", "US"}, {"Ava", "Taylor", "GB"},
                {"Ethan", "Moore", "IE"}, {"Mia", "Jackson", "NZ"}, {"Lucas", "White", "US"},
                {"Charlotte", "Harris", "GB"}, {"João", "Silva", "BR"}, {"Beatriz", "Santos", "PT"},
                {"Pedro", "Costa", "PT"}, {"Ana", "Almeida", "BR"}, {"Rafael", "Oliveira", "BR"},
                // DROP-642: use latin (pinyin) display names for CN/HK/SG demo users so mentor
                // profiles built on these accounts never surface CJK names to the storefront.
                {"Mariana", "Pereira", "PT"}, {"Ming", "Li", "CN"}, {"Fang", "Wang", "CN"}, {"Wei", "Zhang", "CN"},
                {"Fang", "Liu", "HK"}, {"Min", "Chen", "SG"}, {"Na", "Yang", "CN"}, {"Hiroshi", "Tanaka", "JP"},
                {"Sakura", "Yamamoto", "JP"},
                {"Min-jun", "Kim", "KR"}, {"Seo-yeon", "Park", "KR"}, {"Arjun", "Patel", "IN"},
                {"Priya", "Sharma", "IN"}, {"Rohan", "Singh", "IN"}, {"Aisha", "Khan", "AE"}, {"Hugo", "Bernard", "FR"},
                {"Camille", "Dubois", "FR"}, {"Lukas", "Müller", "DE"}, {"Lena", "Schmidt", "DE"},
                {"Giulia", "Rossi", "IT"}, {"Lorenzo", "Bianchi", "IT"}, {"Jan", "Kowalski", "PL"},
                {"Eva", "Novak", "PL"}, {"Erik", "Eriksson", "SE"}, {"Ingrid", "Olsen", "NO"},};

        List<UserEntity> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, persons.length); i++) {
            String[] p = persons[i];
            String slug = slugify(p[0] + p[1]) + (i + 1);
            String email = (slug + "@demo.nx036.local").toLowerCase();
            if (userRepository.existsByEmail(email))
                continue;
            String language = languageForCountry(p[2]);
            UserEntity u = UserEntity.builder().email(email).passwordHash(passwordEncoder.encode("Demo!Customer2026"))
                    .role(UserRole.USER).active(true).displayName(p[0] + " " + p[1]).country(p[2]).language(language)
                    .build();
            out.add(userRepository.save(u));
        }
        // ensure wallets exist (the bootstrap listener handles this for new boots, but seed re-creates them just in case)
        out.forEach(u -> walletUseCase.getOrCreate(u.getId()));
        log.info("Seeded {} customers", out.size());
        return out;
    }

    private List<UserEntity> seedPartners(int n) {
        String[][] shops = {{"trendypicks-mx", "TrendyPicks MX", "MX", "Shopify"},
                {"madrid-essentials", "Madrid Essentials", "ES", "Shopify"},
                {"bcn-stylebox", "Barcelona StyleBox", "ES", "WooCommerce"},
                {"saopaulo-deals", "São Paulo Deals", "BR", "WooCommerce"},
                {"buenosaires-mart", "BA Mart", "AR", "Shopify"}, {"london-trends", "London Trends", "GB", "Shopify"},
                {"berlin-uptown", "Berlin Uptown", "DE", "WooCommerce"}, {"paris-pop", "Paris Pop", "FR", "Shopify"},
                {"toronto-vault", "Toronto Vault", "CA", "Shopify"},
                {"miami-imports", "Miami Imports", "US", "BigCommerce"},
                {"lisbon-loop", "Lisbon Loop", "PT", "WooCommerce"}, {"tokyo-curated", "Tokyo Curated", "JP", "Custom"},
                {"seoul-aesthetic", "Seoul Aesthetic", "KR", "Cafe24"},
                {"sydney-supply", "Sydney Supply", "AU", "Shopify"}, {"dubai-prime", "Dubai Prime", "AE", "Shopify"},};
        List<UserEntity> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, shops.length); i++) {
            String[] s = shops[i];
            String email = s[0] + "@partners.nx036.local";
            if (userRepository.existsByEmail(email))
                continue;
            UserEntity u = UserEntity.builder().email(email).passwordHash(passwordEncoder.encode("Demo!Partner2026"))
                    .role(UserRole.PARTNER).active(true).displayName(s[1]).companyName(s[1] + " · " + s[3])
                    .country(s[2]).language(languageForCountry(s[2])).build();
            out.add(userRepository.save(u));
        }
        out.forEach(u -> walletUseCase.getOrCreate(u.getId()));
        log.info("Seeded {} partners", out.size());
        return out;
    }

    private void seedOperators(int n) {
        String[][] ops = {{"ops.maria", "María Ops", "ES"}, {"ops.carlos", "Carlos Ops", "MX"},
                {"ops.lin", "Lin Ops", "CN"}, {"ops.priya", "Priya Ops", "IN"}, {"ops.lucas", "Lucas Ops", "BR"},};
        for (int i = 0; i < Math.min(n, ops.length); i++) {
            String[] o = ops[i];
            String email = o[0] + "@ops.nx036.local";
            if (userRepository.existsByEmail(email))
                continue;
            userRepository.save(UserEntity.builder().email(email)
                    .passwordHash(passwordEncoder.encode("Demo!Operator2026")).role(UserRole.OPERATOR).active(true)
                    .displayName(o[1]).country(o[2]).language(languageForCountry(o[2])).build());
        }
    }

    /* ============================== addresses ============================== */

    private void seedAddressesFor(List<UserEntity> customers, List<UserEntity> partners) {
        int created = 0;
        for (UserEntity u : customers)
            created += seedAddresses(u, 1 + rnd.nextInt(3));
        for (UserEntity u : partners)
            created += seedAddresses(u, 1 + rnd.nextInt(2));
        log.info("Seeded {} addresses", created);
    }

    private int seedAddresses(UserEntity u, int count) {
        String[] cities = citiesFor(u.getCountry());
        if (cities.length == 0)
            cities = new String[]{"Capital"};
        String[] labels = {"Casa", "Oficina", "Casa de mis padres", "Almacén"};
        int created = 0;
        for (int i = 0; i < count; i++) {
            String city = cities[rnd.nextInt(cities.length)];
            UserAddressEntity a = UserAddressEntity.builder().user(u).label(labels[i % labels.length])
                    .fullName(u.getDisplayName() != null ? u.getDisplayName() : u.getEmail())
                    .phone("+" + (10 + rnd.nextInt(89)) + " " + (100000000L + Math.abs(rnd.nextLong()) % 900000000L))
                    .line1(streetFor(u.getCountry()) + " " + (1 + rnd.nextInt(450)))
                    .line2(rnd.nextInt(3) == 0 ? "Piso " + (1 + rnd.nextInt(8)) + (char) ('A' + rnd.nextInt(5)) : null)
                    .city(city).state(stateFor(u.getCountry(), city)).postalCode(postalFor(u.getCountry()))
                    .country(u.getCountry() != null ? u.getCountry() : "US").isDefault(i == 0).build();
            addressRepository.save(a);
            created++;
        }
        return created;
    }

    /* ============================== wallet recharges ============================== */

    private void seedWalletDeposits(List<UserEntity> customers, List<UserEntity> partners) {
        int deposits = 0;
        for (UserEntity u : customers) {
            int dCount = 1 + rnd.nextInt(3);
            for (int i = 0; i < dCount; i++) {
                long cents = 5000L + (long) rnd.nextInt(95000); // $50 — $1000
                createDeposit(u, cents, pickMethod());
                deposits++;
            }
        }
        for (UserEntity u : partners) {
            int dCount = 2 + rnd.nextInt(4);
            for (int i = 0; i < dCount; i++) {
                long cents = 50000L + (long) rnd.nextInt(950000); // $500 — $10k
                createDeposit(u, cents, pickMethod());
                deposits++;
            }
        }
        log.info("Seeded {} wallet deposits (payments + transactions)", deposits);
    }

    private void createDeposit(UserEntity u, long cents, PaymentMethod method) {
        walletUseCase.getOrCreate(u.getId());
        WalletEntity w = walletRepository.findByUser_Id(u.getId()).orElseThrow();
        PaymentEntity p = PaymentEntity.builder().user(u).wallet(w).method(method).status(PaymentStatus.SUCCEEDED)
                .amountUsdCents(cents).settlementCurrency("USD")
                .settlementAmount(BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100)))
                .provider(providerFor(method)).providerRef("seed_" + UUID.randomUUID().toString().substring(0, 12))
                .idempotencyKey("seed-dep-" + UUID.randomUUID()).build();
        paymentRepository.save(p);
        walletUseCase.deposit(u.getId(), cents, p.getId(), "seed-tx-" + UUID.randomUUID(),
                "Recarga de prueba (" + method.name() + ")");
    }

    /* ============================== orders ============================== */

    private List<CustomerOrderEntity> seedOrders(List<UserEntity> customers, int count) {
        List<ProductEntity> products = productRepository.findAll();
        if (products.isEmpty()) {
            log.warn("No products in catalog — skipping order seed");
            return List.of();
        }

        // DROP-450: distribución completa con todos los status incluyendo REFUNDED y todos los pesos
        // ajustados para que el dashboard muestre un mix realista (no todo PENDING).
        OrderStatus[] distribution = {OrderStatus.PENDING, // 1
                OrderStatus.AWAITING_PAYMENT, // 2
                OrderStatus.PAID, // 3
                OrderStatus.PAID, // 4
                OrderStatus.FORWARDED, // 5
                OrderStatus.FORWARDED, // 6
                OrderStatus.SHIPPED, // 7
                OrderStatus.SHIPPED, // 8
                OrderStatus.SHIPPED, // 9
                OrderStatus.DELIVERED, // 10
                OrderStatus.DELIVERED, // 11
                OrderStatus.DELIVERED, // 12
                OrderStatus.DELIVERED, // 13
                OrderStatus.DELIVERED, // 14
                OrderStatus.CANCELLED, // 15
                OrderStatus.REFUNDED, // 16
        };
        // Notas variadas para que las órdenes parezcan reales
        String[] noteVariants = {null, null, null, "Por favor empacar con cuidado", "Cliente solicita factura",
                "Dirección secundaria — llamar antes", "Regalo, sin recibo en la caja", "Entrega después de las 18:00",
                "Producto frágil — usar burbuja extra",};

        List<CustomerOrderEntity> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UserEntity customer = customers.get(rnd.nextInt(customers.size()));
            OrderStatus status = distribution[i % distribution.length];

            List<UserAddressEntity> addrs = addressRepository
                    .findByUser_IdOrderByIsDefaultDescCreatedAtDesc(customer.getId());
            if (addrs.isEmpty())
                continue;
            UserAddressEntity src = addrs.get(0);

            AddressEntity ship = orderAddressRepository
                    .save(AddressEntity.builder().fullName(src.getFullName()).phone(src.getPhone())
                            .email(customer.getEmail()).line1(src.getLine1()).line2(src.getLine2()).city(src.getCity())
                            .state(src.getState()).postalCode(src.getPostalCode()).country(src.getCountry()).build());

            CustomerOrderEntity o = CustomerOrderEntity.builder()
                    .orderNumber("NX-DEMO-" + String.format("%05d", 10000 + i)).userId(customer.getId())
                    .externalOrderId("DEMO-" + (10000 + i)).shippingAddress(ship).billingAddress(ship)
                    .status(OrderStatus.PENDING).currency("USD").subtotalCents(0).shippingCents(0).taxCents(0)
                    .totalCents(0).notes(noteVariants[rnd.nextInt(noteVariants.length)])
                    .placedAt(Instant.now().minus(rnd.nextInt(120), ChronoUnit.DAYS)).build();

            // 1-6 items per order — más variedad para que los totales se distribuyan.
            int itemCount = 1 + rnd.nextInt(6);
            int subtotal = 0;
            for (int j = 0; j < itemCount; j++) {
                ProductEntity prod = products.get(rnd.nextInt(products.size()));
                // Qty con cola: 70% 1-3, 20% 4-10, 10% 11-50 — para ver totales grandes
                int qtyBucket = rnd.nextInt(100);
                int qty = qtyBucket < 70
                        ? 1 + rnd.nextInt(3)
                        : qtyBucket < 90 ? 4 + rnd.nextInt(7) : 11 + rnd.nextInt(40);
                // Unit price: si la base está demasiado uniforme, añadimos variación ±30%
                int baseUnit = (int) Math.round(prod.getBasePrice().doubleValue() * 100);
                if (baseUnit < 100)
                    baseUnit = 200 + rnd.nextInt(15000); // 2..152 USD
                double variation = 0.7 + rnd.nextDouble() * 0.6; // 0.7..1.3
                int unitCents = Math.max(100, (int) Math.round(baseUnit * variation));
                int lineTotal = unitCents * qty;
                String img = prod.getImages() != null && !prod.getImages().isEmpty()
                        ? prod.getImages().get(0).getSourceUrl()
                        : null;
                OrderItemEntity it = OrderItemEntity.builder().order(o).product(prod).titleSnapshot(prod.getTitleZh())
                        .imageUrlSnapshot(img).skuSnapshot(prod.getExternalId()).unitPriceCents(unitCents)
                        .costCents((int) Math.round(unitCents * 0.65)).quantity(qty).lineTotalCents(lineTotal).build();
                o.getItems().add(it);
                subtotal += lineTotal;
            }
            int shipping = 0; // free shipping in demo
            int tax = (int) Math.round(subtotal * 0.0);
            o.setSubtotalCents(subtotal);
            o.setShippingCents(shipping);
            o.setTaxCents(tax);
            o.setTotalCents(subtotal + shipping + tax);

            // Apply status + matching timestamps
            applyStatusTimeline(o, status);
            CustomerOrderEntity saved = orderRepository.save(o);

            // Charge wallet for orders that progressed past PAID (only if balance suffices)
            if (status == OrderStatus.PAID || status == OrderStatus.FORWARDED || status == OrderStatus.SHIPPED
                    || status == OrderStatus.DELIVERED) {
                WalletEntity w = walletRepository.findByUser_Id(customer.getId()).orElse(null);
                if (w != null && (w.getBalanceUsdCents() - w.getHoldUsdCents()) >= saved.getTotalCents()) {
                    walletUseCase.charge(customer.getId(), saved.getTotalCents(), saved.getId(),
                            "seed-charge-" + saved.getId(), "Order " + saved.getOrderNumber());
                }
            }
            created.add(saved);
        }
        log.info("Seeded {} orders", created.size());
        return created;
    }

    private void applyStatusTimeline(CustomerOrderEntity o, OrderStatus target) {
        Instant placed = o.getPlacedAt();
        o.setStatus(target);
        switch (target) {
            case FORWARDED -> o.setForwardedAt(placed.plus(1 + rnd.nextInt(2), ChronoUnit.HOURS));
            case SHIPPED -> {
                o.setForwardedAt(placed.plus(1 + rnd.nextInt(2), ChronoUnit.HOURS));
                o.setShippedAt(placed.plus(1 + rnd.nextInt(3), ChronoUnit.DAYS));
            }
            case DELIVERED -> {
                o.setForwardedAt(placed.plus(1 + rnd.nextInt(2), ChronoUnit.HOURS));
                o.setShippedAt(placed.plus(1 + rnd.nextInt(3), ChronoUnit.DAYS));
                o.setDeliveredAt(placed.plus(5 + rnd.nextInt(10), ChronoUnit.DAYS));
            }
            case CANCELLED -> o.setCancelledAt(placed.plus(2 + rnd.nextInt(24), ChronoUnit.HOURS));
            default -> {
                /* PENDING / AWAITING_PAYMENT / PAID — no extra timestamps */ }
        }
    }

    /* ============================== subscriptions ============================== */

    private void seedSubscriptions(List<UserEntity> partners) {
        List<SubscriptionPlanEntity> plans = planRepository.findAll();
        if (plans.isEmpty()) {
            log.warn("No subscription plans defined — skipping subscriptions");
            return;
        }
        // Pick a non-trivial paid plan (skip "free" if present)
        SubscriptionPlanEntity paid = plans.stream().filter(p -> p.getPriceMonthlyCents() > 0).findFirst()
                .orElse(plans.get(0));
        SubscriptionPlanEntity free = plans.stream().filter(p -> p.getPriceMonthlyCents() == 0).findFirst()
                .orElse(plans.get(0));
        SubscriptionStatus[] statuses = {SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE,
                SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING,
                SubscriptionStatus.PAST_DUE, SubscriptionStatus.PAUSED, SubscriptionStatus.CANCELED,};
        int created = 0;
        for (int i = 0; i < partners.size(); i++) {
            UserEntity p = partners.get(i);
            if (!subscriptionRepository.findByUserId(p.getId()).isEmpty())
                continue;
            SubscriptionPlanEntity assignedPlan = i % 3 == 0 ? free : paid;
            boolean isFreePlan = assignedPlan.getPriceMonthlyCents() == 0 && assignedPlan.getPriceYearlyCents() == 0;
            // DROP-634: un plan gratuito (FREE) no puede estar en periodo de prueba —
            // un plan sin coste ya es "activo" desde el primer momento. Forzamos
            // ACTIVE cuando el plan asignado es gratuito.
            SubscriptionStatus st = statuses[i % statuses.length];
            if (isFreePlan && st == SubscriptionStatus.TRIALING) {
                st = SubscriptionStatus.ACTIVE;
            }
            Instant now = Instant.now();
            CustomerSubscriptionEntity s = CustomerSubscriptionEntity.builder().user(p).plan(assignedPlan)
                    // DROP-634: usamos el formato canónico MONTHLY/YEARLY (igual que el
                    // use case real) para que el frontend lo traduzca siempre vía i18n.
                    .status(st).billingPeriod(i % 2 == 0 ? "MONTHLY" : "YEARLY")
                    .currentPeriodStart(now.minus(15 + rnd.nextInt(30), ChronoUnit.DAYS))
                    .currentPeriodEnd(now.plus(15 + rnd.nextInt(30), ChronoUnit.DAYS))
                    .canceledAt(st == SubscriptionStatus.CANCELED ? now.minus(5, ChronoUnit.DAYS) : null)
                    .trialEndsAt(st == SubscriptionStatus.TRIALING ? now.plus(7, ChronoUnit.DAYS) : null).build();
            subscriptionRepository.save(s);
            created++;
        }
        log.info("Seeded {} subscriptions", created);
    }

    /* ============================== pricing rules ============================== */

    private void seedPriceRules() {
        if (priceRuleRepository.count() >= 5)
            return;
        List<PriceRuleEntity> rules = new ArrayList<>();
        // Only add the GLOBAL default rule if one isn't already present (v2 SQL seed creates it).
        boolean hasGlobalDefault = priceRuleRepository.findAll().stream()
                .anyMatch(r -> r.getScope() == PriceRuleScope.GLOBAL && r.getMarginType() == MarginType.PERCENTAGE
                        && r.getScopeId() == null);
        if (!hasGlobalDefault) {
            rules.add(rule(PriceRuleScope.GLOBAL, null, MarginType.PERCENTAGE, "35.00", 100,
                    "Margen global por defecto (35%)"));
        }
        Map<String, BigDecimal> catMargins = Map.of("consumer-electronics", new BigDecimal("28.00"), "fashion-apparel",
                new BigDecimal("45.00"), "home-kitchen", new BigDecimal("38.00"), "beauty-personal-care",
                new BigDecimal("52.00"), "sports-outdoors", new BigDecimal("40.00"), "toys-gifts",
                new BigDecimal("48.00"));
        catMargins.forEach((slug, value) -> categoryRepository.findBySlug(slug).ifPresent(cat -> {
            // Prefer a localized name from translations; fall back to the zh column then the slug.
            String label = cat.getTranslations() == null
                    ? null
                    : cat.getTranslations().stream()
                            .filter(tr -> "es".equals(tr.getLanguage()) || "en".equals(tr.getLanguage()))
                            .map(tr -> tr.getName()).filter(n -> n != null && !n.isBlank()).findFirst().orElse(null);
            if (label == null || label.isBlank())
                label = cat.getNameZh();
            if (label == null || label.isBlank())
                label = cat.getSlug();
            rules.add(rule(PriceRuleScope.CATEGORY, cat.getId(), MarginType.PERCENTAGE, value.toPlainString(), 200,
                    "Margen categoría — " + label));
        }));
        supplierRepository.findAll().stream().limit(3).forEach(s -> rules.add(rule(PriceRuleScope.SUPPLIER, s.getId(),
                MarginType.PERCENTAGE, "30.00", 300, "Proveedor preferido — " + s.getName())));
        rules.add(rule(PriceRuleScope.GLOBAL, null, MarginType.FIXED, "1.50", 50,
                "Recargo fijo para productos de bajo coste"));

        priceRuleRepository.saveAll(rules);
        log.info("Seeded {} price rules", rules.size());
    }

    private PriceRuleEntity rule(PriceRuleScope scope, UUID scopeId, MarginType type, String value, int position,
            String description) {
        return PriceRuleEntity.builder().scope(scope).scopeId(scopeId).marginType(type)
                .marginValue(new BigDecimal(value)).active(true).position(position).description(description).build();
    }

    /* ============================== specifications ============================== */

    /** Multi-locale technical specifications: 6-9 per product, in 'es' and 'en'. */
    private void seedSpecifications() {
        List<ProductEntity> products = productRepository.findAll();
        int created = 0;
        for (ProductEntity p : products) {
            List<String[]> es = specsFor(p, "es");
            List<String[]> en = specsFor(p, "en");
            int pos = 0;
            for (String[] kv : es) {
                specRepository.save(ProductSpecificationEntity.builder().product(p).locale("es").specKey(kv[0])
                        .specValue(kv[1]).position(pos++).build());
                created++;
            }
            pos = 0;
            for (String[] kv : en) {
                specRepository.save(ProductSpecificationEntity.builder().product(p).locale("en").specKey(kv[0])
                        .specValue(kv[1]).position(pos++).build());
                created++;
            }
        }
        log.info("Seeded {} product specifications", created);
    }

    private List<String[]> specsFor(ProductEntity p, String lang) {
        String category = p.getCategory() != null ? p.getCategory().getSlug() : "general";
        boolean es = "es".equals(lang);
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{es ? "Marca" : "Brand", p.getBrand() != null ? p.getBrand() : "NX036 Generic"});
        out.add(new String[]{es ? "Material" : "Material", materialFor(category)});
        out.add(new String[]{es ? "Modelo" : "Model", p.getExternalId()});
        out.add(new String[]{es ? "País de origen" : "Country of origin", "CN"});
        out.add(new String[]{es ? "Peso neto" : "Net weight",
                (p.getWeightGrams() != null ? p.getWeightGrams() : 250 + rnd.nextInt(800)) + " g"});
        out.add(new String[]{es ? "Garantía" : "Warranty", (3 + rnd.nextInt(22)) + (es ? " meses" : " months")});
        out.add(new String[]{es ? "Lead time del proveedor" : "Supplier lead time",
                (2 + rnd.nextInt(8)) + (es ? " días" : " days")});
        if ("consumer-electronics".equals(category)) {
            out.add(new String[]{es ? "Voltaje" : "Voltage", "100-240V AC, 50/60Hz"});
            out.add(new String[]{es ? "Certificaciones" : "Certifications", "CE, FCC, RoHS"});
            out.add(new String[]{es ? "Conectividad" : "Connectivity", "Bluetooth 5.3 · USB-C"});
        } else if ("fashion-apparel".equals(category)) {
            out.add(new String[]{es ? "Composición" : "Composition", "Cotton 80% / Polyester 20%"});
            out.add(new String[]{es ? "Cuidado" : "Care",
                    es ? "Lavar a 30°C, no plancha directa" : "Machine wash 30°C"});
        } else if ("beauty-personal-care".equals(category)) {
            out.add(new String[]{es ? "Tipo de piel" : "Skin type", es ? "Todo tipo" : "All types"});
            out.add(new String[]{es ? "Caducidad" : "Shelf life", "24 " + (es ? "meses" : "months")});
        } else if ("home-kitchen".equals(category)) {
            out.add(new String[]{es ? "Apto para lavavajillas" : "Dishwasher safe", es ? "Sí" : "Yes"});
        }
        return out;
    }

    private String materialFor(String slug) {
        return switch (slug) {
            case "consumer-electronics" -> "ABS + Aluminum";
            case "fashion-apparel" -> "Cotton blend";
            case "home-kitchen" -> "Stainless steel 304";
            case "beauty-personal-care" -> "Silicone + ABS";
            case "sports-outdoors" -> "Polyester ripstop";
            case "toys-gifts" -> "Food-grade ABS";
            default -> "Mixed";
        };
    }

    /* ============================== attributes + tags ============================== */

    private void seedAttributesAndTags() {
        String[][] catTagPool = {{"consumer-electronics", "wireless,bluetooth,tech,gadget,charger,smart"},
                {"fashion-apparel", "unisex,casual,streetwear,trending,summer,winter"},
                {"home-kitchen", "kitchen,storage,eco,decor,organizer,minimalist"},
                {"beauty-personal-care", "skincare,beauty,cruelty-free,vegan,korean,kbeauty"},
                {"sports-outdoors", "fitness,outdoor,camping,gym,hiking,yoga"},
                {"toys-gifts", "kids,educational,gift,creative,steam,age3+"},};
        Map<String, String[]> tagsByCat = new HashMap<>();
        for (String[] e : catTagPool)
            tagsByCat.put(e[0], e[1].split(","));

        String[] ageGroups = {"adult", "teen", "kids", "all_ages"};
        String[] seasons = {"all_season", "summer", "winter", "spring"};

        List<ProductEntity> products = productRepository.findAll();
        int aCount = 0, tCount = 0;
        for (ProductEntity p : products) {
            String slug = p.getCategory() != null ? p.getCategory().getSlug() : "general";
            // attributes
            attrRepository.save(ProductAttributeEntity.builder().product(p).attrKey("brand")
                    .attrValue(p.getBrand() != null ? p.getBrand() : "NX036").build());
            attrRepository.save(ProductAttributeEntity.builder().product(p).attrKey("origin").attrValue("CN").build());
            attrRepository.save(ProductAttributeEntity.builder().product(p).attrKey("season")
                    .attrValue(seasons[rnd.nextInt(seasons.length)]).build());
            attrRepository.save(ProductAttributeEntity.builder().product(p).attrKey("age_group")
                    .attrValue(ageGroups[rnd.nextInt(ageGroups.length)]).build());
            attrRepository
                    .save(ProductAttributeEntity.builder().product(p).attrKey("category").attrValue(slug).build());
            aCount += 5;

            // tags (3-5 from category pool + one global)
            String[] pool = tagsByCat.getOrDefault(slug, new String[]{"trending"});
            int n = 3 + rnd.nextInt(3);
            Set<String> picks = new HashSet<>();
            while (picks.size() < n)
                picks.add(pool[rnd.nextInt(pool.length)]);
            picks.add(rnd.nextInt(2) == 0 ? "trending" : "new-arrival");
            for (String tag : picks) {
                tagRepository.save(ProductTagEntity.builder().product(p).tag(tag).build());
                tCount++;
            }
        }
        log.info("Seeded {} attributes and {} tags", aCount, tCount);
    }

    /* ============================== shipping ============================== */

    /** Per supplier: serve a curated list of major countries, with 3-4 methods each. */
    private void seedShipping() {
        String[] countries = {"US", "ES", "MX", "BR", "GB", "DE", "FR", "IT", "CA", "AU", "JP", "KR", "SG", "AE", "CN",
                "HK", "AR", "CL", "CO", "PE", "PT", "NL", "PL", "SE", "NO"};

        List<SupplierEntity> suppliers = supplierRepository.findAll();
        int zCount = 0, rCount = 0;
        for (SupplierEntity s : suppliers) {
            for (String c : countries) {
                zoneRepository.save(ShippingZoneEntity.builder().supplier(s).countryCode(c).region(regionOf(c))
                        .active(true).build());
                zCount++;

                // 4 shipping methods per zone
                rateRepository.save(rate(s, c, "STANDARD", "CJPacket", 7, 14, 350, 800, 30000));
                rateRepository.save(rate(s, c, "EXPRESS", "DHL Express", 3, 6, 1200, 2500, 20000));
                rateRepository.save(rate(s, c, "AIR", "China Post Air", 5, 10, 700, 1500, 30000));
                rateRepository.save(rate(s, c, "SEA", "Sea LCL", 25, 45, 1500, 400, 200000));
                rCount += 4;
            }
        }
        log.info("Seeded {} shipping zones and {} shipping rates", zCount, rCount);
    }

    private ShippingRateEntity rate(SupplierEntity s, String country, String method, String carrier, int dMin, int dMax,
            int baseCents, int perKgCents, int maxGrams) {
        return ShippingRateEntity.builder().supplier(s).countryCode(country).method(method).carrier(carrier)
                .transitDaysMin(dMin).transitDaysMax(dMax).baseCents(baseCents).perKgCents(perKgCents)
                .maxWeightGrams(maxGrams).active(true).build();
    }

    private static String regionOf(String c) {
        return switch (c) {
            case "US", "CA", "MX" -> "NORTH_AMERICA";
            case "AR", "BR", "CL", "CO", "PE" -> "LATAM";
            case "GB", "DE", "FR", "IT", "ES", "PT", "NL", "PL", "SE", "NO" -> "EUROPE";
            case "CN", "HK", "JP", "KR", "SG" -> "APAC";
            case "AE" -> "MIDDLE_EAST";
            case "AU", "NZ" -> "OCEANIA";
            default -> "OTHER";
        };
    }

    /* ============================== product extras ============================== */

    /** Fill in the new product columns (lead time, warranty, certifications, package weight). */
    private void seedProductExtras() {
        List<ProductEntity> products = productRepository.findAll();
        int updated = 0;
        for (ProductEntity p : products) {
            if (p.getLeadTimeDays() != null && p.getCertifications() != null)
                continue;
            if (p.getLeadTimeDays() == null)
                p.setLeadTimeDays(2 + rnd.nextInt(8));
            if (p.getWarrantyMonths() == null)
                p.setWarrantyMonths(3 + rnd.nextInt(22));
            if (p.getReturnPolicyDays() == null)
                p.setReturnPolicyDays(rnd.nextInt(2) == 0 ? 14 : 30);
            if (p.getCountryOfOrigin() == null)
                p.setCountryOfOrigin("CN");
            if (p.getPackageWeightGrams() == null && p.getWeightGrams() != null) {
                p.setPackageWeightGrams(p.getWeightGrams() + 80 + rnd.nextInt(220));
            }
            if (p.getCertifications() == null || p.getCertifications().isEmpty()) {
                String slug = p.getCategory() != null ? p.getCategory().getSlug() : "general";
                p.setCertifications(switch (slug) {
                    case "consumer-electronics" -> List.of("CE", "FCC", "RoHS");
                    case "fashion-apparel" -> List.of("OEKO-TEX");
                    case "beauty-personal-care" -> List.of("ISO 22716", "GMP");
                    case "home-kitchen" -> List.of("FDA", "LFGB");
                    case "sports-outdoors" -> List.of("CE");
                    case "toys-gifts" -> List.of("EN71", "ASTM F963", "CPSIA");
                    default -> List.of();
                });
            }
            productRepository.save(p);
            updated++;
        }
        log.info("Updated {} products with extras (lead time, warranty, certifications)", updated);
    }

    /* ============================== category expansion (DROP-19) ============================== */

    /** Adds 8 root categories to bring the mega-menu from 6 to 14 cats. */
    private void seedExtraCategories() {
        // DROP-465: cada fila incluye una traducción PT real; antes copiábamos el texto ES
        // al slot "pt" y el editor de /admin/categories mostraba español en el campo Portugués.
        Object[][] extras = {
                {"auto-parts", "20007", "汽车配件", 7, "car", "Auto y motos", "Auto Parts & Motorcycle", "Auto e motos"},
                {"office-supplies", "20008", "办公用品", 8, "pen", "Oficina y papelería", "Office & Stationery",
                        "Escritório e papelaria"},
                {"pet-supplies", "20009", "宠物用品", 9, "paw", "Mascotas", "Pet Supplies", "Animais de estimação"},
                {"tools-hardware", "20010", "工具五金", 10, "wrench", "Herramientas", "Tools & Hardware", "Ferramentas"},
                {"jewelry-watches", "20011", "珠宝手表", 11, "gem", "Joyería y relojes", "Jewelry & Watches",
                        "Joias e relógios"},
                {"garden-outdoor", "20012", "园艺户外", 12, "leaf", "Jardín y exterior", "Garden & Outdoor",
                        "Jardim e exterior"},
                {"baby-maternity", "20013", "母婴用品", 13, "baby", "Bebés y maternidad", "Baby & Maternity",
                        "Bebês e maternidade"},
                {"lighting", "20014", "灯饰照明", 14, "lightbulb", "Iluminación", "Lighting", "Iluminação"},};
        int created = 0;
        for (Object[] e : extras) {
            String slug = (String) e[0];
            if (categoryRepository.findBySlug(slug).isPresent())
                continue;
            catalogService.upsertCategory(new com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest(
                    slug, null, "1688", (String) e[1], (String) e[2], (int) e[3], (String) e[4],
                    java.util.Map.of("es", (String) e[5], "en", (String) e[6], "pt", (String) e[7])));
            created++;
        }
        if (created > 0)
            log.info("Seeded {} extra root categories (total now {})", created, categoryRepository.count());
    }

    /* ============================== ship_from / free_shipping / has_video / inventory ============================== */

    private void seedProductFlagsAndVideos() {
        String[] shipFromPool = {"CN", "CN", "CN", "CN", "HK", "US", "ES", "MX"}; // bias to CN
        List<ProductEntity> products = productRepository.findAll();
        int updated = 0;
        for (ProductEntity p : products) {
            boolean changed = false;
            if (p.getShipFrom() == null) {
                p.setShipFrom(shipFromPool[rnd.nextInt(shipFromPool.length)]);
                changed = true;
            }
            if (p.getFreeShipping() == null) {
                p.setFreeShipping(rnd.nextInt(3) == 0);
                changed = true; // ~33% free shipping
            }
            if (p.getSelfPickup() == null) {
                p.setSelfPickup(rnd.nextInt(8) == 0);
                changed = true; // ~12% self pickup
            }
            if (p.getHasVideo() == null) {
                p.setHasVideo(rnd.nextInt(4) == 0);
                changed = true; // ~25% have video
            }
            if (Boolean.TRUE.equals(p.getHasVideo()) && p.getVideoUrl() == null) {
                p.setVideoUrl("https://cdn.nx036.local/v/" + p.getId() + ".mp4");
                changed = true;
            }
            if (p.getInventoryCount() == null) {
                p.setInventoryCount(50 + rnd.nextInt(9950));
                changed = true;
            }
            if (changed) {
                productRepository.save(p);
                updated++;
            }
        }
        if (updated > 0)
            log.info("Filled ship-from / free-shipping / video / inventory on {} products", updated);
    }

    /* ============================== price + stock history (DROP-25) ============================== */

    /** 90 days of price/stock snapshots per active product with small daily noise. */
    private void seedPriceHistory() {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate from = today.minusDays(90);
        List<ProductEntity> products = productRepository.findAll().stream()
                .filter(p -> p.getStatus() != null && "ACTIVE".equals(p.getStatus().name())).toList();
        int created = 0;
        for (ProductEntity p : products) {
            if (p.getBasePrice() == null)
                continue;
            int basePriceCents = p.getBasePrice().multiply(new java.math.BigDecimal(100)).intValue();
            int baseStock = p.getInventoryCount() != null ? p.getInventoryCount() : 500;
            for (java.time.LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
                // ±8% price noise and ±20% stock noise so the chart shows variation.
                double priceNoise = 1.0 + (rnd.nextDouble() - 0.5) * 0.16;
                double stockNoise = 1.0 + (rnd.nextDouble() - 0.5) * 0.40;
                historyRepository.save(ProductHistoryEntity.builder().product(p).snapshotDate(d)
                        .priceUsdCents((int) Math.round(basePriceCents * priceNoise))
                        .stock(Math.max(0, (int) Math.round(baseStock * stockNoise))).build());
                created++;
            }
        }
        log.info("Seeded {} product history snapshots across {} products", created, products.size());
    }

    /* ============================== DROP-2..13 platform seeds ============================== */

    private void seedAgents() {
        Object[][] agents = {
                {"Liang Chen", "STRATEGIC", "10 years sourcing electronics from Shenzhen", List.of("en", "zh", "es"),
                        98.2, 4.7, 4.9, 412},
                {"María Pérez", "SENIOR", "Fashion & beauty supplier discovery", List.of("es", "en"), 94.8, 7.2, 4.8,
                        268},
                {"Ravi Sharma", "SENIOR", "Tools, hardware and auto parts negotiator", List.of("en", "hi"), 92.1, 8.0,
                        4.7, 192},
                {"Sofía Almeida", "MID", "Bilingual coordinator for LATAM stores", List.of("es", "pt", "en"), 88.5, 9.5,
                        4.6, 134},
                {"Tom Wilson", "MID", "Toys, hobby and gifts category", List.of("en"), 87.0, 10.2, 4.5, 118},
                {"Yuki Tanaka", "JUNIOR", "Apparel and accessories ramp-up", List.of("en", "ja"), 82.0, 14.0, 4.3, 56},
                {"Lin Zhao", "JUNIOR", "POD and customization help", List.of("zh", "en"), 80.5, 15.2, 4.2, 38},
                {"Camille Dubois", "STRATEGIC", "Premium beauty + EU compliance", List.of("fr", "en"), 96.5, 5.1, 4.85,
                        318},};
        for (Object[] a : agents) {
            @SuppressWarnings("unchecked")
            List<String> langs = (List<String>) a[3];
            agentProfileRepo.save(com.nexaplatform.dropshipping.infrastructure.persistence.entity.AgentProfileEntity
                    .builder().displayName((String) a[0]).tier((String) a[1]).bio((String) a[2]).languages(langs)
                    .successRate(new java.math.BigDecimal(a[4].toString()))
                    .avgResponseHours(new java.math.BigDecimal(a[5].toString()))
                    .satisfaction(new java.math.BigDecimal(a[6].toString())).completedJobs((int) a[7])
                    .hourlyRateUsdCents(2500 + rnd.nextInt(7500)).active(true).build());
        }
        log.info("Seeded {} sourcing agents", agents.length);
    }

    private void seedCourses() {
        Object[][] courses = {
                {"intro-dropshipping", "Intro al Dropshipping en NX036", "Pilares del negocio y primeros 30 días.",
                        "Jesus Finol", 45, "es", "BEGINNER"},
                {"sourcing-china", "Sourcing efectivo en China", "Cómo evaluar fábricas y MOQs realistas.",
                        "Liang Chen", 60, "es", "INTERMEDIATE"},
                {"shopify-integration", "Integración Shopify paso a paso", "Conecta tu tienda y publica 50 productos.",
                        "María Pérez", 40, "es", "BEGINNER"},
                {"winning-products", "Hallar Winning Products", "Uso de Intelligence + Ad Trends.", "Tom Wilson", 35,
                        "es", "INTERMEDIATE"},
                {"shipping-fundamentals", "Logística internacional 101", "STANDARD vs EXPRESS vs AIR vs SEA.",
                        "Sofía Almeida", 30, "es", "BEGINNER"},
                {"pod-mastery", "Print On Demand desde cero", "Editor, mockups, IA y fulfillment POD.", "Lin Zhao", 50,
                        "es", "INTERMEDIATE"},
                {"branding-odm", "ODM y branding para tu marca", "Cuándo invertir en custom packaging.",
                        "Camille Dubois", 40, "es", "ADVANCED"},
                {"intro-en", "Intro to dropshipping on NX036", "Business pillars and your first 30 days.",
                        "Jesus Finol", 45, "en", "BEGINNER"},
                {"sourcing-en", "Effective sourcing from China", "Evaluate factories and realistic MOQs.", "Liang Chen",
                        60, "en", "INTERMEDIATE"},
                {"shopify-en", "Shopify integration step by step", "Connect your shop and publish 50 products.",
                        "María Pérez", 40, "en", "BEGINNER"},
                {"intro-pt", "Introdução ao Dropshipping", "Pilares do negócio e os primeiros 30 dias.", "Jesus Finol",
                        45, "pt", "BEGINNER"},
                {"intro-zh", "代发货入门", "业务基础与首个 30 天行动计划。", "Jesus Finol", 45, "zh", "BEGINNER"},};
        for (Object[] c : courses) {
            String slug = (String) c[0];
            courseRepo.save(com.nexaplatform.dropshipping.infrastructure.persistence.entity.AcademyCourseEntity
                    .builder().slug(slug).title((String) c[1]).description((String) c[2]).instructor((String) c[3])
                    .durationMinutes((int) c[4]).locale((String) c[5]).level((String) c[6])
                    .coverUrl(ACADEMY_COVERS[Math.floorMod(slug.hashCode(), ACADEMY_COVERS.length)])
                    .videoUrl(ACADEMY_VIDEOS[Math.floorMod(slug.hashCode(), ACADEMY_VIDEOS.length)]).published(true)
                    .build());
        }
        log.info("Seeded {} academy courses", courses.length);
    }

    // DROP-605: real, reachable covers + sample videos (the old cdn.nx036.local URLs were fictional).
    private static final String[] ACADEMY_COVERS = {
            "https://images.unsplash.com/photo-1497032205916-ac775f0649ae?w=800",
            "https://images.unsplash.com/photo-1456735190827-d1262f71b8a3?w=800",
            "https://images.unsplash.com/photo-1498049794561-7780e7231661?w=800",
            "https://images.unsplash.com/photo-1556821840-3a63f95609a7?w=800",
            "https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?w=800",
            "https://images.unsplash.com/photo-1503341960582-b45751874cf0?w=800" };
    private static final String[] ACADEMY_VIDEOS = {
            "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_1MB.mp4",
            "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4",
            "https://www.w3schools.com/html/mov_bbb.mp4" };

    private void seedMentors(List<UserEntity> demoPartners) {
        // Build mentor profiles on the first 6 demo partners.
        Object[][] headlines = {
                {"Veteran dropshipper · 50K+ orders/yr", List.of("strategy", "scaling", "unit-economics")},
                {"Shopify & Amazon multichannel expert", List.of("shopify", "amazon", "seo")},
                {"POD designer turned merchant", List.of("pod", "branding", "customization")},
                {"China sourcing operator (Yiwu/Shenzhen)", List.of("sourcing", "qa", "logistics")},
                {"TikTok Shop growth specialist", List.of("tiktok", "short-video", "ugc")},
                {"Wholesale & B2B negotiation", List.of("wholesale", "b2b", "contracts")},};
        int created = 0;
        for (int i = 0; i < Math.min(headlines.length, demoPartners.size()); i++) {
            UserEntity u = demoPartners.get(i);
            @SuppressWarnings("unchecked")
            List<String> expertise = (List<String>) headlines[i][1];
            mentorProfileRepo.save(com.nexaplatform.dropshipping.infrastructure.persistence.entity.MentorProfileEntity
                    .builder().user(u).headline((String) headlines[i][0]).expertise(expertise)
                    .languages(List.of("en", "es")).hourlyRateUsdCents(6000 + rnd.nextInt(9000))
                    .bio("Hands-on dropshipping mentor. Booking includes call recording and notes.")
                    .timezone("Europe/Madrid").active(true).build());
            created++;
        }
        log.info("Seeded {} mentor profiles", created);
    }

    private void seedWarehouses() {
        Object[][] whs = {{"CN-SHZ", "Shenzhen Hub", "CN", "Shenzhen"}, {"CN-YIW", "Yiwu Hub", "CN", "Yiwu"},
                {"US-LAX", "Los Angeles", "US", "Los Angeles"}, {"US-NYC", "New York", "US", "New York"},
                {"DE-FRA", "Frankfurt", "DE", "Frankfurt"}, {"GB-LON", "London", "GB", "London"},
                {"PL-WAW", "Warsaw", "PL", "Warsaw"}, {"JP-TYO", "Tokyo", "JP", "Tokyo"},
                {"ES-MAD", "Madrid", "ES", "Madrid"}, {"MY-KUL", "Kuala Lumpur", "MY", "Kuala Lumpur"},
                {"MX-MEX", "Mexico City", "MX", "Mexico City"}, {"CA-YYZ", "Toronto", "CA", "Toronto"},};
        List<com.nexaplatform.dropshipping.infrastructure.persistence.entity.WarehouseEntity> saved = new ArrayList<>();
        for (Object[] w : whs) {
            saved.add(warehouseRepo.save(com.nexaplatform.dropshipping.infrastructure.persistence.entity.WarehouseEntity
                    .builder().code((String) w[0]).name((String) w[1]).country((String) w[2]).city((String) w[3])
                    .active(true).build()));
        }
        // Seed per-warehouse stock for the first 30 products across 4 random warehouses.
        List<ProductEntity> prods = productRepository.findAll();
        int stockRows = 0;
        for (ProductEntity p : prods.subList(0, Math.min(30, prods.size()))) {
            java.util.Collections.shuffle(saved, rnd);
            for (int i = 0; i < 4 && i < saved.size(); i++) {
                pwsRepo.save(com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductWarehouseStockEntity
                        .builder().product(p).warehouse(saved.get(i)).stock(20 + rnd.nextInt(2000)).build());
                stockRows++;
            }
        }
        log.info("Seeded {} warehouses and {} stock rows", whs.length, stockRows);
    }

    private void seedAdTrends() {
        String[] sources = {"tiktok", "facebook", "instagram", "pinterest", "youtube", "amazon"};
        String[] hooks = {"Vendo {} y ya facturo USD/día", "El producto que rompe en {} esta semana",
                "Cómo escalar {} con $50/día de ads", "{} viral en TikTok — checa el ratio CTR",
                "Top {} para Q4 con margen 60%+",};
        List<ProductEntity> prods = productRepository.findAll().stream()
                .filter(p -> "ACTIVE".equals(p.getStatus() == null ? null : p.getStatus().name())).toList();
        int created = 0;
        for (int i = 0; i < 40 && i < prods.size() * 2; i++) {
            ProductEntity p = prods.get(i % prods.size());
            String src = sources[rnd.nextInt(sources.length)];
            // DROP-642: use the translated (ES → EN) product title in the headline; never the
            // Chinese title_zh, which would surface CJK text to the user in the Ad Trends tab.
            String hook = hooks[rnd.nextInt(hooks.length)].replace("{}", displayTitleFor(p));
            adTrendRepo.save(com.nexaplatform.dropshipping.infrastructure.persistence.entity.AdTrendEntity.builder()
                    .source(src).headline(hook).productSlug(p.getSlug())
                    .impressions(10000L + (long) rnd.nextInt(900000)).engagement(500L + (long) rnd.nextInt(40000))
                    .score(new java.math.BigDecimal(
                            String.format(java.util.Locale.US, "%.3f", 0.5 + rnd.nextDouble() * 0.5)))
                    .region(new String[]{"US", "ES", "BR", "MX", "GB"}[rnd.nextInt(5)])
                    .capturedAt(java.time.Instant.now().minus(rnd.nextInt(14), java.time.temporal.ChronoUnit.DAYS))
                    .build());
            created++;
        }
        log.info("Seeded {} ad trends", created);
    }

    /**
     * DROP-642: resolves a latin, user-facing product title for headlines/snapshots.
     * Prefers the Spanish translation, then English, and only falls back to the raw
     * Chinese {@code title_zh} when no latin translation exists.
     */
    private String displayTitleFor(ProductEntity p) {
        if (p.getTranslations() != null) {
            for (String lang : new String[]{"es", "en"}) {
                String t = p.getTranslations().stream()
                        .filter(tr -> lang.equals(tr.getLanguage()))
                        .map(com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity::getTitle)
                        .filter(s -> s != null && !s.isBlank())
                        .findFirst().orElse(null);
                if (t != null)
                    return t;
            }
        }
        return p.getTitleZh();
    }

    private void seedNotifications(List<UserEntity> demoCustomers) {
        String[][] templates = {{"order.shipped", "Tu pedido fue enviado", "Sigue tu envío en /orders."},
                {"order.delivered", "¡Tu pedido llegó!", "Cuéntanos qué tal con un review."},
                {"sourcing.quoted", "Recibiste 3 cotizaciones", "Compara y elige tu agente."},
                {"intel.alert", "Tendencia detectada", "Hay un winning product en tu categoría."},
                {"academy.new", "Curso nuevo disponible", "Acaba de salir el módulo de Shopify."},};
        int created = 0;
        for (UserEntity u : demoCustomers.stream().limit(25).toList()) {
            for (int i = 0; i < 3; i++) {
                String[] t = templates[rnd.nextInt(templates.length)];
                notificationRepo.save(com.nexaplatform.dropshipping.infrastructure.persistence.entity.NotificationEntity
                        .builder().user(u).eventType(t[0]).title(t[1]).body(t[2]).channel("IN_APP").build());
                created++;
            }
        }
        log.info("Seeded {} notifications", created);
    }

    /** DROP-168 / DROP-228 — give demo customers a connected storefront so the Shop column is populated. */
    private void seedShopConnections(List<UserEntity> demoCustomers) {
        String[] platforms = {"SHOPIFY", "WOOCOMMERCE", "TIKTOK_SHOP", "AMAZON"};
        int created = 0;
        for (UserEntity u : demoCustomers) {
            String platform = platforms[rnd.nextInt(platforms.length)];
            String handle = (u.getDisplayName() != null
                    ? u.getDisplayName().toLowerCase().replaceAll("[^a-z0-9]+", "-")
                    : "shop-" + Integer.toHexString(u.hashCode())) + "-" + platform.toLowerCase().split("_")[0];
            shopConnectionRepo.save(com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopConnectionEntity
                    .builder().user(u).platform(platform).shopHandle(handle).status("CONNECTED")
                    .lastSyncAt(java.time.Instant.now().minusSeconds(rnd.nextInt(86400))).build());
            created++;
        }
        log.info("Seeded {} shop connections", created);
    }

    /** DROP-13 flags: mark ~30% as Brand Selected and ~25% as Ready-To-Ship. */
    private void seedProductBrandsAndReadyToShip() {
        int u = 0;
        for (ProductEntity p : productRepository.findAll()) {
            boolean changed = false;
            if (p.getBrandSelected() == null) {
                p.setBrandSelected(rnd.nextInt(3) == 0);
                changed = true;
            }
            if (p.getReadyToShip() == null) {
                p.setReadyToShip(rnd.nextInt(4) == 0);
                changed = true;
            }
            if (p.getPodEnabled() == null) {
                p.setPodEnabled(rnd.nextInt(5) == 0);
                changed = true;
            }
            if (changed) {
                productRepository.save(p);
                u++;
            }
        }
        if (u > 0)
            log.info("Flagged brand_selected/ready_to_ship/pod_enabled on {} products", u);
    }

    /* ============================== tiny helpers ============================== */

    private PaymentMethod pickMethod() {
        int r = rnd.nextInt(10);
        if (r < 6)
            return PaymentMethod.CARD;
        if (r < 9)
            return PaymentMethod.PAYPAL;
        return PaymentMethod.USDT;
    }

    private String providerFor(PaymentMethod m) {
        return switch (m) {
            case CARD -> "stripe";
            case PAYPAL -> "paypal";
            case USDT -> "manual";
        };
    }

    private static String slugify(String s) {
        return s.toLowerCase().replaceAll("[áàä]", "a").replaceAll("[éèë]", "e").replaceAll("[íìï]", "i")
                .replaceAll("[óòö]", "o").replaceAll("[úùü]", "u").replaceAll("ñ", "n").replaceAll("[^a-z0-9]", "");
    }

    private static String languageForCountry(String country) {
        if (country == null)
            return "en";
        return switch (country) {
            case "ES", "MX", "AR", "CO", "CL", "PE", "VE", "UY", "EC", "PY", "BO" -> "es";
            case "BR", "PT" -> "pt";
            case "CN", "HK", "TW", "SG" -> "zh";
            default -> "en";
        };
    }

    private static String[] citiesFor(String country) {
        if (country == null)
            return new String[]{"Madrid"};
        return switch (country) {
            case "ES" -> new String[]{"Madrid", "Barcelona", "Valencia", "Sevilla", "Málaga"};
            case "MX" -> new String[]{"Ciudad de México", "Guadalajara", "Monterrey", "Puebla", "Cancún"};
            case "AR" -> new String[]{"Buenos Aires", "Córdoba", "Rosario", "Mendoza"};
            case "CO" -> new String[]{"Bogotá", "Medellín", "Cali", "Cartagena"};
            case "CL" -> new String[]{"Santiago", "Valparaíso", "Concepción"};
            case "PE" -> new String[]{"Lima", "Arequipa", "Trujillo", "Cusco"};
            case "BR" -> new String[]{"São Paulo", "Rio de Janeiro", "Brasília", "Salvador", "Porto Alegre"};
            case "PT" -> new String[]{"Lisboa", "Porto", "Coimbra"};
            case "US" -> new String[]{"New York", "Los Angeles", "Chicago", "Houston", "Miami"};
            case "GB" -> new String[]{"London", "Manchester", "Edinburgh", "Bristol"};
            case "CA" -> new String[]{"Toronto", "Vancouver", "Montreal"};
            case "AU" -> new String[]{"Sydney", "Melbourne", "Brisbane", "Perth"};
            case "NZ" -> new String[]{"Auckland", "Wellington"};
            case "IE" -> new String[]{"Dublin", "Cork", "Galway"};
            case "DE" -> new String[]{"Berlin", "Hamburg", "München", "Köln"};
            case "FR" -> new String[]{"Paris", "Lyon", "Marseille", "Toulouse"};
            case "IT" -> new String[]{"Roma", "Milano", "Napoli", "Torino"};
            case "PL" -> new String[]{"Warszawa", "Kraków", "Wrocław"};
            case "SE" -> new String[]{"Stockholm", "Göteborg", "Malmö"};
            case "NO" -> new String[]{"Oslo", "Bergen", "Trondheim"};
            case "CN" -> new String[]{"上海", "北京", "深圳", "广州", "杭州"};
            case "HK" -> new String[]{"Hong Kong"};
            case "SG" -> new String[]{"Singapore"};
            case "JP" -> new String[]{"東京", "大阪", "京都"};
            case "KR" -> new String[]{"서울", "부산", "인천"};
            case "IN" -> new String[]{"Mumbai", "Delhi", "Bengaluru", "Chennai"};
            case "AE" -> new String[]{"Dubai", "Abu Dhabi"};
            default -> new String[]{"Capital"};
        };
    }

    private String stateFor(String country, String city) {
        return switch (country == null ? "" : country) {
            case "ES" -> switch (city) {
                case "Madrid" -> "Madrid";
                case "Barcelona" -> "Cataluña";
                case "Valencia" -> "Comunidad Valenciana";
                case "Sevilla", "Málaga" -> "Andalucía";
                default -> null;
            };
            case "US" -> "NY";
            case "MX" -> city.equals("Ciudad de México") ? "CDMX" : "MX";
            case "BR" -> city.startsWith("São") ? "SP" : "RJ";
            default -> null;
        };
    }

    private String streetFor(String country) {
        String[] streets = switch (country == null ? "" : country) {
            case "ES" -> new String[]{"Calle Mayor", "Av. Diagonal", "Calle Princesa", "Paseo de Gracia"};
            case "US" -> new String[]{"Main St", "Broadway", "5th Ave", "Park Ave"};
            case "GB" -> new String[]{"King's Road", "Oxford St", "Baker Street"};
            case "MX" -> new String[]{"Av. Insurgentes", "Av. Reforma", "Calle Madero"};
            case "BR" -> new String[]{"Av. Paulista", "Rua Augusta", "Av. Brasil"};
            case "CN" -> new String[]{"南京路", "长安街", "黄浦江畔"};
            case "JP" -> new String[]{"渋谷区", "新宿区"};
            case "FR" -> new String[]{"Rue de Rivoli", "Avenue des Champs-Élysées"};
            case "DE" -> new String[]{"Friedrichstraße", "Hauptstraße"};
            default -> new String[]{"Av. Central"};
        };
        return streets[rnd.nextInt(streets.length)];
    }

    private String postalFor(String country) {
        if (country == null)
            return "00000";
        return switch (country) {
            case "ES" -> "28" + String.format("%03d", rnd.nextInt(1000));
            case "US" -> String.format("%05d", 10000 + rnd.nextInt(80000));
            case "GB" -> "SW" + (1 + rnd.nextInt(20)) + " " + (1 + rnd.nextInt(9)) + (char) ('A' + rnd.nextInt(26));
            case "MX" -> String.format("%05d", 1000 + rnd.nextInt(90000));
            case "BR" -> String.format("%05d-%03d", rnd.nextInt(100000), rnd.nextInt(1000));
            case "CN" -> String.format("%06d", 100000 + rnd.nextInt(800000));
            case "JP" -> String.format("%03d-%04d", rnd.nextInt(1000), rnd.nextInt(10000));
            case "FR", "DE", "IT" -> String.format("%05d", 10000 + rnd.nextInt(80000));
            default -> String.format("%05d", rnd.nextInt(100000));
        };
    }
}
