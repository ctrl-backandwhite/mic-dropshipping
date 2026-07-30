package com.nexaplatform.dropshipping.infrastructure.seed;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.application.usecase.WalletUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.*;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
@ConditionalOnProperty(prefix = "nexadrop.demo-seed", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class DemoOperationsSeedRunner {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String PARTNERS_NX036_LOCAL = "@partners.nx036.local";
    private static final String CONSUMER_ELECTRONICS = "consumer-electronics";
    private static final String BEAUTY_PERSONAL_CARE = "beauty-personal-care";
    private static final String DEMO_NX036_LOCAL = "@demo.nx036.local";
    private static final String FASHION_APPAREL = "fashion-apparel";
    private static final String SPORTS_OUTDOORS = "sports-outdoors";
    private static final String INTERMEDIATE = "INTERMEDIATE";
    private static final String HOME_KITCHEN = "home-kitchen";
    private static final String LOS_ANGELES = "Los Angeles";
    private static final String MAR_A_P_REZ = "María Pérez";
    private static final String JESUS_FINOL = "Jesus Finol";
    private static final String WOOCOMMERCE = "WooCommerce";
    private static final String TOYS_GIFTS = "toys-gifts";
    private static final String LIANG_CHEN = "Liang Chen";
    private static final String BEGINNER = "BEGINNER";
    private static final String NEW_YORK = "New York";
    private static final String SHOPIFY = "Shopify";
    private static final String GENERAL = "general";
    private static final String TORONTO = "Toronto";
    private static final String LONDON = "London";
    private static final String MADRID = "Madrid";

    /**
     * Contraseña de las cuentas de demostración.
     *
     * <p>Estaba escrita en el código ("Demo!Customer2026" y compañía), así que cualquiera con acceso al
     * repositorio conocía las credenciales de las cuentas que crea el seed —incluida una de OPERATOR—. El
     * seed viene desactivado, pero basta con encenderlo en un entorno accesible para tener usuarios con
     * contraseña pública.
     *
     * <p>Ahora se toma de {@code nexadrop.demo-seed.password}. Si no se define, se genera una aleatoria
     * por arranque y se registra en el log: las cuentas quedan creadas pero sin credencial adivinable, y
     * quien levante la demo puede leerla del arranque.
     */
    private String demoPassword;

    @Value("${nexadrop.demo-seed.password:}")
    private String configuredPassword;

    /** Resuelve la contraseña una sola vez por arranque, para que todas las cuentas compartan la misma. */
    private String demoPassword() {
        if (demoPassword == null) {
            if (configuredPassword != null && !configuredPassword.isBlank()) {
                demoPassword = configuredPassword;
            } else {
                demoPassword = "Demo!" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                log.warn("::> [SEED] Sin nexadrop.demo-seed.password: las cuentas de demo usan la contraseña "
                        + "generada '{}' (solo este arranque)", demoPassword);
            }
        }
        return demoPassword;
    }

    private final UserRepository userRepository;
    private final UserAddressRepository addressRepository;
    private final AddressRepository orderAddressRepository;
    private final ProductSpecificationRepository specRepository;
    private final ProductAttributeRepository attrRepository;
    private final ProductTagRepository tagRepository;
    private final ShippingZoneRepository zoneRepository;
    private final ShippingRateRepository rateRepository;
    private final ProductHistoryRepository historyRepository;
    private final CatalogUseCase catalogService;
    private final AgentProfileRepository agentProfileRepo;
    private final AcademyCourseRepository courseRepo;
    private final MentorProfileRepository mentorProfileRepo;
    private final WarehouseRepository warehouseRepo;
    private final AdTrendRepository adTrendRepo;
    private final NotificationRepository notificationRepo;
    private final ProductWarehouseStockRepository pwsRepo;
    private final ShopConnectionRepository shopConnectionRepo;
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

    /**
     * Generador con semilla FIJA a propósito: los datos de demostración deben salir iguales en cada
     * arranque para que las capturas y las pruebas manuales sean reproducibles. No interviene en nada
     * criptográfico —la contraseña de las cuentas usa UUID.randomUUID(), que sí es seguro—.
     */
    private final Random rnd = new Random(424242L); // NOSONAR java:S2245 — datos de demo, no seguridad

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
                .filter(u -> u.getEmail() != null && u.getEmail().contains(DEMO_NX036_LOCAL)).toList();
        List<UserEntity> demoPartners = partners.stream()
                .filter(u -> u.getEmail() != null && u.getEmail().contains(PARTNERS_NX036_LOCAL)).toList();

        seedCommerce(demoCustomers, demoPartners);
        seedCatalogExtras();
        seedPlatform(demoCustomers, demoPartners);

        log.info("NX036 demo ops seed finished — users={} orders={} payments={} subs={} priceRules={}",
                userRepository.count(), orderRepository.count(), paymentRepository.count(),
                subscriptionRepository.count(), priceRuleRepository.count());
    }

    /**
     * Direcciones, pedidos y suscripciones. Cada bloque solo entra si la tabla está por debajo del mínimo
     * que hace presentable la demo: así el seed es idempotente por secciones y volver a arrancar no
     * duplica datos.
     *
     * <p>NO se siembra saldo de wallet (recargas/depósitos de prueba). La wallet arranca en 0 y solo se
     * acredita con recargas reales (Stripe/PayPal) o reembolsos. Ver decisión 2026-07-01.
     *
     * <p>Las reglas de margen tampoco se siembran: las define el operador (decisión 2026-06-13).
     */
    private void seedCommerce(List<UserEntity> demoCustomers, List<UserEntity> demoPartners) {
        if (addressRepository.count() < 50)
            seedAddressesFor(demoCustomers, demoPartners);
        if (orderRepository.count() < 30)
            seedOrders(demoCustomers, 60);
        if (subscriptionRepository.count() < 5)
            seedSubscriptions(demoPartners);
    }

    /** Todo lo que cuelga del catálogo: fichas, envío, banderas comerciales e histórico de precios. */
    private void seedCatalogExtras() {
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
        seedProductBrandsAndReadyToShip();
    }

    /** Módulos de plataforma: agentes, academia, mentores, almacenes, tendencias, avisos y tiendas. */
    private void seedPlatform(List<UserEntity> demoCustomers, List<UserEntity> demoPartners) {
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
    }

    private static int countDemo(List<UserEntity> us) {
        return (int) us.stream().filter(u -> u.getEmail() != null && u.getEmail().contains(DEMO_NX036_LOCAL)
                || u.getEmail() != null && u.getEmail().contains(PARTNERS_NX036_LOCAL)).count();
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
            String email = (slug + DEMO_NX036_LOCAL).toLowerCase();
            if (userRepository.existsByEmail(email))
                continue;
            String language = languageForCountry(p[2]);
            UserEntity u = UserEntity.builder().email(email).passwordHash(passwordEncoder.encode(demoPassword()))
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
        String[][] shops = {{"trendypicks-mx", "TrendyPicks MX", "MX", SHOPIFY},
                {"madrid-essentials", "Madrid Essentials", "ES", SHOPIFY},
                {"bcn-stylebox", "Barcelona StyleBox", "ES", WOOCOMMERCE},
                {"saopaulo-deals", "São Paulo Deals", "BR", WOOCOMMERCE},
                {"buenosaires-mart", "BA Mart", "AR", SHOPIFY}, {"london-trends", "London Trends", "GB", SHOPIFY},
                {"berlin-uptown", "Berlin Uptown", "DE", WOOCOMMERCE}, {"paris-pop", "Paris Pop", "FR", SHOPIFY},
                {"toronto-vault", "Toronto Vault", "CA", SHOPIFY},
                {"miami-imports", "Miami Imports", "US", "BigCommerce"},
                {"lisbon-loop", "Lisbon Loop", "PT", WOOCOMMERCE}, {"tokyo-curated", "Tokyo Curated", "JP", "Custom"},
                {"seoul-aesthetic", "Seoul Aesthetic", "KR", "Cafe24"},
                {"sydney-supply", "Sydney Supply", "AU", SHOPIFY}, {"dubai-prime", "Dubai Prime", "AE", SHOPIFY},};
        List<UserEntity> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, shops.length); i++) {
            String[] s = shops[i];
            String email = s[0] + PARTNERS_NX036_LOCAL;
            if (userRepository.existsByEmail(email))
                continue;
            UserEntity u = UserEntity.builder().email(email).passwordHash(passwordEncoder.encode(demoPassword()))
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
                    .passwordHash(passwordEncoder.encode(demoPassword())).role(UserRole.OPERATOR).active(true)
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
                    .phone("+" + (10 + rnd.nextInt(89)) + " " + (100000000L + Math.floorMod(rnd.nextLong(), 900000000L)))
                    .line1(streetFor(u.getCountry()) + " " + (1 + rnd.nextInt(450)))
                    .line2(rnd.nextInt(3) == 0 ? "Piso " + (1 + rnd.nextInt(8)) + (char) ('A' + rnd.nextInt(5)) : null)
                    .city(city).state(stateFor(u.getCountry(), city)).postalCode(postalFor(u.getCountry()))
                    .country(u.getCountry() != null ? u.getCountry() : "US").isDefault(i == 0).build();
            addressRepository.save(a);
            created++;
        }
        return created;
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

            int subtotal = addOrderItems(o, products);
            int shipping = 0; // free shipping in demo
            int tax = (int) Math.round(subtotal * 0.0);
            o.setSubtotalCents(subtotal);
            o.setShippingCents(shipping);
            o.setTaxCents(tax);
            o.setTotalCents(subtotal + shipping + tax);

            // Apply status + matching timestamps
            applyStatusTimeline(o, status);
            CustomerOrderEntity saved = orderRepository.save(o);
            // NOTA: no se cobra la wallet en el seed (no hay saldo sembrado). Los pedidos demo se generan
            // con su estado/timeline; la wallet real solo se mueve con recargas/pagos reales.
            created.add(saved);
        }
        log.info("Seeded {} orders", created.size());
        return created;
    }

    /** Añade 1-6 líneas al pedido — más variedad para que los totales se distribuyan — y da el subtotal. */
    private int addOrderItems(CustomerOrderEntity o, List<ProductEntity> products) {
        int itemCount = 1 + rnd.nextInt(6);
        int subtotal = 0;
        for (int j = 0; j < itemCount; j++) {
            ProductEntity prod = products.get(rnd.nextInt(products.size()));
            int qty = randomQuantity();
            int unitCents = randomUnitCents(prod);
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
        return subtotal;
    }

    /** Cantidad con cola: 70% de 1-3, 20% de 4-10 y 10% de 11-50, para que se vean totales grandes. */
    private int randomQuantity() {
        int bucket = rnd.nextInt(100);
        if (bucket < 70) {
            return 1 + rnd.nextInt(3);
        }
        return bucket < 90 ? 4 + rnd.nextInt(7) : 11 + rnd.nextInt(40);
    }

    /** Precio unitario con variación de ±30% sobre la base; si la base es irrisoria, se inventa una. */
    private int randomUnitCents(ProductEntity prod) {
        int baseUnit = (int) Math.round(prod.getBasePrice().doubleValue() * 100);
        if (baseUnit < 100) {
            baseUnit = 200 + rnd.nextInt(15000); // 2..152 USD
        }
        double variation = 0.7 + rnd.nextDouble() * 0.6; // 0.7..1.3
        return Math.max(100, (int) Math.round(baseUnit * variation));
    }

    private void applyStatusTimeline(CustomerOrderEntity o, OrderStatus target) {
        Instant placed = o.getPlacedAt();
        o.setStatus(target);
        switch (target) {
            case FORWARDED -> o.setForwardedAt(placed.plus(1L + rnd.nextInt(2), ChronoUnit.HOURS));
            case SHIPPED -> {
                o.setForwardedAt(placed.plus(1L + rnd.nextInt(2), ChronoUnit.HOURS));
                o.setShippedAt(placed.plus(1L + rnd.nextInt(3), ChronoUnit.DAYS));
            }
            case DELIVERED -> {
                o.setForwardedAt(placed.plus(1L + rnd.nextInt(2), ChronoUnit.HOURS));
                o.setShippedAt(placed.plus(1L + rnd.nextInt(3), ChronoUnit.DAYS));
                o.setDeliveredAt(placed.plus(5L + rnd.nextInt(10), ChronoUnit.DAYS));
            }
            case CANCELLED -> o.setCancelledAt(placed.plus(2L + rnd.nextInt(24), ChronoUnit.HOURS));
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
            subscriptionRepository.save(buildSubscription(p, assignedPlan, statuses[i % statuses.length], i));
            created++;
        }
        log.info("Seeded {} subscriptions", created);
    }

    private CustomerSubscriptionEntity buildSubscription(UserEntity partner, SubscriptionPlanEntity plan,
            SubscriptionStatus status, int index) {
        boolean isFreePlan = plan.getPriceMonthlyCents() == 0 && plan.getPriceYearlyCents() == 0;
        // DROP-634: un plan gratuito (FREE) no puede estar en periodo de prueba — un plan sin coste ya es
        // "activo" desde el primer momento. Forzamos ACTIVE cuando el plan asignado es gratuito.
        SubscriptionStatus st = isFreePlan && status == SubscriptionStatus.TRIALING ? SubscriptionStatus.ACTIVE
                : status;
        Instant now = Instant.now();
        return CustomerSubscriptionEntity.builder().user(partner).plan(plan)
                // DROP-634: usamos el formato canónico MONTHLY/YEARLY (igual que el use case real) para
                // que el frontend lo traduzca siempre vía i18n.
                .status(st).billingPeriod(index % 2 == 0 ? "MONTHLY" : "YEARLY")
                .currentPeriodStart(now.minus(15L + rnd.nextInt(30), ChronoUnit.DAYS))
                .currentPeriodEnd(now.plus(15L + rnd.nextInt(30), ChronoUnit.DAYS))
                .canceledAt(st == SubscriptionStatus.CANCELED ? now.minus(5, ChronoUnit.DAYS) : null)
                .trialEndsAt(st == SubscriptionStatus.TRIALING ? now.plus(7, ChronoUnit.DAYS) : null).build();
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

    /**
     * Textos de las especificaciones de demostración. El seed solo escribe las fichas en español e inglés,
     * así que cada constante lleva sus dos versiones: nombre del campo, unidades y los pocos valores que
     * también van traducidos.
     */
    private enum SpecText {
        BRAND("Marca", "Brand"),
        MATERIAL("Material", "Material"),
        MODEL("Modelo", "Model"),
        ORIGIN("País de origen", "Country of origin"),
        NET_WEIGHT("Peso neto", "Net weight"),
        WARRANTY("Garantía", "Warranty"),
        LEAD_TIME("Lead time del proveedor", "Supplier lead time"),
        VOLTAGE("Voltaje", "Voltage"),
        CERTIFICATIONS("Certificaciones", "Certifications"),
        CONNECTIVITY("Conectividad", "Connectivity"),
        COMPOSITION("Composición", "Composition"),
        CARE("Cuidado", "Care"),
        SKIN_TYPE("Tipo de piel", "Skin type"),
        SHELF_LIFE("Caducidad", "Shelf life"),
        DISHWASHER_SAFE("Apto para lavavajillas", "Dishwasher safe"),
        MONTHS_UNIT(" meses", " months"),
        DAYS_UNIT(" días", " days"),
        CARE_VALUE("Lavar a 30°C, no plancha directa", "Machine wash 30°C"),
        ALL_SKIN_TYPES("Todo tipo", "All types"),
        YES("Sí", "Yes");

        private final String es;
        private final String en;

        SpecText(String es, String en) {
            this.es = es;
            this.en = en;
        }

        String of(boolean spanish) {
            return spanish ? es : en;
        }
    }

    private List<String[]> specsFor(ProductEntity p, String lang) {
        boolean es = "es".equals(lang);
        String category = p.getCategory() != null ? p.getCategory().getSlug() : GENERAL;
        List<String[]> out = new ArrayList<>(commonSpecs(p, category, es));
        out.addAll(categorySpecs(category, es));
        return out;
    }

    /** Las 7 especificaciones que lleva cualquier producto, tenga la categoría que tenga. */
    private List<String[]> commonSpecs(ProductEntity p, String category, boolean es) {
        List<String[]> out = new ArrayList<>();
        out.add(spec(SpecText.BRAND, es, p.getBrand() != null ? p.getBrand() : "NX036 Generic"));
        out.add(spec(SpecText.MATERIAL, es, materialFor(category)));
        out.add(spec(SpecText.MODEL, es, p.getExternalId()));
        out.add(spec(SpecText.ORIGIN, es, "CN"));
        int grams = p.getWeightGrams() != null ? p.getWeightGrams() : 250 + rnd.nextInt(800);
        out.add(spec(SpecText.NET_WEIGHT, es, grams + " g"));
        out.add(spec(SpecText.WARRANTY, es, (3 + rnd.nextInt(22)) + SpecText.MONTHS_UNIT.of(es)));
        out.add(spec(SpecText.LEAD_TIME, es, (2 + rnd.nextInt(8)) + SpecText.DAYS_UNIT.of(es)));
        return out;
    }

    /** Especificaciones propias de la categoría; las que no están contempladas no añaden ninguna. */
    private static List<String[]> categorySpecs(String category, boolean es) {
        if (CONSUMER_ELECTRONICS.equals(category)) {
            return List.of(spec(SpecText.VOLTAGE, es, "100-240V AC, 50/60Hz"),
                    spec(SpecText.CERTIFICATIONS, es, "CE, FCC, RoHS"),
                    spec(SpecText.CONNECTIVITY, es, "Bluetooth 5.3 · USB-C"));
        }
        if (FASHION_APPAREL.equals(category)) {
            return List.of(spec(SpecText.COMPOSITION, es, "Cotton 80% / Polyester 20%"),
                    spec(SpecText.CARE, es, SpecText.CARE_VALUE.of(es)));
        }
        if (BEAUTY_PERSONAL_CARE.equals(category)) {
            return List.of(spec(SpecText.SKIN_TYPE, es, SpecText.ALL_SKIN_TYPES.of(es)),
                    spec(SpecText.SHELF_LIFE, es, "24" + SpecText.MONTHS_UNIT.of(es)));
        }
        if (HOME_KITCHEN.equals(category)) {
            // El tipo va explícito: con UN solo elemento, List.of(String[]) se interpreta como varargs y
            // devolvería List<String> en vez de List<String[]>.
            return List.<String[]>of(spec(SpecText.DISHWASHER_SAFE, es, SpecText.YES.of(es)));
        }
        return List.of();
    }

    /** Par clave/valor tal y como lo consume {@code seedSpecifications}. */
    private static String[] spec(SpecText label, boolean es, String value) {
        return new String[]{label.of(es), value};
    }

    private String materialFor(String slug) {
        return switch (slug) {
            case CONSUMER_ELECTRONICS -> "ABS + Aluminum";
            case FASHION_APPAREL -> "Cotton blend";
            case HOME_KITCHEN -> "Stainless steel 304";
            case BEAUTY_PERSONAL_CARE -> "Silicone + ABS";
            case SPORTS_OUTDOORS -> "Polyester ripstop";
            case TOYS_GIFTS -> "Food-grade ABS";
            default -> "Mixed";
        };
    }

    /* ============================== attributes + tags ============================== */

    private void seedAttributesAndTags() {
        String[][] catTagPool = {{CONSUMER_ELECTRONICS, "wireless,bluetooth,tech,gadget,charger,smart"},
                {FASHION_APPAREL, "unisex,casual,streetwear,trending,summer,winter"},
                {HOME_KITCHEN, "kitchen,storage,eco,decor,organizer,minimalist"},
                {BEAUTY_PERSONAL_CARE, "skincare,beauty,cruelty-free,vegan,korean,kbeauty"},
                {SPORTS_OUTDOORS, "fitness,outdoor,camping,gym,hiking,yoga"},
                {TOYS_GIFTS, "kids,educational,gift,creative,steam,age3+"},};
        Map<String, String[]> tagsByCat = new HashMap<>();
        for (String[] e : catTagPool)
            tagsByCat.put(e[0], e[1].split(","));

        String[] ageGroups = {"adult", "teen", "kids", "all_ages"};
        String[] seasons = {"all_season", "summer", "winter", "spring"};

        List<ProductEntity> products = productRepository.findAll();
        int aCount = 0;
        int tCount = 0;
        for (ProductEntity p : products) {
            String slug = p.getCategory() != null ? p.getCategory().getSlug() : GENERAL;
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
        int zCount = 0;
        int rCount = 0;
        for (SupplierEntity s : suppliers) {
            for (String c : countries) {
                zoneRepository.save(ShippingZoneEntity.builder().supplier(s).countryCode(c).region(regionOf(c))
                        .active(true).build());
                zCount++;

                for (RateSpec spec : DEMO_RATES) {
                    rateRepository.save(rate(s, c, spec));
                }
                rCount += DEMO_RATES.size();
            }
        }
        log.info("Seeded {} shipping zones and {} shipping rates", zCount, rCount);
    }

    /** Tarifa de demostración: transporte, tránsito en días, coste base y por kilo, y peso máximo. */
    private record RateSpec(String method, String carrier, int transitDaysMin, int transitDaysMax, int baseCents,
            int perKgCents, int maxWeightGrams) {
    }

    /** Los cuatro métodos de envío que se siembran en cada zona. */
    private static final List<RateSpec> DEMO_RATES = List.of(
            new RateSpec("STANDARD", "CJPacket", 7, 14, 350, 800, 30000),
            new RateSpec("EXPRESS", "DHL Express", 3, 6, 1200, 2500, 20000),
            new RateSpec("AIR", "China Post Air", 5, 10, 700, 1500, 30000),
            new RateSpec("SEA", "Sea LCL", 25, 45, 1500, 400, 200000));

    private ShippingRateEntity rate(SupplierEntity s, String country, RateSpec spec) {
        return ShippingRateEntity.builder().supplier(s).countryCode(country).method(spec.method())
                .carrier(spec.carrier()).transitDaysMin(spec.transitDaysMin()).transitDaysMax(spec.transitDaysMax())
                .baseCents(spec.baseCents()).perKgCents(spec.perKgCents()).maxWeightGrams(spec.maxWeightGrams())
                .active(true).build();
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
            // Con plazo y certificaciones ya puestos no queda nada que rellenar en esta ficha.
            if (p.getLeadTimeDays() != null && p.getCertifications() != null)
                continue;
            fillProductExtras(p);
            productRepository.save(p);
            updated++;
        }
        log.info("Updated {} products with extras (lead time, warranty, certifications)", updated);
    }

    /** Rellena SOLO las columnas vacías: nunca pisa un dato que ya trae el producto. */
    private void fillProductExtras(ProductEntity p) {
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
            p.setCertifications(certificationsFor(p.getCategory() != null ? p.getCategory().getSlug() : GENERAL));
        }
    }

    private static List<String> certificationsFor(String categorySlug) {
        return switch (categorySlug) {
            case CONSUMER_ELECTRONICS -> List.of("CE", "FCC", "RoHS");
            case FASHION_APPAREL -> List.of("OEKO-TEX");
            case BEAUTY_PERSONAL_CARE -> List.of("ISO 22716", "GMP");
            case HOME_KITCHEN -> List.of("FDA", "LFGB");
            case SPORTS_OUTDOORS -> List.of("CE");
            case TOYS_GIFTS -> List.of("EN71", "ASTM F963", "CPSIA");
            default -> List.of();
        };
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
            catalogService.upsertCategory(new CatalogDtos.IngestCategoryRequest(
                    slug, null, "1688", (String) e[1], (String) e[2], (int) e[3], (String) e[4],
                    Map.of("es", (String) e[5], "en", (String) e[6], "pt", (String) e[7])));
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
            if (fillFlagsAndVideo(p, shipFromPool)) {
                productRepository.save(p);
                updated++;
            }
        }
        if (updated > 0)
            log.info("Filled ship-from / free-shipping / video / inventory on {} products", updated);
    }

    /** Rellena las banderas comerciales que falten; devuelve {@code true} si tocó alguna. */
    private boolean fillFlagsAndVideo(ProductEntity p, String[] shipFromPool) {
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
        // Va después del bloque anterior a propósito: el vídeo se inventa también para los que acaban de
        // marcarse con vídeo en esta misma pasada.
        if (Boolean.TRUE.equals(p.getHasVideo()) && p.getVideoUrl() == null) {
            p.setVideoUrl("https://cdn.nx036.local/v/" + p.getId() + ".mp4");
            changed = true;
        }
        if (p.getInventoryCount() == null) {
            p.setInventoryCount(50 + rnd.nextInt(9950));
            changed = true;
        }
        return changed;
    }

    /* ============================== price + stock history (DROP-25) ============================== */

    /** 90 days of price/stock snapshots per active product with small daily noise. */
    private void seedPriceHistory() {
        // Fechas en UTC: las instantáneas son la serie que pinta la ficha del producto y no deben moverse
        // un día arriba o abajo según la zona horaria de la máquina que arranca la demo.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(90);
        List<ProductEntity> products = productRepository.findAll().stream()
                .filter(p -> p.getStatus() != null && "ACTIVE".equals(p.getStatus().name())).toList();
        int created = 0;
        for (ProductEntity p : products) {
            if (p.getBasePrice() == null)
                continue;
            int basePriceCents = p.getBasePrice().multiply(new BigDecimal(100)).intValue();
            int baseStock = p.getInventoryCount() != null ? p.getInventoryCount() : 500;
            for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
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
                {LIANG_CHEN, "STRATEGIC", "10 years sourcing electronics from Shenzhen", List.of("en", "zh", "es"),
                        98.2, 4.7, 4.9, 412},
                {MAR_A_P_REZ, "SENIOR", "Fashion & beauty supplier discovery", List.of("es", "en"), 94.8, 7.2, 4.8,
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
            agentProfileRepo.save(AgentProfileEntity
                    .builder().displayName((String) a[0]).tier((String) a[1]).bio((String) a[2]).languages(langs)
                    .successRate(new BigDecimal(a[4].toString()))
                    .avgResponseHours(new BigDecimal(a[5].toString()))
                    .satisfaction(new BigDecimal(a[6].toString())).completedJobs((int) a[7])
                    .hourlyRateUsdCents(2500 + rnd.nextInt(7500)).active(true).build());
        }
        log.info("Seeded {} sourcing agents", agents.length);
    }

    private void seedCourses() {
        Object[][] courses = {
                {"intro-dropshipping", "Intro al Dropshipping en NX036", "Pilares del negocio y primeros 30 días.",
                        JESUS_FINOL, 45, "es", BEGINNER},
                {"sourcing-china", "Sourcing efectivo en China", "Cómo evaluar fábricas y MOQs realistas.",
                        LIANG_CHEN, 60, "es", INTERMEDIATE},
                {"shopify-integration", "Integración Shopify paso a paso", "Conecta tu tienda y publica 50 productos.",
                        MAR_A_P_REZ, 40, "es", BEGINNER},
                {"winning-products", "Hallar Winning Products", "Uso de Intelligence + Ad Trends.", "Tom Wilson", 35,
                        "es", INTERMEDIATE},
                {"shipping-fundamentals", "Logística internacional 101", "STANDARD vs EXPRESS vs AIR vs SEA.",
                        "Sofía Almeida", 30, "es", BEGINNER},
                {"pod-mastery", "Print On Demand desde cero", "Editor, mockups, IA y fulfillment POD.", "Lin Zhao", 50,
                        "es", INTERMEDIATE},
                {"branding-odm", "ODM y branding para tu marca", "Cuándo invertir en custom packaging.",
                        "Camille Dubois", 40, "es", "ADVANCED"},
                {"intro-en", "Intro to dropshipping on NX036", "Business pillars and your first 30 days.",
                        JESUS_FINOL, 45, "en", BEGINNER},
                {"sourcing-en", "Effective sourcing from China", "Evaluate factories and realistic MOQs.", LIANG_CHEN,
                        60, "en", INTERMEDIATE},
                {"shopify-en", "Shopify integration step by step", "Connect your shop and publish 50 products.",
                        MAR_A_P_REZ, 40, "en", BEGINNER},
                {"intro-pt", "Introdução ao Dropshipping", "Pilares do negócio e os primeiros 30 dias.", JESUS_FINOL,
                        45, "pt", BEGINNER},
                {"intro-zh", "代发货入门", "业务基础与首个 30 天行动计划。", JESUS_FINOL, 45, "zh", BEGINNER},};
        for (Object[] c : courses) {
            String slug = (String) c[0];
            courseRepo.save(AcademyCourseEntity
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
            mentorProfileRepo.save(MentorProfileEntity
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
                {"US-LAX", LOS_ANGELES, "US", LOS_ANGELES}, {"US-NYC", NEW_YORK, "US", NEW_YORK},
                {"DE-FRA", "Frankfurt", "DE", "Frankfurt"}, {"GB-LON", LONDON, "GB", LONDON},
                {"PL-WAW", "Warsaw", "PL", "Warsaw"}, {"JP-TYO", "Tokyo", "JP", "Tokyo"},
                {"ES-MAD", MADRID, "ES", MADRID}, {"MY-KUL", "Kuala Lumpur", "MY", "Kuala Lumpur"},
                {"MX-MEX", "Mexico City", "MX", "Mexico City"}, {"CA-YYZ", TORONTO, "CA", TORONTO},};
        List<WarehouseEntity> saved = new ArrayList<>();
        for (Object[] w : whs) {
            saved.add(warehouseRepo.save(WarehouseEntity
                    .builder().code((String) w[0]).name((String) w[1]).country((String) w[2]).city((String) w[3])
                    .active(true).build()));
        }
        // Seed per-warehouse stock for the first 30 products across 4 random warehouses.
        List<ProductEntity> prods = productRepository.findAll();
        int stockRows = 0;
        for (ProductEntity p : prods.subList(0, Math.min(30, prods.size()))) {
            Collections.shuffle(saved, rnd);
            for (int i = 0; i < 4 && i < saved.size(); i++) {
                pwsRepo.save(ProductWarehouseStockEntity
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
            adTrendRepo.save(AdTrendEntity.builder()
                    .source(src).headline(hook).productSlug(p.getSlug())
                    .impressions(10000L + rnd.nextInt(900000)).engagement(500L + rnd.nextInt(40000))
                    .score(new BigDecimal(
                            String.format(Locale.US, "%.3f", 0.5 + rnd.nextDouble() * 0.5)))
                    .region(new String[]{"US", "ES", "BR", "MX", "GB"}[rnd.nextInt(5)])
                    .capturedAt(Instant.now().minus(rnd.nextInt(14), ChronoUnit.DAYS))
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
                        .map(ProductTranslationEntity::getTitle)
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
                notificationRepo.save(NotificationEntity
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
            shopConnectionRepo.save(ShopConnectionEntity
                    .builder().user(u).platform(platform).shopHandle(handle).status("CONNECTED")
                    .lastSyncAt(Instant.now().minusSeconds(rnd.nextInt(86400))).build());
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

    /**
     * Nombre a slug: se descompone el texto (NFD) y se tiran las marcas diacríticas, de modo que la tilde
     * cae sea cual sea la letra y venga precompuesta o suelta. La lista de literales que había antes solo
     * cubría las vocales del castellano y se comía por completo las que no estaban (ã, ç, ê…).
     */
    private static String slugify(String s) {
        String decomposed = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}++", "").replaceAll("[^a-z0-9]++", "");
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
            return new String[]{MADRID};
        return switch (country) {
            case "ES" -> new String[]{MADRID, "Barcelona", "Valencia", "Sevilla", "Málaga"};
            case "MX" -> new String[]{"Ciudad de México", "Guadalajara", "Monterrey", "Puebla", "Cancún"};
            case "AR" -> new String[]{"Buenos Aires", "Córdoba", "Rosario", "Mendoza"};
            case "CO" -> new String[]{"Bogotá", "Medellín", "Cali", "Cartagena"};
            case "CL" -> new String[]{"Santiago", "Valparaíso", "Concepción"};
            case "PE" -> new String[]{"Lima", "Arequipa", "Trujillo", "Cusco"};
            case "BR" -> new String[]{"São Paulo", "Rio de Janeiro", "Brasília", "Salvador", "Porto Alegre"};
            case "PT" -> new String[]{"Lisboa", "Porto", "Coimbra"};
            case "US" -> new String[]{NEW_YORK, LOS_ANGELES, "Chicago", "Houston", "Miami"};
            case "GB" -> new String[]{LONDON, "Manchester", "Edinburgh", "Bristol"};
            case "CA" -> new String[]{TORONTO, "Vancouver", "Montreal"};
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
                case MADRID -> MADRID;
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
