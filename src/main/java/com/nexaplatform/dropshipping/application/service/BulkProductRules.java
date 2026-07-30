package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkAttr;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkTier;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryAttributeSchemaEntity;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reglas de calidad que una fila tiene que cumplir para entrar en el catálogo.
 *
 * <p>Estaban repartidas por las 255 líneas del importador, mezcladas con la construcción del producto.
 * Reunidas aquí se leen de un tirón, se prueban sin montar media aplicación y —lo importante— se ve que
 * son las mismas que el manual de carga exige a mano: un producto sin precio real, sin envío, sin IVA o
 * sin imagen no se sube.
 *
 * <p>Todas rechazan con un mensaje que dice QUÉ falta y de QUÉ producto: el importador procesa lotes de
 * cientos de filas y acumula los errores, así que un "dato inválido" a secas obliga a revisar el lote
 * entero para dar con la fila mala.
 */
public final class BulkProductRules {

    /** El identificador externo se guarda en un varchar(120). */
    public static final int MAX_EXTERNAL_ID = 120;

    /** Deja hueco para el prefijo "BULK-" y el sufijo de tiempo dentro del límite de la columna. */
    public static final int MAX_SLUG_BASE = 90;

    private BulkProductRules() {
    }

    /**
     * La categoría puede exigir atributos (talla, material...). Si los declara obligatorios, la fila
     * tiene que traerlos con valor: importar sin ellos deja fichas incompletas que luego hay que
     * repasar producto a producto.
     */
    public static void assertRequiredAttributes(BulkProductDtoIn r, List<CategoryAttributeSchemaEntity> schema,
            String categorySlug) {
        List<String> required = schema.stream()
                .filter(CategoryAttributeSchemaEntity::isRequired)
                .map(CategoryAttributeSchemaEntity::getAttrKey)
                .toList();
        if (required.isEmpty()) {
            return;
        }
        Set<String> provided = new HashSet<>();
        if (r.getAttributes() != null) {
            for (BulkAttr a : r.getAttributes()) {
                if (a.getKey() != null && a.getValue() != null && !a.getValue().isBlank()) {
                    provided.add(a.getKey().trim().toLowerCase());
                }
            }
        }
        for (String key : required) {
            if (!provided.contains(key.toLowerCase())) {
                throw new BusinessException(
                        "Falta el atributo obligatorio '" + key + "' para la categoría " + categorySlug);
            }
        }
    }

    /** Un producto sin título en ningún idioma no se puede ni nombrar en el escaparate. */
    public static void assertTitle(String esTitle) {
        if (esTitle == null || esTitle.isBlank()) {
            throw new BusinessException("Falta el título del producto en al menos un idioma (titleEs o translations).");
        }
    }

    /**
     * Precio real de proveedor. NO se inventa: si no viene explícito se toma del tramo más bajo (un
     * precio de verdad, el de mayor cantidad), y si tampoco hay tramos se rechaza la fila. Un producto
     * con precio inventado se vendería con el margen mal calculado.
     */
    public static BigDecimal resolvePrice(BulkProductDtoIn r, String esTitle) {
        BigDecimal price = r.getPrice();
        if (price == null && r.getTieredPricing() != null) {
            for (BulkTier t : r.getTieredPricing()) {
                if (t.getUnitPrice() != null && (price == null || t.getUnitPrice().compareTo(price) < 0)) {
                    price = t.getUnitPrice();
                }
            }
        }
        if (price == null) {
            throw new BusinessException("Falta el precio real del producto (price o tieredPricing): " + esTitle);
        }
        return price;
    }

    /**
     * Envío e IVA en yuanes son obligatorios. El total que paga el cliente es base×margen + IVA + envío,
     * así que sin ellos el producto se vendería por debajo de coste sin que nadie lo note.
     */
    public static void assertShippingAndVat(BulkProductDtoIn r, String esTitle) {
        if (r.getShippingCny() == null) {
            throw new BusinessException("Falta el envío (shippingCny) del producto: " + esTitle);
        }
        if (r.getIvaCny() == null) {
            throw new BusinessException("Falta el IVA (ivaCny) del producto: " + esTitle);
        }
    }

    /**
     * Identificador externo con el que el importador hace UPSERT: reimportar la misma fila ACTUALIZA el
     * producto en su sitio en vez de duplicarlo, y así se conservan sus favoritos y sus pedidos. Si la
     * fila no trae uno, se deriva del título; en cualquier caso se capa a lo que admite la columna,
     * porque un título largo desbordaba el varchar(120) y tumbaba la importación.
     */
    public static String externalIdOf(BulkProductDtoIn r, String esTitle, java.util.function.
            UnaryOperator<String> slugify, long uniqueSuffix) {
        String externalId;
        if (r.getExternalId() != null && !r.getExternalId().isBlank()) {
            externalId = r.getExternalId().trim();
        } else {
            String base = slugify.apply(esTitle);
            if (base.length() > MAX_SLUG_BASE) {
                base = base.substring(0, MAX_SLUG_BASE);
            }
            externalId = "BULK-" + base + "-" + uniqueSuffix;
        }
        return externalId.length() > MAX_EXTERNAL_ID ? externalId.substring(0, MAX_EXTERNAL_ID) : externalId;
    }
}
