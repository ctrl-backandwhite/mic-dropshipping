package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkAxis;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkReview;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkVariant;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductSpecificationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueTranslationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El export del bulk es la mitad inversa de la carga: lo que salga de aquí tiene que poder reimportarse
 * tal cual (round-trip). Cada regla fijada abajo es un producto que, de romperse, se reimportaría
 * incompleto o directamente sería rechazado.
 */
class Cov10ProductBulkExportMapperTest {

    ProductBulkExportMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ProductBulkExportMapper();
    }

    @Test
    void unProductoPeladoNoRevientaYDevuelveListasVaciasNoNulos() {
        // El producto puede no tener categoría, proveedor, tramos ni reseñas: el export no puede fallar.
        ProductEntity p = ProductEntity.builder().externalId("1688-1").build();

        BulkProductDtoIn d = mapper.toBulk(p, null, null, null, null);

        assertThat(d.getCategorySlug()).isNull();
        assertThat(d.getSupplierExternalId()).isNull();
        assertThat(d.getStatus()).isNull();
        assertThat(d.getTranslations()).isNull();
        assertThat(d.getImageUrls()).isEmpty();
        assertThat(d.getTieredPricing()).isEmpty();
        assertThat(d.getVariantAxes()).isEmpty();
        assertThat(d.getVariants()).isEmpty();
        assertThat(d.getAttributes()).isEmpty();
        assertThat(d.getSpecifications()).isEmpty();
        assertThat(d.getReviews()).isEmpty();
    }

    @Test
    void elRecargoFijoSeExportaParaQueViajePorElBus() {
        // DROP-158: surcharge_cny es un componente del precio y tiene que viajar en el export
        // (el bus reimporta el producto en el otro entorno con el recargo igual).
        ProductEntity p = ProductEntity.builder().externalId("1688-1").build();
        p.setSurchargeCny(new java.math.BigDecimal("2.50"));

        BulkProductDtoIn d = mapper.toBulk(p, null, null, null, null);

        assertThat(d.getSurchargeCny()).isEqualByComparingTo("2.50");
    }

    @Test
    void categoriaProveedorYEstadoSalenPlanosParaPoderReimportarse() {
        ProductEntity p = ProductEntity.builder().externalId("1688-1").brand("Acme")
                .status(ProductStatus.ACTIVE)
                .category(CategoryEntity.builder().slug("moda-muj-01").externalId("cat-1688").build())
                .supplier(SupplierEntity.builder().externalId("sup-1688").name("Fábrica X").build()).build();

        BulkProductDtoIn d = mapper.toBulk(p, List.of(), List.of(), List.of(), List.of());

        assertThat(d.getCategorySlug()).isEqualTo("moda-muj-01");
        assertThat(d.getCategory1688Id()).isEqualTo("cat-1688");
        assertThat(d.getSupplierExternalId()).isEqualTo("sup-1688");
        assertThat(d.getSupplierName()).isEqualTo("Fábrica X");
        // El fabricante se guarda en `brand`; si no se exportara así, la reimportación lo perdería.
        assertThat(d.getManufacturer()).isEqualTo("Acme");
        assertThat(d.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void lasImagenesSalenEnElOrdenDeCargaYPrefierenLaUrlDeOrigen() {
        ProductEntity p = ProductEntity.builder().externalId("1688-1").build();
        p.getImages().add(imagen(2, "https://origen/2.jpg", "https://cdn/2.jpg"));
        p.getImages().add(imagen(0, "https://origen/0.jpg", null));
        // Sin sourceUrl (imagen añadida a mano) se cae a la cdn_url: si no, el producto se reimportaría
        // "sin imágenes" y el importador lo rechazaría.
        p.getImages().add(imagen(1, "   ", "https://cdn/1.jpg"));

        BulkProductDtoIn d = mapper.toBulk(p, List.of(), List.of(), List.of(), List.of());

        assertThat(d.getImageUrls()).containsExactly("https://origen/0.jpg", "https://cdn/1.jpg",
                "https://origen/2.jpg");
    }

    @Test
    void unaImagenSinNingunaUrlSeDescartaEnVezDeExportarUnNulo() {
        ProductEntity p = ProductEntity.builder().externalId("1688-1").build();
        p.getImages().add(imagen(0, null, null));
        p.getImages().add(imagen(1, "https://origen/1.jpg", null));

        BulkProductDtoIn d = mapper.toBulk(p, List.of(), List.of(), List.of(), List.of());

        assertThat(d.getImageUrls()).containsExactly("https://origen/1.jpg");
    }

    @Test
    void losTitulosFijosSalenDeLasTraduccionesSinImportarLaCajaDelIdioma() {
        ProductEntity p = ProductEntity.builder().externalId("1688-1").titleZh("中文").descriptionZh("描述").build();
        p.getTranslations().add(traduccion("ES", "Camisa", "Camisa de algodón"));
        p.getTranslations().add(traduccion("en", "Shirt", "Cotton shirt"));

        BulkProductDtoIn d = mapper.toBulk(p, List.of(), List.of(), List.of(), List.of());

        assertThat(d.getTitleEs()).isEqualTo("Camisa");
        assertThat(d.getDescriptionEs()).isEqualTo("Camisa de algodón");
        assertThat(d.getTitleEn()).isEqualTo("Shirt");
        assertThat(d.getTitlePt()).isNull();
        assertThat(d.getDescriptionPt()).isNull();
        assertThat(d.getTitleZh()).isEqualTo("中文");
        // El mapa ilimitado conserva el idioma tal cual está guardado (no normalizado).
        assertThat(d.getTranslations()).containsOnlyKeys("ES", "en");
        assertThat(d.getTranslations().get("en").getTitle()).isEqualTo("Shirt");
    }

    @Test
    void unaTraduccionSinIdiomaNoSePuedeExportarYSeIgnora() {
        ProductEntity p = ProductEntity.builder().externalId("1688-1").build();
        p.getTranslations().add(traduccion(null, "Huérfana", "Sin idioma"));

        BulkProductDtoIn d = mapper.toBulk(p, List.of(), List.of(), List.of(), List.of());

        assertThat(d.getTranslations()).isNull();
        assertThat(d.getTitleEs()).isNull();
    }

    @Test
    void losEjesSalenPorPosicionYCadaValorConSuFotoYSusTraducciones() {
        ProductEntity p = ProductEntity.builder().externalId("1688-1").build();
        VariantOptionEntity talla = eje(1, "Talla", "尺码");
        talla.getValues().add(valor(0, "M", "中", null));
        VariantOptionEntity color = eje(0, "Color", "颜色");
        VariantValueEntity blanco = valor(1, "Blanco", "白色", "https://origen/blanco.jpg");
        blanco.getTranslations().add(VariantValueTranslationEntity.builder().language("en").value("White").build());
        color.getValues().add(blanco);
        color.getValues().add(valor(0, "Negro", "黑色", null));
        p.getVariantOptions().add(talla);
        p.getVariantOptions().add(color);

        BulkProductDtoIn d = mapper.toBulk(p, List.of(), List.of(), List.of(), List.of());

        assertThat(d.getVariantAxes()).extracting(BulkAxis::getName).containsExactly("Color", "Talla");
        BulkAxis ejeColor = d.getVariantAxes().get(0);
        // El orden de los valores es el de 1688 (posición), no el de la colección en memoria.
        assertThat(ejeColor.getValues()).containsExactly("Negro", "Blanco");
        assertThat(ejeColor.getValueImages()).containsExactly(Map.entry("Blanco", "https://origen/blanco.jpg"));
        // Las traducciones se indexan por el valor CHINO: es la clave estable del proveedor.
        assertThat(ejeColor.getValueTranslations()).containsOnlyKeys("白色");
        assertThat(ejeColor.getValueTranslations().get("白色")).containsEntry("en", "White");
        assertThat(d.getVariantAxes().get(1).getValueImages()).isNull();
        assertThat(d.getVariantAxes().get(1).getValueTranslations()).isNull();
    }

    @Test
    void unEjeSinNombreTraducidoSeExportaConElNombreChino() {
        ProductEntity p = ProductEntity.builder().externalId("1688-1").build();
        VariantOptionEntity sinNombre = eje(0, null, "颜色");
        sinNombre.getValues().add(valor(0, null, "白色", null));
        p.getVariantOptions().add(sinNombre);

        BulkProductDtoIn d = mapper.toBulk(p, List.of(), List.of(), List.of(), List.of());

        assertThat(d.getVariantAxes().get(0).getName()).isEqualTo("颜色");
        assertThat(d.getVariantAxes().get(0).getValues()).containsExactly("白色");
    }

    @Test
    void unaTraduccionDeValorSinIdiomaNoSeExporta() {
        ProductEntity p = ProductEntity.builder().externalId("1688-1").build();
        VariantOptionEntity color = eje(0, "Color", "颜色");
        VariantValueEntity blanco = valor(0, "Blanco", "白色", null);
        blanco.getTranslations().add(VariantValueTranslationEntity.builder().language(null).value("White").build());
        color.getValues().add(blanco);
        p.getVariantOptions().add(color);

        BulkProductDtoIn d = mapper.toBulk(p, List.of(), List.of(), List.of(), List.of());

        assertThat(d.getVariantAxes().get(0).getValueTranslations()).isNull();
    }

    @Test
    void cadaVarianteConservaSuPrecioStockPesoYMedidas() {
        ProductEntity p = ProductEntity.builder().externalId("1688-1").build();
        p.getVariants().add(ProductVariantEntity.builder().sku("SKU-1")
                .options(Map.of("Color", "Blanco")).price(new BigDecimal("12.50")).stock(7)
                .imageSourceUrl("https://origen/blanco.jpg").supplierSkuId("sku-1688")
                .weightGrams(300).packageWeightGrams(420).lengthMm(30).widthMm(20).heightMm(5).build());

        BulkProductDtoIn d = mapper.toBulk(p, List.of(), List.of(), List.of(), List.of());

        BulkVariant v = d.getVariants().get(0);
        assertThat(v.getSku()).isEqualTo("SKU-1");
        // optionValues (no attributes): sin esta clave el producto reimportado sale "sin stock".
        assertThat(v.getOptionValues()).containsEntry("Color", "Blanco");
        assertThat(v.getPrice()).isEqualByComparingTo("12.50");
        assertThat(v.getStock()).isEqualTo(7);
        assertThat(v.getImageUrl()).isEqualTo("https://origen/blanco.jpg");
        assertThat(v.getSupplierSkuId()).isEqualTo("sku-1688");
        assertThat(v.getWeightGrams()).isEqualTo(300);
        assertThat(v.getPackageWeightGrams()).isEqualTo(420);
        assertThat(v.getLengthMm()).isEqualTo(30);
        assertThat(v.getWidthMm()).isEqualTo(20);
        assertThat(v.getHeightMm()).isEqualTo(5);
    }

    @Test
    void losTramosAtributosYFichaTecnicaSeExportanUnoAUno() {
        ProductEntity p = ProductEntity.builder().externalId("1688-1").build();
        ProductPriceTierEntity tier = ProductPriceTierEntity.builder().minQty(10).maxQty(49)
                .unitPrice(new BigDecimal("9.90")).currency("CNY").build();
        ProductAttributeEntity attr = ProductAttributeEntity.builder().attrKey("material").attrValue("algodón")
                .locale("es").build();
        ProductSpecificationEntity spec = ProductSpecificationEntity.builder().locale("es").specKey("Longitud")
                .specValue("30 cm").position(2).build();

        BulkProductDtoIn d = mapper.toBulk(p, List.of(attr), List.of(spec), List.of(tier), List.of());

        assertThat(d.getTieredPricing()).singleElement().satisfies(t -> {
            assertThat(t.getMinQty()).isEqualTo(10);
            assertThat(t.getMaxQty()).isEqualTo(49);
            assertThat(t.getUnitPrice()).isEqualByComparingTo("9.90");
            assertThat(t.getCurrency()).isEqualTo("CNY");
        });
        assertThat(d.getAttributes()).singleElement().satisfies(a -> {
            assertThat(a.getKey()).isEqualTo("material");
            assertThat(a.getValue()).isEqualTo("algodón");
            assertThat(a.getLocale()).isEqualTo("es");
        });
        assertThat(d.getSpecifications()).singleElement().satisfies(s -> {
            assertThat(s.getLocale()).isEqualTo("es");
            assertThat(s.getKey()).isEqualTo("Longitud");
            assertThat(s.getValue()).isEqualTo("30 cm");
            assertThat(s.getPosition()).isEqualTo(2);
        });
    }

    @Test
    void lasEtiquetasDeUnaResenaSeParteanPorComasYSeLimpian() {
        ProductEntity p = ProductEntity.builder().externalId("1688-1").build();
        ProductReviewEntity con = ProductReviewEntity.builder().authorName("Ana").authorCountry("ES").rating((short) 5)
                .title("Genial").body("Muy buena").language("es").verifiedPurchase(true)
                .tags(" calidad , , envío ").build();
        ProductReviewEntity sin = ProductReviewEntity.builder().authorName("Bob").rating((short) 4).tags("  ").build();

        BulkProductDtoIn d = mapper.toBulk(p, List.of(), List.of(), List.of(), List.of(con, sin));

        BulkReview primera = d.getReviews().get(0);
        assertThat(primera.getTags()).containsExactly("calidad", "envío");
        assertThat(primera.getRating()).isEqualTo(5);
        assertThat(primera.getVerifiedPurchase()).isTrue();
        // Sin etiquetas se exporta null, no una lista vacía: el JSON no se llena de ruido.
        assertThat(d.getReviews().get(1).getTags()).isNull();
    }

    @Test
    void losCamposDeLogisticaYAduanaViajanEnElExport() {
        ProductEntity p = ProductEntity.builder().externalId("1688-1").basePrice(new BigDecimal("20.00"))
                .shippingCny(new BigDecimal("10.00")).ivaCny(new BigDecimal("2.60")).moq(2).monthlySales(500)
                .rating(new BigDecimal("4.8")).weightGrams(300).packageWeightGrams(420).lengthMm(30).widthMm(20)
                .heightMm(5).countryOfOrigin("CN").hsCode("6104.43").certifications(List.of("CE"))
                .shipFrom("CN").leadTimeDays(3).videoUrl("https://v/1.mp4").videoUrls(List.of("https://v/2.mp4"))
                .salesRegions(List.of("EU")).ratingBreakdown(Map.of("5", 120))
                .crossBorderSupport(Map.of("boxMark", true)).dropshipShipped30d(900)
                .dropshipPickupRate48h(new BigDecimal("98.5")).build();

        BulkProductDtoIn d = mapper.toBulk(p, List.of(), List.of(), List.of(), List.of());

        assertThat(d.getPrice()).isEqualByComparingTo("20.00");
        assertThat(d.getShippingCny()).isEqualByComparingTo("10.00");
        assertThat(d.getIvaCny()).isEqualByComparingTo("2.60");
        assertThat(d.getMoq()).isEqualTo(2);
        assertThat(d.getMonthlySales()).isEqualTo(500);
        assertThat(d.getRating()).isEqualByComparingTo("4.8");
        assertThat(d.getWeightGrams()).isEqualTo(300);
        assertThat(d.getPackageWeightGrams()).isEqualTo(420);
        assertThat(d.getLengthMm()).isEqualTo(30);
        assertThat(d.getWidthMm()).isEqualTo(20);
        assertThat(d.getHeightMm()).isEqualTo(5);
        assertThat(d.getCountryOfOrigin()).isEqualTo("CN");
        assertThat(d.getHsCode()).isEqualTo("6104.43");
        assertThat(d.getCertifications()).containsExactly("CE");
        assertThat(d.getShipFrom()).isEqualTo("CN");
        assertThat(d.getLeadTimeDays()).isEqualTo(3);
        assertThat(d.getVideoUrl()).isEqualTo("https://v/1.mp4");
        assertThat(d.getVideoUrls()).containsExactly("https://v/2.mp4");
        assertThat(d.getSalesRegions()).containsExactly("EU");
        assertThat(d.getRatingBreakdown()).containsEntry("5", 120);
        assertThat(d.getCrossBorderSupport()).containsEntry("boxMark", true);
        assertThat(d.getDropshipShipped30d()).isEqualTo(900);
        assertThat(d.getDropshipPickupRate48h()).isEqualByComparingTo("98.5");
    }

    private static ProductImageEntity imagen(int position, String sourceUrl, String cdnUrl) {
        return ProductImageEntity.builder().position(position).sourceUrl(sourceUrl).cdnUrl(cdnUrl).build();
    }

    private static ProductTranslationEntity traduccion(String language, String title, String description) {
        return ProductTranslationEntity.builder().language(language).title(title).description(description).build();
    }

    private static VariantOptionEntity eje(int position, String name, String nameZh) {
        return VariantOptionEntity.builder().position(position).name(name).nameZh(nameZh)
                .values(new ArrayList<>()).build();
    }

    private static VariantValueEntity valor(int position, String value, String valueZh, String imageSourceUrl) {
        return VariantValueEntity.builder().position(position).value(value).valueZh(valueZh)
                .imageSourceUrl(imageSourceUrl).translations(new ArrayList<>()).build();
    }

    @Test
    void laTernaAduaneraLaBateriaYLaFichaDelProveedorSobrevivenAlExport() {
        // Estos cuatro campos NO se exportaban, y el export dice de sí mismo que sirve para reimportar.
        // Al volver a entrar, el material y el uso se repoblaban con los del PERFIL DE LA CATEGORÍA: la
        // ficha afinada a mano volvía al valor genérico. Y desde que la aduana agrupa por terna, eso
        // cambia CON QUIÉN comparte línea de declaración, es decir, cuántos derechos de 3 EUR se pagan.
        // La batería decide el canal del transportista, y sourceUrl es por dónde se compra al proveedor.
        ProductEntity p = ProductEntity.builder().externalId("1688-1")
                .customsMaterial("100% cotton").customsUsage("Casual wear").batteryType("BUILT_IN")
                .sourceUrl("https://detail.1688.com/offer/otra-cosa.html").build();

        BulkProductDtoIn d = mapper.toBulk(p, List.of(), List.of(), List.of(), List.of());

        assertThat(d.getCustomsMaterial()).isEqualTo("100% cotton");
        assertThat(d.getCustomsUsage()).isEqualTo("Casual wear");
        assertThat(d.getBatteryType()).isEqualTo("BUILT_IN");
        assertThat(d.getSourceUrl()).isEqualTo("https://detail.1688.com/offer/otra-cosa.html");
    }

    @Test
    void laCategoriaSaleConSuNombreChinoAdemasDelSlug() {
        // El slug solo sirve si el destino YA tiene esa categoría. El par (id, nombre) de 1688 es el
        // respaldo con el que el import puede resolverla en un entorno recién montado.
        ProductEntity p = ProductEntity.builder().externalId("1688-1")
                .category(CategoryEntity.builder().slug("moda-vestidos").externalId("126546700")
                        .nameZh("连衣裙").build())
                .build();

        BulkProductDtoIn d = mapper.toBulk(p, List.of(), List.of(), List.of(), List.of());

        assertThat(d.getCategorySlug()).isEqualTo("moda-vestidos");
        assertThat(d.getCategory1688Id()).isEqualTo("126546700");
        assertThat(d.getCategory1688Name()).isEqualTo("连衣裙");
    }
}
