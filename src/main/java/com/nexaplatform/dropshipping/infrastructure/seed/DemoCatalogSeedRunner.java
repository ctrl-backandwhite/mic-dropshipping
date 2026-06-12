package com.nexaplatform.dropshipping.infrastructure.seed;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestImage;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestPriceTier;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariant;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantOption;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantValue;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Seeds the catalog with ~50 realistic 1688-style products distributed across the 6 top
 * categories on first start (when the catalog is empty). The data mirrors what the live
 * crawler microservice persists, so the platform is fully operational and evaluable without
 * paid scraping provider credentials. Disable with {@code nexadrop.demo-seed.enabled=false}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "nexadrop.demo-seed", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DemoCatalogSeedRunner {

    private final CatalogUseCase catalogService;
    private final ProductRepository productRepository;
    private final UserUseCase userUseCase;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        seedDemoUsers();

        if (productRepository.count() > 0) {
            log.info("Demo seed skipped — catalog already contains {} products", productRepository.count());
            return;
        }
        log.info("NX036 demo seed running — populating 1688-style catalog");

        Map<String, UUID> suppliers = seedSuppliers();
        Map<String, UUID> categories = seedCategories();
        seedProducts(suppliers, categories);

        log.info("NX036 demo seed finished — {} products active", productRepository.count());
    }

    /* ============================== users ============================== */

    private void seedDemoUsers() {
        ensureUser("partner@nx036.local", "Nx036Partner!2026", UserRole.PARTNER, "NX036 Partner");
        ensureUser("operator@nx036.local", "Nx036Operator!2026", UserRole.OPERATOR, "NX036 Operator");
        ensureUser("customer@nx036.local", "Nx036Customer!2026", UserRole.USER, "NX036 Customer");
    }

    private void ensureUser(String email, String password, UserRole role, String displayName) {
        if (userRepository.existsByEmail(email))
            return;
        try {
            userUseCase.createAdminUser(User.builder().email(email).displayName(displayName).build(), password,
                    role.name());
            log.info("Demo user created: {} ({})", email, role);
        } catch (Exception e) {
            log.warn("Could not create demo user {} via service: {} — falling back to direct save", email,
                    e.getMessage());
            UserEntity u = UserEntity.builder().email(email).passwordHash(passwordEncoder.encode(password)).role(role)
                    .active(true).displayName(displayName).language("es").build();
            userRepository.save(u);
        }
    }

    /* ============================== suppliers ============================== */

    private Map<String, UUID> seedSuppliers() {
        Map<String, UUID> m = new HashMap<>();
        m.put("yiwu", supplier("yiwu-bright", "Yiwu Bright Trading Co.", "义乌博昕贸易有限公司", "Yiwu", "4.85", 7, true, true));
        m.put("shenzhen",
                supplier("shenzhen-technova", "Shenzhen TechNova Co.", "深圳泰科诺瓦科技", "Shenzhen", "4.92", 9, true, true));
        m.put("guangzhou",
                supplier("gz-fashion-hub", "Guangzhou Fashion Hub", "广州时尚枢纽", "Guangzhou", "4.71", 6, true, false));
        m.put("ningbo", supplier("ningbo-homeplus", "Ningbo HomePlus Co.", "宁波家加", "Ningbo", "4.66", 5, true, true));
        m.put("hangzhou",
                supplier("hangzhou-beauty", "Hangzhou Beauty Lab", "杭州美研所", "Hangzhou", "4.78", 4, true, true));
        m.put("dongguan",
                supplier("dongguan-craft", "Dongguan Craft Industries", "东莞工艺", "Dongguan", "4.69", 8, true, true));
        m.put("foshan", supplier("foshan-living", "Foshan Modern Living", "佛山现代生活", "Foshan", "4.74", 6, true, true));
        m.put("shanghai",
                supplier("shanghai-prime", "Shanghai Prime Supplies", "上海至佳", "Shanghai", "4.81", 5, true, true));
        return m;
    }

    private UUID supplier(String externalId, String name, String nameZh, String city, String rating, int years,
            boolean verified, boolean trustPass) {
        return catalogService
                .upsertSupplier(new IngestSupplierRequest("1688", externalId, name, nameZh, "CN", city,
                        new BigDecimal(rating), years, verified, trustPass, "https://" + externalId + ".1688.com"))
                .getId();
    }

    /* ============================== categories ============================== */

    private Map<String, UUID> seedCategories() {
        Map<String, UUID> m = new HashMap<>();
        m.put("electronics", cat("consumer-electronics", "10001", "数码电子", 1, "microchip", "Electrónica de consumo",
                "Consumer Electronics", "Eletrônica de consumo"));
        m.put("fashion", cat("fashion-apparel", "10002", "时尚服饰", 2, "shirt", "Moda y vestir", "Fashion & Apparel",
                "Moda e vestuário"));
        m.put("home",
                cat("home-kitchen", "10003", "家居厨房", 3, "house", "Hogar y cocina", "Home & Kitchen", "Casa e cozinha"));
        m.put("beauty", cat("beauty-personal-care", "10004", "美妆个护", 4, "spa", "Belleza y cuidado personal",
                "Beauty & Personal Care", "Beleza e cuidado pessoal"));
        m.put("sports", cat("sports-outdoors", "10005", "运动户外", 5, "dumbbell", "Deportes y aire libre",
                "Sports & Outdoors", "Esportes e ar livre"));
        m.put("toys", cat("toys-gifts", "10006", "玩具礼品", 6, "gift", "Juguetes y regalos", "Toys & Gifts",
                "Brinquedos e presentes"));
        return m;
    }

    // DROP-465: el seed antes copiaba el texto ES al campo PT como atajo, lo que generaba
    // categorías cuyo "Portugués" mostraba español al editarlas en /admin/categories.
    // Ahora exigimos PT explícito en cada registro para evitar esa confusión.
    private UUID cat(String slug, String externalId, String nameZh, int position, String icon, String es, String en,
            String pt) {
        return catalogService.upsertCategory(new IngestCategoryRequest(slug, null, "1688", externalId, nameZh, position,
                icon, Map.of("es", es, "en", en, "pt", pt))).getId();
    }

    /* ============================== products ============================== */

    private final AtomicInteger sku = new AtomicInteger(1000);

    private void seedProducts(Map<String, UUID> sup, Map<String, UUID> cat) {

        // ========== ELECTRONICS (10) ==========
        add(sup.get("shenzhen"), cat.get("electronics"), "Auriculares Bluetooth TWS X-Pro ANC", "TWS蓝牙耳机 X-Pro 主动降噪",
                "ANC activo, IPX5, autonomía 30h, USB-C, llamadas duales.",
                "Active noise cancelling, IPX5, dual-call, 30h battery, USB-C.", "69.90", 1, 4820, "4.7", 1240, "28.5",
                imgs("https://images.unsplash.com/photo-1606220945770-b5b6c2c55bf1?w=800",
                        "https://images.unsplash.com/photo-1583394838336-acd977736f90?w=800",
                        "https://images.unsplash.com/photo-1590658268037-6bf12165a8df?w=800"),
                opts(opt("颜色", val("黑色"), val("白色"), val("蓝色"))),
                vars(v("XPRO-BLK", "Negro mate", "65.00", 540, m("颜色", "黑色")),
                        v("XPRO-WHT", "Blanco perla", "65.00", 320, m("颜色", "白色")),
                        v("XPRO-BLU", "Azul cobalto", "67.00", 210, m("颜色", "蓝色"))),
                tiers(t(1, 49, "65.00"), t(50, 199, "59.50"), t(200, null, "54.00")));

        add(sup.get("shenzhen"), cat.get("electronics"), "Cargador GaN 65W triple puerto PD3.0", "65W GaN氮化镓充电器 三口快充",
                "GaN 65W, USB-C×2 + USB-A, PD3.0 y QC4+.", "Triple-port GaN 65W with PD3.0 + QC4+.", "21.50", 1, 3210,
                "4.8", 980, "32.1",
                imgs("https://images.unsplash.com/photo-1583863788434-e58a36330cf0?w=800",
                        "https://images.unsplash.com/photo-1585338107529-13afc5f02586?w=800"),
                opts(opt("插头", val("欧规"), val("美规"), val("英规"))),
                vars(v("GAN65-EU", "EU", "19.00", 740, m("插头", "欧规")), v("GAN65-US", "US", "19.00", 510, m("插头", "美规")),
                        v("GAN65-UK", "UK", "20.00", 180, m("插头", "英规"))),
                tiers(t(1, 99, "21.50"), t(100, 499, "18.40"), t(500, null, "16.20")));

        add(sup.get("shenzhen"), cat.get("electronics"), "Smartwatch Series 9 AMOLED 1.83\" SpO2",
                "运动智能手表 Series 9 心率血氧", "Monitor cardíaco 24/7, SpO2, sueño, +120 modos deportivos, AMOLED 1.83\".",
                "24/7 HR, SpO2, sleep, 120+ workouts, 1.83\" AMOLED.", "38.00", 1, 5610, "4.6", 2010, "25.4",
                imgs("https://images.unsplash.com/photo-1523275335684-37898b6baf30?w=800",
                        "https://images.unsplash.com/photo-1546868871-7041f2a55e12?w=800",
                        "https://images.unsplash.com/photo-1579586337278-3befd40fd17a?w=800"),
                opts(opt("表带颜色", val("石墨黑"), val("沙漠粉"), val("北极蓝"))),
                vars(v("SW9-BLK", "Graphite", "35.00", 820, m("表带颜色", "石墨黑")),
                        v("SW9-PNK", "Desert Pink", "35.00", 440, m("表带颜色", "沙漠粉")),
                        v("SW9-BLU", "Arctic Blue", "36.00", 260, m("表带颜色", "北极蓝"))),
                tiers(t(1, 49, "38.00"), t(50, 199, "33.50"), t(200, null, "29.90")));

        add(sup.get("shenzhen"), cat.get("electronics"), "Powerbank 20000mAh PD20W carga rápida", "充电宝 20000毫安 PD20W快充",
                "Batería externa 20.000mAh PD 20W, USB-C bidireccional.",
                "20,000mAh power bank, 20W PD, bidirectional USB-C.", "15.80", 1, 6420, "4.7", 2310, "26.7",
                imgs("https://images.unsplash.com/photo-1606298855672-3efb63017be8?w=800",
                        "https://images.unsplash.com/photo-1608043152269-423dbba4e7e1?w=800"),
                opts(opt("颜色", val("黑色"), val("白色"))),
                vars(v("PB20K-BLK", "Negro", "15.80", 950, m("颜色", "黑色")),
                        v("PB20K-WHT", "Blanco", "15.80", 720, m("颜色", "白色"))),
                tiers(t(1, 49, "15.80"), t(50, 199, "13.20"), t(200, null, "11.40")));

        add(sup.get("shenzhen"), cat.get("electronics"), "Cámara IP WiFi 1080P seguimiento auto", "WIFI摄像头 1080P自动追踪",
                "Cámara WiFi 1080P con auto-seguimiento, visión nocturna y audio bidireccional.",
                "1080P WiFi camera with auto-tracking, night vision and 2-way audio.", "12.40", 1, 3870, "4.5", 1180,
                "19.8",
                imgs("https://images.unsplash.com/photo-1558002038-1055907df827?w=800",
                        "https://images.unsplash.com/photo-1542156822-6924d1a71ace?w=800"),
                opts(opt("套餐", val("仅相机"), val("含32G卡"), val("含64G卡"))),
                vars(v("IPCAM-NO", "Camera only", "12.40", 410, m("套餐", "仅相机")),
                        v("IPCAM-32G", "+32GB", "16.20", 290, m("套餐", "含32G卡")),
                        v("IPCAM-64G", "+64GB", "19.50", 180, m("套餐", "含64G卡"))),
                tiers(t(1, 49, "12.40"), t(50, 199, "10.50"), t(200, null, "8.90")));

        add(sup.get("shenzhen"), cat.get("electronics"), "Mini proyector portátil 1080P 4K-support WiFi",
                "迷你投影仪 4K 1080P原生分辨率", "Proyector 1080P nativo, soporte 4K, WiFi + Bluetooth.",
                "Native 1080P, 4K support, built-in WiFi + Bluetooth.", "59.00", 1, 2150, "4.4", 620, "16.9",
                imgs("https://images.unsplash.com/photo-1485846234645-a62644f84728?w=800"), opts(),
                vars(v("MINIPROJ", "1080P nativo", "59.00", 280, m())),
                tiers(t(1, 19, "59.00"), t(20, 99, "52.40"), t(100, null, "47.20")));

        add(sup.get("shenzhen"), cat.get("electronics"), "Teclado mecánico RGB 87 teclas hot-swap", "机械键盘 87键 RGB热插拔",
                "RGB 87 teclas con hot-swap, dual USB-C/Bluetooth.", "RGB 87-key hot-swap, dual USB-C/Bluetooth.",
                "28.50", 1, 2870, "4.7", 940, "29.5",
                imgs("https://images.unsplash.com/photo-1587829741301-dc798b83add3?w=800",
                        "https://images.unsplash.com/photo-1561112078-7d24e04c3407?w=800"),
                opts(opt("轴体", val("红轴"), val("青轴"), val("茶轴"))),
                vars(v("KBM-RED", "Red", "28.50", 410, m("轴体", "红轴")),
                        v("KBM-BLUE", "Blue", "28.50", 380, m("轴体", "青轴")),
                        v("KBM-BRN", "Brown", "29.00", 290, m("轴体", "茶轴"))),
                tiers(t(1, 49, "28.50"), t(50, 199, "24.30"), t(200, null, "21.40")));

        add(sup.get("shenzhen"), cat.get("electronics"), "Drone plegable 4K GPS retorno automático",
                "可折叠无人机 4K GPS自动返航", "Drone plegable 4K, GPS, retorno automático, 25 min de vuelo.",
                "Foldable 4K drone, GPS, auto-return, 25 min flight.", "94.00", 1, 1240, "4.5", 380, "14.7",
                imgs("https://images.unsplash.com/photo-1473968512647-3e447244af8f?w=800"),
                opts(opt("套装", val("标配"), val("双电池"))),
                vars(v("DRN-STD", "Standard", "94.00", 180, m("套装", "标配")),
                        v("DRN-DUAL", "Dual battery", "112.00", 120, m("套装", "双电池"))),
                tiers(t(1, 9, "94.00"), t(10, 49, "84.00"), t(50, null, "76.00")));

        add(sup.get("shenzhen"), cat.get("electronics"), "Cable USB-C 100W trenzado 2m PD", "100W编织数据线 2米 PD快充",
                "Cable trenzado 2m, PD 100W, 480Mbps.", "2m braided cable, 100W PD, 480Mbps.", "3.80", 5, 8920, "4.8",
                3140, "37.2", imgs("https://images.unsplash.com/photo-1583394838336-acd977736f90?w=800"),
                opts(opt("颜色", val("黑色"), val("白色"), val("灰色"))),
                vars(v("CBL-BLK", "Negro", "3.80", 1450, m("颜色", "黑色")),
                        v("CBL-WHT", "Blanco", "3.80", 1280, m("颜色", "白色")),
                        v("CBL-GRY", "Gris", "3.80", 890, m("颜色", "灰色"))),
                tiers(t(5, 99, "3.80"), t(100, 499, "3.10"), t(500, null, "2.40")));

        add(sup.get("shenzhen"), cat.get("electronics"), "Soporte magnético MagSafe escritorio aluminio",
                "磁吸手机支架 MagSafe桌面", "Soporte de aluminio con imán MagSafe, ángulo y altura ajustables.",
                "Aluminum MagSafe stand with adjustable angle and height.", "8.20", 1, 3640, "4.6", 1240, "23.8",
                imgs("https://images.unsplash.com/photo-1585399000684-d2f72660f092?w=800"),
                opts(opt("颜色", val("银色"), val("深空灰"))),
                vars(v("MGSF-SLV", "Silver", "8.20", 470, m("颜色", "银色")),
                        v("MGSF-GRY", "Space Gray", "8.20", 420, m("颜色", "深空灰"))),
                tiers(t(1, 49, "8.20"), t(50, 199, "6.80"), t(200, null, "5.40")));

        // ========== FASHION (10) ==========
        add(sup.get("guangzhou"), cat.get("fashion"), "Sudadera oversize unisex con capucha 320g", "宽松卫衣男女款 加绒抽绳连帽",
                "Sudadera unisex 320g, interior afelpado, capucha ajustable.",
                "320 gsm unisex hoodie, brushed interior.", "12.40", 1, 7240, "4.5", 1810, "18.9",
                imgs("https://images.unsplash.com/photo-1556821840-3a63f95609a7?w=800",
                        "https://images.unsplash.com/photo-1620799140408-edc6dcb6d633?w=800"),
                opts(opt("尺码", val("S"), val("M"), val("L"), val("XL")), opt("颜色", val("黑色"), val("奶白"), val("军绿"))),
                vars(v("HOOD-S-BLK", "S · Black", "12.40", 320, m("尺码", "S", "颜色", "黑色")),
                        v("HOOD-M-BLK", "M · Black", "12.40", 510, m("尺码", "M", "颜色", "黑色")),
                        v("HOOD-L-BLK", "L · Black", "12.40", 420, m("尺码", "L", "颜色", "黑色")),
                        v("HOOD-M-CRM", "M · Cream", "12.40", 280, m("尺码", "M", "颜色", "奶白")),
                        v("HOOD-L-GRN", "L · Olive", "12.40", 190, m("尺码", "L", "颜色", "军绿"))),
                tiers(t(1, 99, "12.40"), t(100, 499, "10.50"), t(500, null, "8.90")));

        add(sup.get("guangzhou"), cat.get("fashion"), "Jeans skinny tiro alto strech mujer", "高腰修身牛仔裤 弹力九分",
                "Jean skinny tiro alto 11oz, costura reforzada.",
                "High-rise 11oz stretch skinny jeans, reinforced stitching.", "9.80", 1, 4520, "4.4", 990, "16.2",
                imgs("https://images.unsplash.com/photo-1542272604-787c3835535d?w=800",
                        "https://images.unsplash.com/photo-1582418702059-97ebafb35d09?w=800"),
                opts(opt("尺码", val("26"), val("27"), val("28"), val("29"), val("30"))),
                vars(v("JN-26", "W26", "9.80", 200, m("尺码", "26")), v("JN-28", "W28", "9.80", 350, m("尺码", "28")),
                        v("JN-30", "W30", "9.80", 280, m("尺码", "30"))),
                tiers(t(1, 49, "9.80"), t(50, 199, "8.20"), t(200, null, "6.90")));

        add(sup.get("guangzhou"), cat.get("fashion"), "Vestido midi floral verano manga corta", "夏季短袖碎花连衣裙 中长",
                "Vestido midi floral, tejido fresco, mangas farol.",
                "Floral midi dress, breathable fabric, short puff sleeves.", "11.20", 1, 3870, "4.6", 1280, "21.4",
                imgs("https://images.unsplash.com/photo-1572804013309-59a88b7e92f1?w=800"),
                opts(opt("尺码", val("S"), val("M"), val("L")), opt("花色", val("白底蓝花"), val("黑底红花"))),
                vars(v("DR-S-WB", "S · White/Blue", "11.20", 260, m("尺码", "S", "花色", "白底蓝花")),
                        v("DR-M-WB", "M · White/Blue", "11.20", 320, m("尺码", "M", "花色", "白底蓝花")),
                        v("DR-L-BR", "L · Black/Red", "11.20", 210, m("尺码", "L", "花色", "黑底红花"))),
                tiers(t(1, 49, "11.20"), t(50, 199, "9.40"), t(200, null, "7.80")));

        add(sup.get("guangzhou"), cat.get("fashion"), "Cazadora bomber acolchada unisex forro polar", "男女款棒球服 绗缝夹克",
                "Bomber acolchada unisex, forro polar y puños elásticos.", "Unisex padded bomber with fleece lining.",
                "18.50", 1, 2340, "4.5", 680, "17.8",
                imgs("https://images.unsplash.com/photo-1591047139829-d91aecb6caea?w=800"),
                opts(opt("尺码", val("M"), val("L"), val("XL")), opt("颜色", val("驼色"), val("墨绿"))),
                vars(v("BMB-M-TAN", "M · Tan", "18.50", 180, m("尺码", "M", "颜色", "驼色")),
                        v("BMB-L-TAN", "L · Tan", "18.50", 220, m("尺码", "L", "颜色", "驼色")),
                        v("BMB-XL-OLV", "XL · Olive", "18.50", 140, m("尺码", "XL", "颜色", "墨绿"))),
                tiers(t(1, 49, "18.50"), t(50, 199, "15.70"), t(200, null, "13.40")));

        add(sup.get("guangzhou"), cat.get("fashion"), "Camisa blanca slim fit hombre manga larga", "男士长袖白衬衫 修身",
                "Camisa formal slim fit, popelín 60s, easy-iron.", "Slim-fit formal shirt, 60s poplin, easy-iron.",
                "7.90", 1, 5810, "4.6", 1620, "20.5",
                imgs("https://images.unsplash.com/photo-1602810318383-e386cc2a3ccf?w=800"),
                opts(opt("尺码", val("39"), val("40"), val("41"), val("42"))),
                vars(v("SH-40", "Size 40", "7.90", 410, m("尺码", "40")),
                        v("SH-41", "Size 41", "7.90", 380, m("尺码", "41")),
                        v("SH-42", "Size 42", "7.90", 270, m("尺码", "42"))),
                tiers(t(1, 99, "7.90"), t(100, 499, "6.50"), t(500, null, "5.30")));

        add(sup.get("guangzhou"), cat.get("fashion"), "Calcetines algodón orgánico 5 pares unisex", "5双装有机棉袜子 男女款",
                "Pack 5 pares de calcetines algodón orgánico, talón reforzado.",
                "5-pair organic cotton socks with reinforced heel.", "3.20", 5, 9520, "4.7", 2810, "33.2",
                imgs("https://images.unsplash.com/photo-1582418702059-97ebafb35d09?w=800"),
                opts(opt("尺码", val("均码"), val("加大码"))),
                vars(v("SOC-OS", "One size", "3.20", 1240, m("尺码", "均码")),
                        v("SOC-PL", "Plus", "3.20", 410, m("尺码", "加大码"))),
                tiers(t(5, 99, "3.20"), t(100, 499, "2.60"), t(500, null, "2.10")));

        add(sup.get("guangzhou"), cat.get("fashion"), "Bolso tote lona aceitada asas de cuero 15L", "复古油蜡帆布托特包 手工制",
                "Tote en lona aceitada con asas de cuero, 15L, cierre magnético.",
                "Waxed canvas tote with leather handles, 15L, magnetic snap.", "14.90", 1, 2840, "4.7", 920, "25.6",
                imgs("https://images.unsplash.com/photo-1591561954557-26941169b49e?w=800"),
                opts(opt("颜色", val("驼色"), val("墨绿"), val("炭黑"))),
                vars(v("TOTE-TAN", "Tan", "14.90", 380, m("颜色", "驼色")),
                        v("TOTE-OLV", "Olive", "14.90", 290, m("颜色", "墨绿")),
                        v("TOTE-CHR", "Charcoal", "14.90", 210, m("颜色", "炭黑"))),
                tiers(t(1, 49, "14.90"), t(50, 199, "12.60"), t(200, null, "10.80")));

        add(sup.get("guangzhou"), cat.get("fashion"), "Gorra béisbol algodón ajustable bordado", "刺绣棒球帽 可调节",
                "Gorra béisbol algodón bordado, cierre metálico.", "Embroidered cotton baseball cap with metal clasp.",
                "4.20", 1, 6780, "4.5", 1840, "22.1",
                imgs("https://images.unsplash.com/photo-1521369909029-2afed882baee?w=800"),
                opts(opt("颜色", val("黑色"), val("米色"), val("海军蓝"))),
                vars(v("CAP-BLK", "Black", "4.20", 920, m("颜色", "黑色")),
                        v("CAP-BEI", "Beige", "4.20", 580, m("颜色", "米色")),
                        v("CAP-NVY", "Navy", "4.20", 410, m("颜色", "海军蓝"))),
                tiers(t(1, 99, "4.20"), t(100, 499, "3.40"), t(500, null, "2.80")));

        add(sup.get("guangzhou"), cat.get("fashion"), "Zapatillas malla transpirable EVA unisex", "透气网面运动鞋 男女款",
                "Zapatillas con malla transpirable, suela EVA ligera.",
                "Breathable mesh sneakers with lightweight EVA sole.", "16.40", 1, 5230, "4.6", 1570, "19.7",
                imgs("https://images.unsplash.com/photo-1542291026-7eec264c27ff?w=800"),
                opts(opt("尺码", val("39"), val("40"), val("41"), val("42"), val("43")), opt("颜色", val("白色"), val("黑色"))),
                vars(v("SNK-40-WHT", "40 · White", "16.40", 320, m("尺码", "40", "颜色", "白色")),
                        v("SNK-42-WHT", "42 · White", "16.40", 280, m("尺码", "42", "颜色", "白色")),
                        v("SNK-41-BLK", "41 · Black", "16.40", 240, m("尺码", "41", "颜色", "黑色"))),
                tiers(t(1, 49, "16.40"), t(50, 199, "13.80"), t(200, null, "11.60")));

        add(sup.get("guangzhou"), cat.get("fashion"), "Cinturón cuero genuino hebilla automática", "真皮自动扣腰带 男士",
                "Cinturón cuero genuino con hebilla automática, 110cm ajustable.",
                "Genuine leather belt, ratcheting buckle, adjustable to 110cm.", "6.80", 1, 3420, "4.5", 980, "21.3",
                imgs("https://images.unsplash.com/photo-1604644401890-0bd678c83788?w=800"),
                opts(opt("颜色", val("黑色"), val("棕色"))),
                vars(v("BLT-BLK", "Negro", "6.80", 540, m("颜色", "黑色")),
                        v("BLT-BRN", "Marrón", "6.80", 380, m("颜色", "棕色"))),
                tiers(t(1, 99, "6.80"), t(100, 499, "5.40"), t(500, null, "4.20")));

        // ========== HOME & KITCHEN (8) ==========
        add(sup.get("ningbo"), cat.get("home"), "Set 6 vasos vidrio borosilicato 350ml doble pared",
                "硼硅酸盐玻璃杯6件套 350ml 双层", "Set 6 vasos doble pared, vidrio borosilicato, -20°C a 150°C.",
                "Set of 6 double-wall borosilicate glass cups.", "18.20", 1, 2810, "4.8", 760, "34.1",
                imgs("https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=800"), opts(),
                vars(v("GL350", "Pack 6", "18.20", 410, m())),
                tiers(t(1, 19, "18.20"), t(20, 99, "15.50"), t(100, null, "13.20")));

        add(sup.get("ningbo"), cat.get("home"), "Robot aspirador inteligente Wi-Fi mapeo láser 2700Pa",
                "智能扫地机器人 WIFI 激光导航", "Aspirador con mapeo láser, succión 2700Pa, app control.",
                "Robot vacuum with laser mapping, 2700Pa, app-controlled.", "89.00", 1, 1840, "4.6", 510, "22.4",
                imgs("https://images.unsplash.com/photo-1545459720-aac8509eb02c?w=800"),
                opts(opt("颜色", val("曜石黑"), val("月光白"))),
                vars(v("RV-BLK", "Obsidian", "85.00", 220, m("颜色", "曜石黑")),
                        v("RV-WHT", "Moonlight", "85.00", 180, m("颜色", "月光白"))),
                tiers(t(1, 9, "89.00"), t(10, 49, "79.00"), t(50, null, "72.00")));

        add(sup.get("foshan"), cat.get("home"), "Set cuchillos cocina acero damasco 7 piezas", "大马士革钢厨房刀具7件套",
                "Set 7 cuchillos acero damasco, mango pakkawood, bloque magnético.",
                "7-piece damascus steel knife set, pakkawood handle, magnetic block.", "65.00", 1, 1240, "4.8", 380,
                "28.4", imgs("https://images.unsplash.com/photo-1593618998160-e34014e67546?w=800"), opts(),
                vars(v("KNF-7", "Set 7", "65.00", 210, m())),
                tiers(t(1, 9, "65.00"), t(10, 49, "57.50"), t(50, null, "51.40")));

        add(sup.get("foshan"), cat.get("home"), "Cafetera goteo automática 1.5L programable 24h", "全自动滴漏咖啡机 1.5L 可编程",
                "Cafetera goteo 1.5L, temporizador 24h, jarra térmica.",
                "1.5L drip coffee maker, 24h timer, thermal carafe.", "34.50", 1, 2180, "4.5", 680, "19.6",
                imgs("https://images.unsplash.com/photo-1559056199-641a0ac8b55e?w=800"), opts(),
                vars(v("CFM-15", "1.5 litros", "34.50", 320, m())),
                tiers(t(1, 19, "34.50"), t(20, 99, "29.40"), t(100, null, "25.80")));

        add(sup.get("ningbo"), cat.get("home"), "Olla a presión eléctrica 6L 8-en-1", "电压力锅 6L 八合一",
                "Multi-cooker 6L con 8 programas.", "6L multi-cooker with 8 functions.", "49.00", 1, 1820, "4.7", 540,
                "22.9", imgs("https://images.unsplash.com/photo-1585032226651-759b368d7246?w=800"), opts(),
                vars(v("PRC-6", "6 litros", "49.00", 280, m())),
                tiers(t(1, 19, "49.00"), t(20, 99, "42.50"), t(100, null, "37.80")));

        add(sup.get("ningbo"), cat.get("home"), "Sábanas microfibra queen 4 piezas hipoalergénicas",
                "超细纤维床单四件套 queen size", "Sábanas queen 4 pzs, microfibra, no se arrugan.",
                "Queen 4-pc microfiber sheets, wrinkle-resistant.", "13.80", 1, 4210, "4.6", 1180, "23.7",
                imgs("https://images.unsplash.com/photo-1631049307264-da0ec9d70304?w=800"),
                opts(opt("颜色", val("珊瑚粉"), val("浅灰"), val("奶白"))),
                vars(v("BED-CRL", "Coral", "13.80", 480, m("颜色", "珊瑚粉")),
                        v("BED-GRY", "Light Gray", "13.80", 410, m("颜色", "浅灰")),
                        v("BED-CRM", "Cream", "13.80", 320, m("颜色", "奶白"))),
                tiers(t(1, 49, "13.80"), t(50, 199, "11.60"), t(200, null, "9.80")));

        add(sup.get("foshan"), cat.get("home"), "Lámpara LED escritorio plegable USB-C 3 modos", "LED可折叠台灯 Type-C充电",
                "Lámpara LED plegable, 3 modos, USB-C recargable.", "Foldable LED lamp, 3 modes, USB-C rechargeable.",
                "7.60", 1, 5320, "4.7", 1560, "26.3",
                imgs("https://images.unsplash.com/photo-1517991104123-1d56a6e81ed9?w=800"),
                opts(opt("颜色", val("白色"), val("黑色"))),
                vars(v("LMP-WHT", "Blanco", "7.60", 720, m("颜色", "白色")),
                        v("LMP-BLK", "Negro", "7.60", 540, m("颜色", "黑色"))),
                tiers(t(1, 49, "7.60"), t(50, 199, "6.40"), t(200, null, "5.20")));

        add(sup.get("ningbo"), cat.get("home"), "Organizador cocina extensible bambú 12 ranuras", "可伸缩竹制厨房收纳架 12格",
                "Organizador bambú 12 ranuras para utensilios.", "Expandable bamboo organizer with 12 slots.", "9.80",
                1, 3470, "4.6", 920, "21.5", imgs("https://images.unsplash.com/photo-1581636625402-29b2a704ef13?w=800"),
                opts(), vars(v("ORG-12", "12 slots", "9.80", 540, m())),
                tiers(t(1, 49, "9.80"), t(50, 199, "8.20"), t(200, null, "6.80")));

        // ========== BEAUTY (8) ==========
        add(sup.get("hangzhou"), cat.get("beauty"), "Sérum facial vitamina C 20% + hialurónico 30ml",
                "20%维生素C精华 + 透明质酸 30ml", "Sérum 20% vitamina C, ácido hialurónico + ferúlico, airless 30ml.",
                "20% vitamin C, hyaluronic + ferulic acid serum, 30ml airless.", "6.40", 1, 9120, "4.7", 3200, "31.5",
                imgs("https://images.unsplash.com/photo-1571781926291-c477ebfd024b?w=800"), opts(),
                vars(v("VC20", "30ml", "6.40", 1200, m())),
                tiers(t(1, 99, "6.40"), t(100, 499, "5.10"), t(500, null, "4.20")));

        add(sup.get("hangzhou"), cat.get("beauty"), "Brocha pelo natural set 12 piezas con estuche", "化妆刷套装 12支 带收纳包",
                "Set 12 brochas Taklon premium, mango madera, estuche.", "12-piece Taklon makeup brush set with case.",
                "7.20", 1, 3680, "4.5", 1450, "19.8",
                imgs("https://images.unsplash.com/photo-1571781926291-c477ebfd024b?w=800"),
                opts(opt("颜色", val("玫瑰金"), val("黑色"))),
                vars(v("BR12-RG", "Rose Gold", "7.20", 620, m("颜色", "玫瑰金")),
                        v("BR12-BLK", "Black", "7.20", 410, m("颜色", "黑色"))),
                tiers(t(1, 99, "7.20"), t(100, 499, "6.10"), t(500, null, "5.30")));

        add(sup.get("hangzhou"), cat.get("beauty"), "Mascarilla coreana hidratante 10 unidades hialurónico",
                "韩国保湿面膜 10片装", "10 mascarillas coreanas con HA, centella y niacinamida.",
                "10 Korean masks with HA, centella, niacinamide.", "4.80", 5, 6420, "4.8", 2180, "29.4",
                imgs("https://images.unsplash.com/photo-1570554886111-e80fcca6a029?w=800"),
                opts(opt("功效", val("保湿"), val("提亮"), val("舒缓"))),
                vars(v("MSK-HYD", "Hydration", "4.80", 1240, m("功效", "保湿")),
                        v("MSK-BRT", "Brightening", "4.80", 980, m("功效", "提亮")),
                        v("MSK-SOO", "Soothing", "4.80", 720, m("功效", "舒缓"))),
                tiers(t(5, 99, "4.80"), t(100, 499, "3.90"), t(500, null, "3.10")));

        add(sup.get("hangzhou"), cat.get("beauty"), "Plancha pelo cerámica-titanio iónica digital", "陶瓷钛合金电卷棒 数显",
                "Plancha iónica cerámica-titanio, 120-230°C digital.",
                "Ceramic-titanium ionic straightener, 120-230°C digital.", "16.50", 1, 2840, "4.5", 920, "20.2",
                imgs("https://images.unsplash.com/photo-1522338242992-e1a54906a8da?w=800"), opts(),
                vars(v("HIR", "Estándar", "16.50", 410, m())),
                tiers(t(1, 49, "16.50"), t(50, 199, "13.80"), t(200, null, "11.60")));

        add(sup.get("hangzhou"), cat.get("beauty"), "Limpiador facial silicona sónico recargable IPX7", "硅胶声波洁面仪 USB充电",
                "Limpiador silicona sónico, 5 modos, IPX7, USB.", "Sonic silicone cleanser, 5 modes, IPX7, USB.",
                "9.40", 1, 4180, "4.6", 1320, "24.7",
                imgs("https://images.unsplash.com/photo-1600857544200-b2f666a9a2ec?w=800"),
                opts(opt("颜色", val("樱花粉"), val("天空蓝"), val("奶白"))),
                vars(v("CLN-PNK", "Pink", "9.40", 580, m("颜色", "樱花粉")),
                        v("CLN-BLU", "Blue", "9.40", 440, m("颜色", "天空蓝")),
                        v("CLN-CRM", "Cream", "9.40", 360, m("颜色", "奶白"))),
                tiers(t(1, 49, "9.40"), t(50, 199, "7.80"), t(200, null, "6.40")));

        add(sup.get("hangzhou"), cat.get("beauty"), "Crema hidratante facial ácido hialurónico 50g", "玻尿酸保湿面霜 50克",
                "Crema 50g con triple HA, ceramidas y vitamina E.", "50g cream with triple HA, ceramides, vitamin E.",
                "5.60", 1, 7240, "4.7", 2410, "28.6",
                imgs("https://images.unsplash.com/photo-1556228720-195a672e8a03?w=800"), opts(),
                vars(v("CRM", "50g", "5.60", 1240, m())),
                tiers(t(1, 99, "5.60"), t(100, 499, "4.50"), t(500, null, "3.70")));

        add(sup.get("hangzhou"), cat.get("beauty"), "Pestañas postizas naturales 10 pares reutilizables",
                "天然假睫毛 10对装 可重复使用", "10 pares pestañas naturales reutilizables, banda algodón fino.",
                "10-pair natural reusable lashes, thin cotton band.", "3.40", 5, 5320, "4.6", 1840, "26.4",
                imgs("https://images.unsplash.com/photo-1586495777744-4413f21062fa?w=800"),
                opts(opt("款式", val("自然款"), val("浓密款"))),
                vars(v("LSH-NAT", "Natural", "3.40", 1240, m("款式", "自然款")),
                        v("LSH-DRM", "Dramatic", "3.40", 920, m("款式", "浓密款"))),
                tiers(t(5, 99, "3.40"), t(100, 499, "2.70"), t(500, null, "2.10")));

        add(sup.get("hangzhou"), cat.get("beauty"), "Perfume floral mujer 50ml EDP", "女士花香水 50ml EDP",
                "EDP floral mujer 50ml, jazmín, peonía, sándalo.", "50ml floral EDP, jasmine, peony, sandalwood.",
                "12.80", 1, 1840, "4.5", 580, "16.8",
                imgs("https://images.unsplash.com/photo-1541643600914-78b084683601?w=800"), opts(),
                vars(v("PFM", "50ml", "12.80", 320, m())),
                tiers(t(1, 49, "12.80"), t(50, 199, "10.50"), t(200, null, "8.90")));

        // ========== SPORTS (7) ==========
        add(sup.get("yiwu"), cat.get("sports"), "Esterilla yoga TPE doble cara 6mm antideslizante",
                "瑜伽垫 TPE双面纹理 6mm 防滑", "Esterilla TPE doble textura, 6mm, antideslizante, libre de PVC.",
                "Double-textured TPE yoga mat, 6mm, non-slip, PVC-free.", "8.50", 1, 4210, "4.6", 1280, "23.7",
                imgs("https://images.unsplash.com/photo-1601925260368-ae2f83cf8b7f?w=800"),
                opts(opt("颜色", val("薄荷绿"), val("樱花粉"), val("石墨灰"))),
                vars(v("YM-GRN", "Mint", "8.50", 350, m("颜色", "薄荷绿")),
                        v("YM-PNK", "Sakura", "8.50", 290, m("颜色", "樱花粉")),
                        v("YM-GRY", "Graphite", "8.50", 220, m("颜色", "石墨灰"))),
                tiers(t(1, 49, "8.50"), t(50, 199, "7.10"), t(200, null, "5.80")));

        add(sup.get("yiwu"), cat.get("sports"), "Botella deportiva acero inox 750ml termo 24h",
                "运动水壶 316不锈钢 750ml 24小时保温", "Botella térmica 316 inox, doble pared, retención 24h.",
                "316 stainless vacuum-insulated bottle, 750ml, 24h.", "9.90", 1, 3920, "4.7", 980, "21.4",
                imgs("https://images.unsplash.com/photo-1602143407151-7111542de6e8?w=800"),
                opts(opt("颜色", val("墨绿"), val("珊瑚橙"), val("不锈钢"))),
                vars(v("HB-GRN", "Forest", "9.90", 410, m("颜色", "墨绿")),
                        v("HB-ORG", "Coral", "9.90", 380, m("颜色", "珊瑚橙")),
                        v("HB-STL", "Steel", "9.90", 290, m("颜色", "不锈钢"))),
                tiers(t(1, 49, "9.90"), t(50, 199, "8.50"), t(200, null, "7.20")));

        add(sup.get("dongguan"), cat.get("sports"), "Mancuernas hexagonales recubiertas en goma", "六角橡胶哑铃 5公斤 一对",
                "Par de mancuernas hexagonales recubiertas en goma.", "Pair of hex rubber-coated dumbbells.", "22.40",
                1, 1840, "4.7", 510, "20.5", imgs("https://images.unsplash.com/photo-1583454110551-21f2fa2afe61?w=800"),
                opts(opt("规格", val("5公斤"), val("7.5公斤"), val("10公斤"))),
                vars(v("DBL-5", "5kg", "22.40", 180, m("规格", "5公斤")),
                        v("DBL-75", "7.5kg", "29.80", 140, m("规格", "7.5公斤")),
                        v("DBL-10", "10kg", "37.20", 110, m("规格", "10公斤"))),
                tiers(t(1, 9, "22.40"), t(10, 49, "19.80"), t(50, null, "17.40")));

        add(sup.get("dongguan"), cat.get("sports"), "Bandas resistencia látex 5 niveles con bolsa", "乳胶弹力带5档 含收纳袋",
                "Set 5 bandas resistencia látex con manijas y bolsa.",
                "5-level latex resistance band set with handles + bag.", "6.40", 1, 5210, "4.6", 1680, "25.3",
                imgs("https://images.unsplash.com/photo-1517836357463-d25dfeac3438?w=800"), opts(),
                vars(v("BND-5", "Set 5", "6.40", 920, m())),
                tiers(t(1, 49, "6.40"), t(50, 199, "5.40"), t(200, null, "4.40")));

        add(sup.get("yiwu"), cat.get("sports"), "Mochila senderismo 40L impermeable ripstop", "户外登山包 40L 防水",
                "Mochila 40L ripstop impermeable, soporte lumbar, hydration sleeve.",
                "40L waterproof ripstop hiking backpack with lumbar support.", "24.80", 1, 1640, "4.7", 480, "22.1",
                imgs("https://images.unsplash.com/photo-1553062407-98eeb64c6a62?w=800"),
                opts(opt("颜色", val("军绿"), val("黑色"))),
                vars(v("BPK-OLV", "Olive", "24.80", 220, m("颜色", "军绿")),
                        v("BPK-BLK", "Black", "24.80", 190, m("颜色", "黑色"))),
                tiers(t(1, 19, "24.80"), t(20, 99, "21.40"), t(100, null, "18.20")));

        add(sup.get("dongguan"), cat.get("sports"), "Tienda camping ultraligera 2 personas 1.8kg", "双人超轻帐篷 户外野营",
                "Tienda 2 personas 1.8kg, doble capa impermeable.", "2-person ultralight tent, 1.8kg, double-layer.",
                "38.00", 1, 1240, "4.6", 380, "18.4",
                imgs("https://images.unsplash.com/photo-1504280390367-361c6d9f38f4?w=800"),
                opts(opt("颜色", val("橙色"), val("绿色"))),
                vars(v("TNT-ORG", "Orange", "38.00", 180, m("颜色", "橙色")),
                        v("TNT-GRN", "Green", "38.00", 140, m("颜色", "绿色"))),
                tiers(t(1, 9, "38.00"), t(10, 49, "33.40"), t(50, null, "29.80")));

        add(sup.get("yiwu"), cat.get("sports"), "Cuerda saltar rodamientos acero ajustable", "钢丝跳绳 轴承 可调长度",
                "Cuerda saltar con rodamientos, acero ajustable, mango ergonómico.",
                "Speed jump rope with bearings, adjustable steel cable.", "3.20", 1, 7840, "4.7", 2410, "32.8",
                imgs("https://images.unsplash.com/photo-1517836357463-d25dfeac3438?w=800"),
                opts(opt("颜色", val("黑色"), val("红色"))),
                vars(v("ROP-BLK", "Black", "3.20", 1480, m("颜色", "黑色")),
                        v("ROP-RED", "Red", "3.20", 1120, m("颜色", "红色"))),
                tiers(t(1, 99, "3.20"), t(100, 499, "2.60"), t(500, null, "2.10")));

        // ========== TOYS (7) ==========
        add(sup.get("yiwu"), cat.get("toys"), "Bloques construcción magnéticos 100 piezas", "磁力片积木玩具 100片 益智拼装",
                "Set 100 bloques magnéticos translúcidos, ABS food-grade.",
                "100-piece translucent magnetic blocks, food-grade ABS.", "14.60", 1, 2710, "4.9", 690, "27.6",
                imgs("https://images.unsplash.com/photo-1587654780291-39c9404d746b?w=800"), opts(),
                vars(v("MAG-100", "100 piezas", "14.60", 450, m())),
                tiers(t(1, 19, "14.60"), t(20, 99, "12.40"), t(100, null, "10.20")));

        add(sup.get("guangzhou"), cat.get("toys"), "Peluche dinosaurio luminoso 30cm musical", "发光恐龙毛绒玩具 30cm 安抚陪睡",
                "Peluche dinosaurio LED 7 colores y arrullo musical.", "Plush dinosaur with 7-color LED + lullaby.",
                "5.80", 1, 1980, "4.6", 420, "17.2",
                imgs("https://images.unsplash.com/photo-1606107557195-0e29a4b5b4aa?w=800"),
                opts(opt("颜色", val("青绿"), val("樱粉"))),
                vars(v("DINO-GRN", "Verde", "5.80", 280, m("颜色", "青绿")),
                        v("DINO-PNK", "Rosa", "5.80", 240, m("颜色", "樱粉"))),
                tiers(t(1, 99, "5.80"), t(100, 499, "4.70"), t(500, null, "3.90")));

        add(sup.get("shanghai"), cat.get("toys"), "Coche RC 4WD todoterreno escala 1:14 2.4GHz", "遥控车 4WD 越野 1:14 比例",
                "Coche RC 4WD 1:14, 25km/h, 2.4GHz.", "1:14 4WD RC car, max 25km/h, 2.4GHz.", "27.40", 1, 2140, "4.5",
                580, "18.6", imgs("https://images.unsplash.com/photo-1566576912321-d58ddd7a6088?w=800"),
                opts(opt("颜色", val("红色"), val("蓝色"))),
                vars(v("RCC-RED", "Red", "27.40", 280, m("颜色", "红色")),
                        v("RCC-BLU", "Blue", "27.40", 220, m("颜色", "蓝色"))),
                tiers(t(1, 19, "27.40"), t(20, 99, "23.40"), t(100, null, "19.80")));

        add(sup.get("shanghai"), cat.get("toys"), "Pizarra LCD escritura 12 pulgadas niños", "12寸LCD儿童画板 写字板",
                "Pizarra LCD 12\" niños, borrado one-touch.", "12\" LCD writing tablet for kids, one-touch erase.",
                "4.20", 1, 6840, "4.7", 2180, "29.4",
                imgs("https://images.unsplash.com/photo-1565374395542-0ce18882c857?w=800"),
                opts(opt("颜色", val("蓝色"), val("粉色"), val("绿色"))),
                vars(v("LCD-BLU", "Blue", "4.20", 920, m("颜色", "蓝色")), v("LCD-PNK", "Pink", "4.20", 780, m("颜色", "粉色")),
                        v("LCD-GRN", "Green", "4.20", 540, m("颜色", "绿色"))),
                tiers(t(1, 99, "4.20"), t(100, 499, "3.40"), t(500, null, "2.70")));

        add(sup.get("yiwu"), cat.get("toys"), "Puzzle 1000 piezas paisaje premium 2mm", "1000片拼图 风景 高端纸板",
                "Puzzle 1000 piezas, cartón 2mm, póster.", "1000-piece premium 2mm puzzle with poster.", "8.40", 1,
                1840, "4.7", 480, "21.8", imgs("https://images.unsplash.com/photo-1547954575-855750c57bd3?w=800"),
                opts(opt("图案", val("巴黎铁塔"), val("樱花山"))),
                vars(v("PZL-EFF", "Eiffel", "8.40", 340, m("图案", "巴黎铁塔")),
                        v("PZL-CHM", "Cherry Mountain", "8.40", 280, m("图案", "樱花山"))),
                tiers(t(1, 49, "8.40"), t(50, 199, "6.90"), t(200, null, "5.80")));

        add(sup.get("shanghai"), cat.get("toys"), "Kit STEM 50 experimentos ciencia 8-14 años", "STEM科学实验套装 50个实验",
                "Kit STEM 50 experimentos seguros, manual incluido.", "STEM kit, 50 safe experiments, manual included.",
                "11.80", 1, 1640, "4.8", 420, "23.6",
                imgs("https://images.unsplash.com/photo-1532187863486-abf9dbad1b69?w=800"), opts(),
                vars(v("STM-50", "50 experimentos", "11.80", 380, m())),
                tiers(t(1, 19, "11.80"), t(20, 99, "9.80"), t(100, null, "8.20")));

        add(sup.get("guangzhou"), cat.get("toys"), "Kit slime DIY 24 colores con accesorios", "DIY史莱姆套装 24色 含配件",
                "Kit slime 24 colores, glitter, charms y herramientas.",
                "DIY slime kit, 24 colors, glitter, charms, tools.", "6.20", 1, 3420, "4.5", 920, "19.4",
                imgs("https://images.unsplash.com/photo-1573376670774-4427757f7963?w=800"), opts(),
                vars(v("SLM-24", "24 colores", "6.20", 580, m())),
                tiers(t(1, 49, "6.20"), t(50, 199, "5.10"), t(200, null, "4.20")));

        publishAll();
    }

    /* ============================== publish + helpers ============================== */

    private void publishAll() {
        productRepository.findAll().forEach(p -> {
            if (p.getStatus() == ProductStatus.DRAFT) {
                p.setStatus(ProductStatus.ACTIVE);
                double salesNorm = Math.min(1.0, p.getMonthlySales() / 1000.0);
                double rating = p.getRating() != null ? p.getRating().doubleValue() / 5.0 : 0;
                double repurchase = p.getRepurchaseRate() != null ? p.getRepurchaseRate().doubleValue() / 100.0 : 0;
                double reviews = Math.min(1.0, p.getReviewCount() / 500.0);
                double score = 0.4 * salesNorm + 0.3 * rating + 0.2 * repurchase + 0.1 * reviews;
                p.setTrendScore(BigDecimal.valueOf(Math.round(score * 10000) / 10000.0));
                productRepository.save(p);
            }
        });
    }

    private void add(UUID supplierId, UUID categoryId, String esTitle, String zhTitle, String esDesc, String enDesc,
            String basePrice, int moq, int monthlySales, String rating, int reviewCount, String repurchaseRate,
            List<IngestImage> images, List<IngestVariantOption> options, List<IngestVariant> variants,
            List<IngestPriceTier> priceTiers) {
        String externalId = String.format("OFFER-%05d", sku.getAndIncrement());
        IngestProductRequest req = new IngestProductRequest("1688", externalId, zhTitle, esDesc, esDesc, null, moq,
                new BigDecimal(basePrice), "CNY", null, monthlySales, new BigDecimal(repurchaseRate),
                new BigDecimal(rating), reviewCount, "https://detail.1688.com/offer/" + externalId + ".html",
                supplierId, categoryId, images, options, variants, priceTiers);
        ProductEntity saved = catalogService.upsertProduct(req);
        attachTranslations(saved, esTitle, zhTitle, esDesc, enDesc);
    }

    // DROP-473: hasta ahora el seed sólo persistía traducciones es/en por producto
    // y el storefront mostraba el título inglés a los usuarios pt/zh. Ampliamos a
    // los cuatro idiomas con UI completa (es/en/pt/zh) usando como base el dato
    // que ya tenemos en cada producto:
    //   - es: título y descripción en español (original del seed)
    //   - en: título y descripción traducidos al inglés
    //   - zh: título chino original del proveedor (1688) — al menos no cae a EN
    //   - pt: arrancamos copiando es, que en B2B comercial es comprensible para
    //         lusoparlantes; la traducción "buena" la inyectará el pipeline de
    //         traducción automática cuando esté activo.
    private void attachTranslations(ProductEntity p, String esTitle, String zhTitle, String esDesc, String enDesc) {
        ProductEntity managed = productRepository.findById(p.getId()).orElse(null);
        if (managed == null)
            return;
        managed.getTranslations().clear();
        managed.getTranslations().add(ProductTranslationEntity.builder().product(managed).language("es").title(esTitle)
                .shortDescription(esDesc).description(esDesc).provider("seed").build());
        managed.getTranslations().add(ProductTranslationEntity.builder().product(managed).language("en").title(esTitle)
                .shortDescription(enDesc).description(enDesc).provider("seed").build());
        managed.getTranslations().add(ProductTranslationEntity.builder().product(managed).language("zh").title(zhTitle)
                .shortDescription(esDesc).description(esDesc).provider("seed").build());
        managed.getTranslations().add(ProductTranslationEntity.builder().product(managed).language("pt").title(esTitle)
                .shortDescription(esDesc).description(esDesc).provider("seed").build());
        productRepository.save(managed);
    }

    /* ----- tiny DSL helpers ----- */
    private static List<IngestImage> imgs(String... urls) {
        java.util.List<IngestImage> out = new java.util.ArrayList<>();
        for (int i = 0; i < urls.length; i++)
            out.add(new IngestImage(urls[i], i, i == 0 ? "MAIN" : "GALLERY"));
        return out;
    }

    private static IngestVariantOption opt(String nameZh, IngestVariantValue... values) {
        return new IngestVariantOption(nameZh, 0, List.of(values));
    }

    @SafeVarargs
    private static List<IngestVariantOption> opts(IngestVariantOption... options) {
        return List.of(options);
    }

    private static IngestVariantValue val(String valueZh) {
        return new IngestVariantValue(valueZh, 0, null);
    }

    private static IngestVariant v(String sku, String title, String price, int stock, Map<String, String> opts) {
        return new IngestVariant(sku, sku, title, new BigDecimal(price), stock, null, opts);
    }

    @SafeVarargs
    private static List<IngestVariant> vars(IngestVariant... v) {
        return List.of(v);
    }

    private static IngestPriceTier t(int min, Integer max, String price) {
        return new IngestPriceTier(min, max, new BigDecimal(price), "CNY");
    }

    @SafeVarargs
    private static List<IngestPriceTier> tiers(IngestPriceTier... ts) {
        return List.of(ts);
    }

    private static Map<String, String> m(String... kv) {
        if (kv.length == 0)
            return Map.of();
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
            out.put(kv[i], kv[i + 1]);
        return out;
    }

    @SuppressWarnings("unused")
    private CategoryEntity ignore() {
        return null;
    }
}
