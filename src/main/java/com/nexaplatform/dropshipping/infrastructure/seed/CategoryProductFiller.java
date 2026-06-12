package com.nexaplatform.dropshipping.infrastructure.seed;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestImage;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestPriceTier;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariant;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantOption;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tops every catalog category up to {@value #TARGET_PER_CATEGORY} coherent demo products
 * (titles/descriptions matching the category theme), creating any missing category, and
 * keeps the search index in sync. Runs after the base {@link DemoCatalogSeedRunner}; it is
 * idempotent (only fills the deficit per category) and rebuilds the OpenSearch index
 * whenever it is behind the database, so the storefront/admin catalog browse — which reads
 * from OpenSearch — shows the full catalog even after an index volume reset.
 *
 * <p>Disable with {@code nexadrop.catalog-fill.enabled=false}.
 */
@Slf4j
@Component
@Order(100)
@ConditionalOnProperty(prefix = "nexadrop.catalog-fill", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class CategoryProductFiller {

    static final int TARGET_PER_CATEGORY = 50;

    private final CatalogUseCase catalogService;
    private final CatalogFillWriter writer;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final ProductIndexer productIndexer;
    private final OpenSearchClient osClient;

    @Value("${nexadrop.opensearch.products-index}")
    private String index;

    // Title modifiers cycled to vary archetypes into distinct, still-coherent products: {es, en, zh}.
    private static final String[][] MODS = { { "Pro", "Pro", "专业版" }, { "Premium", "Premium", "高级" },
            { "Compacto", "Compact", "紧凑型" }, { "Plus", "Plus", "增强版" }, { "Lite", "Lite", "轻便版" },
            { "Smart", "Smart", "智能" }, { "2024", "2024", "2024款" }, { "XL", "XL", "加大" } };

    @EventListener(ApplicationReadyEvent.class)
    public void fill() {
        List<SupplierEntity> suppliers = supplierRepository.findAll();
        if (suppliers.isEmpty()) {
            log.warn("Catalog fill: no suppliers present — skipping (run the demo seed first)");
            return;
        }

        int created = 0;
        for (Cat c : CATALOG) {
            CategoryEntity cat = categoryRepository.findBySlug(c.slug)
                    .orElseGet(() -> createCategory(c));
            long have = productRepository.countByCategoryId(cat.getId());
            int deficit = (int) (TARGET_PER_CATEGORY - have);
            for (int i = 0; i < deficit; i++) {
                int seq = (int) have + i + 1;
                SupplierEntity sup = suppliers.get((Math.abs(c.slug.hashCode()) + i) % suppliers.size());
                createProduct(c, cat.getId(), sup.getId(), seq, i);
                created++;
            }
            if (deficit > 0)
                log.info("Catalog fill: +{} products on '{}' (now {})", deficit, c.slug, TARGET_PER_CATEGORY);
        }
        log.info("Catalog fill finished — {} products created, catalog total {}", created, productRepository.count());

        reindexIfBehind();
    }

    /* ============================== product generation ============================== */

    private void createProduct(Cat c, UUID categoryId, UUID supplierId, int seq, int i) {
        String[] arch = c.archs[i % c.archs.length];
        String[] mod = MODS[(i / c.archs.length) % MODS.length];
        String model = "M" + (100 + seq);

        String esTitle = arch[0] + " " + mod[0] + " " + model;
        String enTitle = arch[1] + " " + mod[1] + " " + model;
        String zhTitle = arch[2] + mod[2] + " " + model;
        String esDesc = arch[0] + " de alta calidad para " + c.nameEs.toLowerCase()
                + ". Material duradero, acabado premium y diseño práctico para uso diario.";
        String enDesc = arch[1] + " — premium quality for " + c.nameEn.toLowerCase()
                + ". Durable materials, refined finish and a practical everyday design.";

        BigDecimal price = BigDecimal.valueOf(c.basePrice + (i % 6) * c.priceStep).setScale(2, RoundingMode.HALF_UP);
        int monthlySales = 400 + (i * 37) % 4600;
        BigDecimal rating = BigDecimal.valueOf(Math.min(4.9, 4.2 + (i % 8) * 0.1)).setScale(1, RoundingMode.HALF_UP);
        int reviewCount = 60 + (i * 53) % 2400;
        BigDecimal repurchase = BigDecimal.valueOf(12 + (i % 22));

        String externalId = "GEN-" + c.slug + "-" + String.format("%03d", seq);
        String[] imgUrls = c.images;
        List<IngestImage> images = new ArrayList<>();
        for (int k = 0; k < imgUrls.length; k++)
            images.add(new IngestImage(imgUrls[k], k, k == 0 ? "MAIN" : "GALLERY"));

        List<IngestVariant> variants = List.of(new IngestVariant(externalId + "-DEF", externalId + "-DEF",
                esTitle, price, 200 + (i * 7) % 800, null, Map.of()));
        List<IngestPriceTier> tiers = List.of(
                new IngestPriceTier(1, 49, price, "CNY"),
                new IngestPriceTier(50, 199, price.multiply(BigDecimal.valueOf(0.88)).setScale(2, RoundingMode.HALF_UP),
                        "CNY"),
                new IngestPriceTier(200, null, price.multiply(BigDecimal.valueOf(0.78)).setScale(2, RoundingMode.HALF_UP),
                        "CNY"));

        IngestProductRequest req = new IngestProductRequest("1688", externalId, zhTitle, esDesc, esDesc, null, 1, price,
                "CNY", null, monthlySales, repurchase, rating, reviewCount,
                "https://detail.1688.com/offer/" + externalId + ".html", supplierId, categoryId, images,
                List.<IngestVariantOption>of(), variants, tiers);

        writer.write(req, esTitle, enTitle, zhTitle, esDesc, enDesc);
    }

    private CategoryEntity createCategory(Cat c) {
        UUID id = catalogService.upsertCategory(new IngestCategoryRequest(c.slug, null, "1688", c.externalId, c.nameZh,
                c.position, c.icon, Map.of("es", c.nameEs, "en", c.nameEn, "pt", c.namePt))).getId();
        log.info("Catalog fill: created missing category '{}'", c.slug);
        return categoryRepository.findById(id).orElseThrow();
    }

    /* ============================== reindex ============================== */

    private void reindexIfBehind() {
        long dbCount = productRepository.count();
        long osCount;
        try {
            osCount = osClient.count(co -> co.index(index)).count();
        } catch (Exception e) {
            osCount = 0;
        }
        if (osCount >= dbCount) {
            log.info("Catalog fill: search index in sync ({} docs)", osCount);
            return;
        }
        log.info("Catalog fill: search index behind ({} of {}) — reindexing all products", osCount, dbCount);
        int[] done = { 0 };
        productRepository.findAll().forEach(p -> {
            productIndexer.indexProduct(p.getId());
            done[0]++;
        });
        log.info("Catalog fill: reindexed {} products into '{}'", done[0], index);
    }

    /* ============================== category specs ============================== */

    /** Immutable demo-category spec: theme metadata + product archetypes ({es, en, zh} each). */
    private static final class Cat {
        final String slug, nameEs, nameEn, namePt, nameZh, icon, externalId;
        final int position;
        final double basePrice, priceStep;
        final String[] images;
        final String[][] archs;

        Cat(String slug, String nameEs, String nameEn, String namePt, String nameZh, String icon, String externalId,
                int position, double basePrice, double priceStep, String[] images, String[][] archs) {
            this.slug = slug;
            this.nameEs = nameEs;
            this.nameEn = nameEn;
            this.namePt = namePt;
            this.nameZh = nameZh;
            this.icon = icon;
            this.externalId = externalId;
            this.position = position;
            this.basePrice = basePrice;
            this.priceStep = priceStep;
            this.images = images;
            this.archs = archs;
        }
    }

    private static String[] a(String es, String en, String zh) {
        return new String[] { es, en, zh };
    }

    private static final String[] IMG_ELECTRONICS = {
            "https://images.unsplash.com/photo-1498049794561-7780e7231661?w=800",
            "https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=800" };
    private static final String[] IMG_FASHION = {
            "https://images.unsplash.com/photo-1521572163474-6864f9cf17ab?w=800",
            "https://images.unsplash.com/photo-1483985988355-763728e1935b?w=800" };
    private static final String[] IMG_HOME = {
            "https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=800",
            "https://images.unsplash.com/photo-1583845112203-29329902332e?w=800" };
    private static final String[] IMG_BEAUTY = {
            "https://images.unsplash.com/photo-1556228720-195a672e8a03?w=800",
            "https://images.unsplash.com/photo-1571781926291-c477ebfd024b?w=800" };
    private static final String[] IMG_SPORTS = {
            "https://images.unsplash.com/photo-1517836357463-d25dfeac3438?w=800",
            "https://images.unsplash.com/photo-1571019613454-1cb2f99b2d8b?w=800" };
    private static final String[] IMG_TOYS = {
            "https://images.unsplash.com/photo-1566576912321-d58ddd7a6088?w=800",
            "https://images.unsplash.com/photo-1587654780291-39c9404d746b?w=800" };
    private static final String[] IMG_AUTO = {
            "https://images.unsplash.com/photo-1503376780353-7e6692767b70?w=800",
            "https://images.unsplash.com/photo-1492144534655-ae79c964c9d7?w=800" };
    private static final String[] IMG_OFFICE = {
            "https://images.unsplash.com/photo-1497032205916-ac775f0649ae?w=800",
            "https://images.unsplash.com/photo-1456735190827-d1262f71b8a3?w=800" };
    private static final String[] IMG_PET = {
            "https://images.unsplash.com/photo-1601758228041-f3b2795255f1?w=800",
            "https://images.unsplash.com/photo-1450778869180-41d0601e046e?w=800" };
    private static final String[] IMG_TOOLS = {
            "https://images.unsplash.com/photo-1572981779307-38b8cabb2407?w=800",
            "https://images.unsplash.com/photo-1530124566582-a618bc2615dc?w=800" };
    private static final String[] IMG_JEWELRY = {
            "https://images.unsplash.com/photo-1515562141207-7a88fb7ce338?w=800",
            "https://images.unsplash.com/photo-1605100804763-247f67b3557e?w=800" };
    private static final String[] IMG_GARDEN = {
            "https://images.unsplash.com/photo-1416879595882-3373a0480b5b?w=800",
            "https://images.unsplash.com/photo-1466692476868-aef1dfb1e735?w=800" };
    private static final String[] IMG_BABY = {
            "https://images.unsplash.com/photo-1515488042361-ee00e0ddd4e4?w=800",
            "https://images.unsplash.com/photo-1555252333-9f8e92e65df9?w=800" };
    private static final String[] IMG_LIGHTING = {
            "https://images.unsplash.com/photo-1513506003901-1e6a229e2d15?w=800",
            "https://images.unsplash.com/photo-1517991104123-1d56a6e81ed9?w=800" };

    private static final Cat[] CATALOG = {
            new Cat("consumer-electronics", "Electrónica de consumo", "Consumer Electronics", "Eletrônica de consumo",
                    "数码电子", "microchip", "10001", 1, 14.90, 6.0, IMG_ELECTRONICS, new String[][] {
                            a("Auriculares inalámbricos", "Wireless earbuds", "无线耳机"),
                            a("Altavoz Bluetooth", "Bluetooth speaker", "蓝牙音箱"),
                            a("Cargador rápido USB-C", "USB-C fast charger", "USB-C快充"),
                            a("Ratón inalámbrico", "Wireless mouse", "无线鼠标"),
                            a("Webcam HD 1080P", "HD 1080P webcam", "高清摄像头"),
                            a("Hub USB-C 6 en 1", "6-in-1 USB-C hub", "USB-C扩展坞"),
                            a("Batería externa 10000mAh", "10000mAh power bank", "移动电源"),
                            a("Micrófono USB", "USB microphone", "USB麦克风"),
                            a("Soporte para portátil", "Laptop stand", "笔记本支架"),
                            a("Lector de tarjetas SD", "SD card reader", "读卡器"),
                            a("Regleta inteligente WiFi", "WiFi smart plug strip", "智能插排"),
                            a("Ventilador USB de escritorio", "USB desk fan", "USB风扇") }),
            new Cat("fashion-apparel", "Moda y vestir", "Fashion & Apparel", "Moda e vestuário", "时尚服饰", "shirt",
                    "10002", 2, 9.50, 3.5, IMG_FASHION, new String[][] {
                            a("Camiseta de algodón", "Cotton t-shirt", "纯棉T恤"),
                            a("Sudadera con capucha", "Hoodie sweatshirt", "连帽卫衣"),
                            a("Pantalón chino", "Chino trousers", "休闲长裤"),
                            a("Chaqueta vaquera", "Denim jacket", "牛仔外套"),
                            a("Vestido casual", "Casual dress", "休闲连衣裙"),
                            a("Bufanda de punto", "Knitted scarf", "针织围巾"),
                            a("Calcetines pack 5", "5-pack socks", "袜子5双装"),
                            a("Gorra de béisbol", "Baseball cap", "棒球帽"),
                            a("Cinturón de cuero", "Leather belt", "皮带"),
                            a("Bolso bandolera", "Crossbody bag", "斜挎包"),
                            a("Gafas de sol polarizadas", "Polarized sunglasses", "偏光太阳镜"),
                            a("Zapatillas deportivas", "Sneakers", "运动鞋") }),
            new Cat("home-kitchen", "Hogar y cocina", "Home & Kitchen", "Casa e cozinha", "家居厨房", "house", "10003",
                    3, 12.80, 5.0, IMG_HOME, new String[][] {
                            a("Sartén antiadherente", "Non-stick frying pan", "不粘锅"),
                            a("Set de tazas de vidrio", "Glass cup set", "玻璃杯套装"),
                            a("Organizador de cajón", "Drawer organizer", "抽屉收纳盒"),
                            a("Cuchillo de chef", "Chef knife", "厨师刀"),
                            a("Tabla de cortar bambú", "Bamboo cutting board", "竹砧板"),
                            a("Recipiente hermético", "Airtight container", "密封保鲜盒"),
                            a("Báscula de cocina digital", "Digital kitchen scale", "厨房电子秤"),
                            a("Set de cubiertos", "Cutlery set", "餐具套装"),
                            a("Jarra medidora", "Measuring jug", "量杯"),
                            a("Especiero giratorio", "Rotating spice rack", "调味料架"),
                            a("Paños de microfibra", "Microfiber cloths", "超细纤维抹布"),
                            a("Exprimidor manual", "Manual juicer", "手动榨汁器") }),
            new Cat("beauty-personal-care", "Belleza y cuidado personal", "Beauty & Personal Care",
                    "Beleza e cuidado pessoal", "美妆个护", "spa", "10004", 4, 6.40, 2.5, IMG_BEAUTY, new String[][] {
                            a("Crema hidratante facial", "Facial moisturizer", "保湿面霜"),
                            a("Sérum de vitamina C", "Vitamin C serum", "维C精华"),
                            a("Mascarilla hidratante", "Hydrating face mask", "保湿面膜"),
                            a("Espejo LED de maquillaje", "LED makeup mirror", "LED化妆镜"),
                            a("Set de brochas", "Makeup brush set", "化妆刷套装"),
                            a("Secador de pelo iónico", "Ionic hair dryer", "负离子吹风机"),
                            a("Recortadora de precisión", "Precision trimmer", "精修剃毛器"),
                            a("Bálsamo labial nutritivo", "Nourishing lip balm", "润唇膏"),
                            a("Exfoliante corporal", "Body scrub", "身体磨砂膏"),
                            a("Peine desenredante", "Detangling comb", "顺发梳"),
                            a("Aceite esencial relajante", "Relaxing essential oil", "精油"),
                            a("Neceser de viaje", "Travel toiletry bag", "旅行收纳包") }),
            new Cat("sports-outdoors", "Deportes y aire libre", "Sports & Outdoors", "Esportes e ar livre", "运动户外",
                    "dumbbell", "10005", 5, 8.90, 4.0, IMG_SPORTS, new String[][] {
                            a("Esterilla de yoga", "Yoga mat", "瑜伽垫"),
                            a("Banda elástica de resistencia", "Resistance band", "弹力带"),
                            a("Botella térmica de acero", "Steel insulated bottle", "保温水壶"),
                            a("Guantes de gimnasio", "Gym gloves", "健身手套"),
                            a("Cuerda para saltar", "Jump rope", "跳绳"),
                            a("Rodillera deportiva", "Sports knee brace", "运动护膝"),
                            a("Mochila de senderismo", "Hiking backpack", "登山包"),
                            a("Linterna LED táctica", "Tactical LED flashlight", "战术手电"),
                            a("Toalla de microfibra", "Microfiber towel", "速干毛巾"),
                            a("Brújula de montaña", "Mountain compass", "户外指南针"),
                            a("Bastón de trekking", "Trekking pole", "登山杖"),
                            a("Rodillo de masaje muscular", "Muscle massage roller", "肌肉放松滚轮") }),
            new Cat("toys-gifts", "Juguetes y regalos", "Toys & Gifts", "Brinquedos e presentes", "玩具礼品", "gift",
                    "10006", 6, 6.20, 3.0, IMG_TOYS, new String[][] {
                            a("Bloques de construcción", "Building blocks", "积木玩具"),
                            a("Peluche suave", "Soft plush toy", "毛绒玩具"),
                            a("Puzzle 1000 piezas", "1000-piece puzzle", "1000片拼图"),
                            a("Coche teledirigido", "Remote control car", "遥控车"),
                            a("Muñeca articulada", "Articulated doll", "关节娃娃"),
                            a("Juego de mesa familiar", "Family board game", "桌游"),
                            a("Set de plastilina", "Modeling clay set", "彩泥套装"),
                            a("Figura de acción", "Action figure", "可动人偶"),
                            a("Pizarra mágica LCD", "LCD drawing tablet", "液晶画板"),
                            a("Kit de ciencia STEM", "STEM science kit", "科学实验套装"),
                            a("Cometa de colores", "Colorful kite", "风筝"),
                            a("Set de manualidades", "Arts and crafts set", "手工套装") }),
            new Cat("auto-parts", "Auto y motos", "Auto Parts & Motorcycle", "Auto e motos", "汽摩配件", "car", "10007",
                    7, 13.50, 6.0, IMG_AUTO, new String[][] {
                            a("Cámara de tablero Full HD", "Full HD dash cam", "行车记录仪"),
                            a("Soporte de móvil para coche", "Car phone mount", "车载手机支架"),
                            a("Cargador de coche dual USB", "Dual USB car charger", "车载充电器"),
                            a("Fundas de asiento universales", "Universal seat covers", "汽车座套"),
                            a("Organizador de maletero", "Trunk organizer", "后备箱收纳箱"),
                            a("Aspiradora de coche portátil", "Portable car vacuum", "车载吸尘器"),
                            a("Compresor de aire portátil", "Portable air compressor", "车载充气泵"),
                            a("Cubre volante de cuero", "Leather steering wheel cover", "方向盘套"),
                            a("Tira LED interior", "Interior LED strip", "汽车氛围灯"),
                            a("Manómetro de neumáticos digital", "Digital tire gauge", "胎压计"),
                            a("Juego de alfombrillas", "Floor mat set", "汽车脚垫"),
                            a("Ambientador de coche", "Car air freshener", "车载香薰") }),
            new Cat("office-supplies", "Oficina y papelería", "Office & Stationery", "Escritório e papelaria", "办公文具",
                    "pen", "10008", 8, 5.80, 2.5, IMG_OFFICE, new String[][] {
                            a("Cuaderno de tapa dura A5", "A5 hardcover notebook", "A5硬面笔记本"),
                            a("Set de bolígrafos gel", "Gel pen set", "中性笔套装"),
                            a("Organizador de escritorio", "Desk organizer", "桌面收纳盒"),
                            a("Grapadora resistente", "Heavy-duty stapler", "订书机"),
                            a("Calculadora científica", "Scientific calculator", "科学计算器"),
                            a("Archivador con anillas", "Ring binder", "活页文件夹"),
                            a("Set de rotuladores", "Marker set", "马克笔套装"),
                            a("Agenda semanal", "Weekly planner", "周计划本"),
                            a("Portalápices de malla", "Mesh pen holder", "笔筒"),
                            a("Etiquetas adhesivas", "Adhesive labels", "标签贴纸"),
                            a("Cinta adhesiva con dispensador", "Tape with dispenser", "胶带切割器"),
                            a("Pizarra blanca pequeña", "Small whiteboard", "小白板") }),
            new Cat("pet-supplies", "Mascotas", "Pet Supplies", "Produtos para pets", "宠物用品", "paw", "10009", 9,
                    9.20, 4.0, IMG_PET, new String[][] {
                            a("Comedero automático", "Automatic feeder", "自动喂食器"),
                            a("Cama para mascota", "Pet bed", "宠物窝"),
                            a("Correa retráctil", "Retractable leash", "伸缩牵引绳"),
                            a("Juguete masticable", "Chew toy", "磨牙玩具"),
                            a("Cepillo quitapelos", "De-shedding brush", "宠物梳"),
                            a("Transportín de viaje", "Travel carrier", "宠物背包"),
                            a("Bebedero por gravedad", "Gravity water dispenser", "自动饮水器"),
                            a("Arnés acolchado", "Padded harness", "胸背带"),
                            a("Manta para mascota", "Pet blanket", "宠物毯"),
                            a("Rascador para gatos", "Cat scratcher", "猫抓板"),
                            a("Dispensador de premios", "Treat dispenser", "漏食器"),
                            a("Alfombrilla absorbente", "Absorbent pet mat", "宠物吸水垫") }),
            new Cat("tools-hardware", "Herramientas", "Tools & Hardware", "Ferramentas", "五金工具", "wrench", "10010",
                    10, 15.40, 7.0, IMG_TOOLS, new String[][] {
                            a("Taladro atornillador", "Cordless drill", "电钻"),
                            a("Set de destornilladores", "Screwdriver set", "螺丝刀套装"),
                            a("Llave inglesa ajustable", "Adjustable wrench", "活动扳手"),
                            a("Martillo de uña", "Claw hammer", "羊角锤"),
                            a("Nivel láser", "Laser level", "激光水平仪"),
                            a("Cinta métrica 5m", "5m tape measure", "卷尺"),
                            a("Juego de brocas", "Drill bit set", "钻头套装"),
                            a("Alicates universales", "Combination pliers", "钢丝钳"),
                            a("Pistola de silicona", "Glue gun", "热熔胶枪"),
                            a("Multímetro digital", "Digital multimeter", "万用表"),
                            a("Caja de herramientas", "Tool box", "工具箱"),
                            a("Sierra de mano", "Hand saw", "手锯") }),
            new Cat("jewelry-watches", "Joyería y relojes", "Jewelry & Watches", "Joias e relógios", "珠宝手表",
                    "gem", "10011", 11, 11.60, 8.0, IMG_JEWELRY, new String[][] {
                            a("Reloj de pulsera minimalista", "Minimalist wristwatch", "极简手表"),
                            a("Collar de plata 925", "925 silver necklace", "925银项链"),
                            a("Pulsera ajustable", "Adjustable bracelet", "可调手链"),
                            a("Anillo de acero", "Stainless steel ring", "钛钢戒指"),
                            a("Pendientes de circonita", "Zirconia earrings", "锆石耳环"),
                            a("Reloj automático", "Automatic watch", "机械手表"),
                            a("Gemelos elegantes", "Elegant cufflinks", "袖扣"),
                            a("Colgante con grabado", "Engraved pendant", "刻字吊坠"),
                            a("Tobillera de perlas", "Pearl anklet", "珍珠脚链"),
                            a("Set de joyería", "Jewelry set", "首饰套装"),
                            a("Caja organizadora de relojes", "Watch box organizer", "手表收纳盒"),
                            a("Broche decorativo", "Decorative brooch", "胸针") }),
            new Cat("garden-outdoor", "Jardín y exterior", "Garden & Outdoor", "Jardim e exterior", "园艺户外", "leaf",
                    "10012", 12, 12.40, 5.0, IMG_GARDEN, new String[][] {
                            a("Manguera extensible", "Expandable garden hose", "伸缩水管"),
                            a("Set de herramientas de jardín", "Garden tool set", "园艺工具套装"),
                            a("Maceta autorriego", "Self-watering planter", "自动浇水花盆"),
                            a("Regadera de metal", "Metal watering can", "金属洒水壶"),
                            a("Tijeras de podar", "Pruning shears", "修枝剪"),
                            a("Luces solares de jardín", "Solar garden lights", "太阳能庭院灯"),
                            a("Hamaca de tela", "Fabric hammock", "吊床"),
                            a("Parasol de exterior", "Outdoor sun shade", "户外遮阳伞"),
                            a("Guantes de jardinería", "Gardening gloves", "园艺手套"),
                            a("Comedero para pájaros", "Bird feeder", "喂鸟器"),
                            a("Aspersor giratorio", "Rotating sprinkler", "旋转洒水器"),
                            a("Mesa plegable de camping", "Folding camping table", "折叠桌") }),
            new Cat("baby-maternity", "Bebés y maternidad", "Baby & Maternity", "Bebês e maternidade", "母婴用品",
                    "baby", "10013", 13, 8.60, 3.5, IMG_BABY, new String[][] {
                            a("Biberón anticólico", "Anti-colic baby bottle", "防胀气奶瓶"),
                            a("Chupete de silicona", "Silicone pacifier", "硅胶安抚奶嘴"),
                            a("Body de algodón bebé", "Baby cotton bodysuit", "婴儿连体衣"),
                            a("Manta envolvente", "Swaddle blanket", "包被"),
                            a("Monitor de bebé", "Baby monitor", "婴儿监护器"),
                            a("Cambiador portátil", "Portable changing pad", "便携换尿垫"),
                            a("Mochila portabebé ergonómica", "Ergonomic baby carrier", "婴儿背带"),
                            a("Juguete de dentición", "Teething toy", "牙胶玩具"),
                            a("Set de cuidado del bebé", "Baby grooming set", "婴儿护理套装"),
                            a("Babero impermeable", "Waterproof bib", "防水围兜"),
                            a("Esterilizador de biberones", "Bottle sterilizer", "奶瓶消毒器"),
                            a("Bolso pañalera", "Diaper bag", "妈咪包") }),
            new Cat("lighting", "Iluminación", "Lighting", "Iluminação", "照明灯具", "lightbulb", "10014", 14, 7.90, 4.0,
                    IMG_LIGHTING, new String[][] {
                            a("Bombilla LED inteligente", "Smart LED bulb", "智能灯泡"),
                            a("Tira LED RGB", "RGB LED strip", "RGB灯带"),
                            a("Lámpara de mesa regulable", "Dimmable table lamp", "可调台灯"),
                            a("Foco de techo empotrable", "Recessed ceiling light", "嵌入式筒灯"),
                            a("Aplique de pared", "Wall sconce", "壁灯"),
                            a("Guirnalda de luces", "String lights", "灯串"),
                            a("Lámpara de pie", "Floor lamp", "落地灯"),
                            a("Panel LED de techo", "LED ceiling panel", "LED面板灯"),
                            a("Luz nocturna con sensor", "Motion night light", "感应夜灯"),
                            a("Proyector de estrellas", "Star projector", "星空投影灯"),
                            a("Plafón LED", "LED flush mount", "吸顶灯"),
                            a("Linterna recargable", "Rechargeable flashlight", "充电手电筒") }) };
}
