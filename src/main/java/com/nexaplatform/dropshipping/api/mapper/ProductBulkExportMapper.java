package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkAttr;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkAxis;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkReview;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkSpec;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkTier;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkTranslation;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkVariant;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductSpecificationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueTranslationEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Inverse of the bulk import: maps a persisted {@link ProductEntity} (with its sub-entities) back to a
 * {@link BulkProductDtoIn}, so the exported JSON has the exact same shape used to create products and can
 * be re-imported (round-trip). Must be invoked inside a read-only transaction — it walks lazy collections.
 */
@Component
public class ProductBulkExportMapper {

    public BulkProductDtoIn toBulk(ProductEntity p, List<ProductAttributeEntity> attributes,
            List<ProductSpecificationEntity> specifications, List<ProductPriceTierEntity> tiers,
            List<ProductReviewEntity> reviews) {
        BulkProductDtoIn d = new BulkProductDtoIn();

        if (p.getCategory() != null) {
            d.setCategorySlug(p.getCategory().getSlug());
        }
        // El par de 1688 sale del PRODUCTO (v170), no de nuestra categoría: es el rastro del origen y
        // tiene que cruzar al destino, o allí la columna queda vacía y auditar dónde estaba en 1688
        // vuelve a ser imposible. De paso arregla el respaldo: el destino resuelve por
        // `category_1688_mapping`, que está tecleada por las hojas REALES de 1688, así que mandarle el
        // `externalId` y el `nameZh` de una categoría nuestra casi nunca encontraba nada.
        d.setCategory1688Id(p.getCategory1688Id() != null
                ? p.getCategory1688Id()
                : (p.getCategory() != null ? p.getCategory().getExternalId() : null));
        d.setCategory1688Name(p.getCategory1688Name() != null
                ? p.getCategory1688Name()
                : (p.getCategory() != null ? p.getCategory().getNameZh() : null));
        if (p.getSupplier() != null) {
            d.setSupplierExternalId(p.getSupplier().getExternalId());
            d.setSupplierName(p.getSupplier().getName());
        }
        d.setManufacturer(p.getBrand());
        d.setExternalId(p.getExternalId());
        d.setStatus(p.getStatus() != null ? p.getStatus().name() : null);
        // Viaja al bus: sin esto el producto llega a producción certificado pero marcado como no
        // verificado, y el panel de allí dice «sin verificar» de algo que sí lo está.
        d.setVerified(p.getVerified());

        // Canonical fixed-language fields (the unlimited `translations` map below carries every language).
        d.setTitleZh(p.getTitleZh());
        d.setDescriptionZh(p.getDescriptionZh());
        Map<String, ProductTranslationEntity> byLang = new LinkedHashMap<>();
        for (ProductTranslationEntity t : safe(p.getTranslations())) {
            if (t.getLanguage() != null) {
                byLang.put(t.getLanguage().toLowerCase(), t);
            }
        }
        d.setTitleEs(title(byLang, "es"));
        d.setTitleEn(title(byLang, "en"));
        d.setTitlePt(title(byLang, "pt"));
        d.setDescriptionEs(desc(byLang, "es"));
        d.setDescriptionEn(desc(byLang, "en"));
        d.setDescriptionPt(desc(byLang, "pt"));

        d.setPrice(p.getBasePrice());
        d.setShippingCny(p.getShippingCny());
        d.setMargenInternoPct(p.getMargenInternoPct());
        // El recargo (surcharge_pct) viaja en el export igual que el envío y el margen interno: es un
        // componente del precio y el destino (el otro entorno por el bus) tiene que quedárselo igual.
        d.setSurchargePct(p.getSurchargePct());
        // Las bolsas de subvención viajan en el export igual que el recargo: son componentes de lo que
        // paga el cliente y el destino tiene que quedárselas iguales.
        d.setShippingUserCny(p.getShippingUserCny());
        d.setDutyUserCny(p.getDutyUserCny());
        d.setMoq(p.getMoq());
        d.setMonthlySales(p.getMonthlySales());
        d.setRating(p.getRating());

        // imageUrls del export: se prefiere la URL de origen, pero si falta (p.ej. imagen añadida solo
        // con cdn_url) se cae a la cdn_url espejada. Así reexportar→reimportar conserva las imágenes y
        // el producto no se rechaza por "sin imágenes" en el round-trip.
        // La galería y las fotos de la DESCRIPCIÓN salen en campos distintos, y esto no es cosmético:
        // este DTO es el que viaja en el evento del bus, o sea LO QUE CRUZA DE PRE A PRO. Exportarlas
        // mezcladas haría que al reimportarlas en producción entraran como galería, y aparecería un
        // cartel en chino dentro del carrusel de la ficha.
        d.setImageUrls(direccionesDeImagen(p, false));
        d.setDetailImageUrls(direccionesDeImagen(p, true));

        // Logistics / customs (direct columns).
        d.setWeightGrams(p.getWeightGrams());
        d.setPackageWeightGrams(p.getPackageWeightGrams());
        d.setLengthMm(p.getLengthMm());
        d.setWidthMm(p.getWidthMm());
        d.setHeightMm(p.getHeightMm());
        d.setCountryOfOrigin(p.getCountryOfOrigin());
        d.setHsCode(p.getHsCode());
        // La TERNA aduanera completa, no solo la partida. Estos dos campos no se exportaban, y al
        // reimportar se repoblaban con los del perfil de la CATEGORÍA: un producto con material o uso
        // propios —afinados a mano— volvía silenciosamente al valor genérico. Y desde la agrupación de
        // líneas de declaración eso además cambia CON QUIÉN agrupa en la aduana, es decir, cuántos
        // derechos de 3 EUR se pagan.
        d.setCustomsMaterial(p.getCustomsMaterial());
        d.setCustomsUsage(p.getCustomsUsage());
        // Decide el PackageType del transportista y, con él, el canal y la tarifa. Sin exportarlo, un
        // producto con batería se reimportaba como si no la llevara.
        d.setBatteryType(p.getBatteryType());
        // La ficha real del proveedor. Sin ella se regenera a partir del externalId, y para lo que no
        // venga de 1688 esa URL inventada no lleva a ninguna parte: es por donde se va a comprar.
        d.setSourceUrl(p.getSourceUrl());
        d.setCertifications(p.getCertifications());
        d.setShipFrom(p.getShipFrom());
        d.setLeadTimeDays(p.getLeadTimeDays());
        d.setVideoUrl(p.getVideoUrl());
        d.setVideoUrls(p.getVideoUrls());
        d.setSalesRegions(p.getSalesRegions());
        d.setRatingBreakdown(p.getRatingBreakdown());
        d.setCrossBorderSupport(p.getCrossBorderSupport());
        d.setDropshipShipped30d(p.getDropshipShipped30d());
        d.setDropshipPickupRate48h(p.getDropshipPickupRate48h());
        d.setRepurchaseRate(p.getRepurchaseRate());
        d.setReviewsSummary(p.getReviewsSummary());

        d.setTieredPricing(safe(tiers).stream().map(
                t -> new BulkTier(t.getMinQty(), t.getMaxQty(), t.getUnitPrice(), t.getCurrency(), t.getSurchargePct()))
                .toList());

        d.setVariantAxes(safe(p.getVariantOptions()).stream()
                .sorted(Comparator.comparingInt(VariantOptionEntity::getPosition)).map(this::axis).toList());
        d.setVariants(safe(p.getVariants()).stream().map(this::variant).toList());

        d.setAttributes(safe(attributes).stream()
                .map(a -> new BulkAttr(a.getAttrKey(), a.getAttrValue(), a.getLocale())).toList());
        d.setSpecifications(safe(specifications).stream()
                .map(s -> new BulkSpec(s.getLocale(), s.getSpecKey(), s.getSpecValue(), s.getPosition())).toList());

        // Unlimited translations map (every stored language, including es/en/pt/zh).
        Map<String, BulkTranslation> translations = new LinkedHashMap<>();
        for (ProductTranslationEntity t : safe(p.getTranslations())) {
            if (t.getLanguage() != null) {
                translations.put(t.getLanguage(),
                        new BulkTranslation(t.getTitle(), t.getShortDescription(), t.getDescription()));
            }
        }
        d.setTranslations(translations.isEmpty() ? null : translations);

        d.setReviews(safe(reviews).stream().map(this::review).toList());
        return d;
    }

    private BulkAxis axis(VariantOptionEntity o) {
        List<String> values = new ArrayList<>();
        Map<String, String> valueImages = new LinkedHashMap<>();
        Map<String, Map<String, String>> valueTranslations = new LinkedHashMap<>();
        for (VariantValueEntity v : sortedValues(o)) {
            String value = displayValue(v);
            values.add(value);
            if (v.getImageSourceUrl() != null && value != null) {
                valueImages.put(value, v.getImageSourceUrl());
            }
            // El mapa de traducciones se indexa por el valor en CHINO, que es la clave estable del
            // proveedor y la que usa el importador para reencontrarlo al reimportar.
            String key = v.getValueZh() != null ? v.getValueZh() : value;
            Map<String, String> tr = translationsByLanguage(v);
            if (!tr.isEmpty() && key != null) {
                valueTranslations.put(key, tr);
            }
        }
        // Los mapas vacíos se exportan como ausentes para que el JSON no se llene de objetos vacíos.
        return new BulkAxis(o.getName() != null ? o.getName() : o.getNameZh(), values,
                valueImages.isEmpty() ? null : valueImages, valueTranslations.isEmpty() ? null : valueTranslations);
    }

    /** Valores del eje en el orden de carga (posición), que es el mismo orden en el que están en 1688. */
    private List<VariantValueEntity> sortedValues(VariantOptionEntity o) {
        return safe(o.getValues()).stream().sorted(Comparator.comparingInt(VariantValueEntity::getPosition)).toList();
    }

    /** Texto mostrable del valor: el traducido si lo hay, si no el chino original. */
    private static String displayValue(VariantValueEntity v) {
        return v.getValue() != null ? v.getValue() : v.getValueZh();
    }

    /** Traducciones del valor indexadas por idioma; las que no declaran idioma no se pueden exportar. */
    private Map<String, String> translationsByLanguage(VariantValueEntity v) {
        Map<String, String> tr = new LinkedHashMap<>();
        for (VariantValueTranslationEntity t : safe(v.getTranslations())) {
            if (t.getLanguage() != null) {
                tr.put(t.getLanguage(), t.getValue());
            }
        }
        return tr;
    }

    private BulkVariant variant(ProductVariantEntity v) {
        BulkVariant b = new BulkVariant();
        b.setSku(v.getSku());
        b.setOptionValues(v.getOptions());
        b.setPrice(v.getPrice());
        b.setStock(v.getStock());
        b.setImageUrl(v.getImageSourceUrl());
        b.setSupplierSkuId(v.getSupplierSkuId());
        b.setWeightGrams(v.getWeightGrams());
        b.setPackageWeightGrams(v.getPackageWeightGrams());
        b.setLengthMm(v.getLengthMm());
        b.setWidthMm(v.getWidthMm());
        b.setHeightMm(v.getHeightMm());
        // El envío nacional de esta variante. Sin esta línea el volcado que el backend produce
        // —el mismo formato que importa, y lo que se vuelve a subir— pierde el importe en cada
        // ida y vuelta, en silencio. El dato sólo se echaría de menos al llegar la factura del
        // transportista.
        b.setShippingCny(v.getShippingCny());
        return b;
    }

    private BulkReview review(ProductReviewEntity r) {
        List<String> tags = (r.getTags() == null || r.getTags().isBlank())
                ? null
                : Arrays.stream(r.getTags().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new BulkReview(r.getAuthorName(), r.getAuthorCountry(), (int) r.getRating(), r.getTitle(), r.getBody(),
                r.getLanguage(), r.isVerifiedPurchase(), tags);
    }

    private String title(Map<String, ProductTranslationEntity> byLang, String lang) {
        ProductTranslationEntity t = byLang.get(lang);
        return t != null ? t.getTitle() : null;
    }

    private String desc(Map<String, ProductTranslationEntity> byLang, String lang) {
        ProductTranslationEntity t = byLang.get(lang);
        return t != null ? t.getDescription() : null;
    }

    private <T> List<T> safe(List<T> list) {
        return list != null ? list : List.of();
    }

    /**
     * Las direcciones de imagen del producto, separando la galería de las de la descripción.
     *
     * <p>Se prefiere la {@code source_url} de origen y solo se cae a la {@code cdn_url} espejada
     * cuando falta —una imagen añadida a mano no tiene origen—, para que reexportar e reimportar
     * conserve las imágenes y el producto no se rechace por «sin imágenes» en el viaje de vuelta.
     *
     * @param deDetalle {@code true} para las de la descripción ({@code role = DETAIL}), {@code false}
     *                  para la galería (todo lo demás: MAIN y GALLERY).
     */
    private List<String> direccionesDeImagen(ProductEntity p, boolean deDetalle) {
        return safe(p.getImages()).stream().filter(img -> "DETAIL".equalsIgnoreCase(img.getRole()) == deDetalle)
                .sorted(Comparator.comparingInt(ProductImageEntity::getPosition))
                .map(img -> img.getSourceUrl() != null && !img.getSourceUrl().isBlank()
                        ? img.getSourceUrl()
                        : img.getCdnUrl())
                .filter(Objects::nonNull).toList();
    }
}
