package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkAttr;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkVariant;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkTier;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryAttributeSchemaEntity;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    /**
     * Recursos de la interfaz de Alibaba: iconos, insignias y sellos. Las fotos de producto viajan por
     * {@code .../img/ibank/O1CN...}; por {@code /tfs/} solo bajan piezas de la web de 1688.
     */
    private static final String RECURSOS_DE_INTERFAZ = "alicdn.com/tfs/";

    private BulkProductRules() {
    }

    /**
     * Si una dirección sirve como foto de producto.
     *
     * <p>Se colaban recursos de la interfaz del proveedor como una imagen más de la galería, y el
     * comprador acababa viendo una equis gris de 200x200 entre las fotos —364 productos en local y 586
     * en preproducción, siempre en la última posición—. No es un fallo de descarga: la imagen se espeja
     * perfectamente porque existe; lo que no es, es una foto.
     */
    public static boolean isProductPhoto(String url) {
        return url != null && !url.isBlank()
                && !url.toLowerCase(Locale.ROOT).contains(RECURSOS_DE_INTERFAZ);
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

    /**
     * URLs de imagen del producto, en orden y sin repetir.
     *
     * <p>Se aceptan varias claves de entrada porque los volcados de 1688 no son homogéneos: la lista
     * {@code imageUrls} (con sus alias) y el atajo {@code imageUrl}. Si a nivel de producto no hay
     * ninguna, se recurre a las de las variantes y a las de los valores del eje —hay productos cuya única
     * foto vive en el color—, antes que rechazar la fila.
     *
     * <p>El ORDEN es el de la fila y se conserva: es el mismo que tiene el producto en el proveedor, y la
     * ficha lo respeta. Se deduplica sin reordenar.
     *
     * @throws BusinessException si no hay ninguna imagen. Es una regla de calidad dura: un producto sin
     *         foto no se puede vender, así que vale más que la fila no entre a que entre vacía.
     */
    public static List<String> imageUrlsOf(BulkProductDtoIn r, String esTitle) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (r.getImageUrls() != null) {
            addNonBlank(urls, r.getImageUrls());
        }
        if (isProductPhoto(r.getImageUrl())) {
            urls.add(r.getImageUrl().trim());
        }
        if (urls.isEmpty()) {
            addVariantImages(urls, r);
        }
        if (urls.isEmpty()) {
            String who = r.getExternalId() != null && !r.getExternalId().isBlank() ? r.getExternalId() : esTitle;
            throw new BusinessException("El producto '" + who
                    + "' no tiene imágenes — indica al menos una en 'imageUrls' (también vale 'images' o el "
                    + "atajo 'imageUrl', o una imagen de variante).");
        }
        return List.copyOf(urls);
    }

    /** Respaldo: fotos de las variantes y de los valores del eje (el color suele traer la suya). */
    private static void addVariantImages(LinkedHashSet<String> urls, BulkProductDtoIn r) {
        if (r.getVariants() != null) {
            for (BulkVariant v : r.getVariants()) {
                if (isProductPhoto(v.getImageUrl())) {
                    urls.add(v.getImageUrl().trim());
                }
            }
        }
        if (r.getVariantAxes() != null) {
            for (BulkProductDtoIn.BulkAxis ax : r.getVariantAxes()) {
                if (ax.getValueImages() != null) {
                    addNonBlank(urls, ax.getValueImages().values());
                }
            }
        }
    }

    /**
     * Las imágenes de la DESCRIPCIÓN, limpias y sin repetir la galería.
     *
     * <p>Son las fotos largas que el proveedor monta debajo de la ficha. Se guardan como filas de
     * {@code product_image} con {@code role = "DETAIL"}, aparte de la galería, porque el escaparate
     * las pinta en su propia galería horizontal dentro de la sección de detalle: mezclarlas en el
     * carrusel principal lo llenaría de carteles en chino.
     *
     * <p><b>NO se quitan las que también están en la galería, y es una corrección del 12-sep-2026.</b>
     * La primera versión lo hacía —parecía evidente: para qué enseñar dos veces la misma foto— y el
     * efecto medido sobre el producto 1031738929572 fue el contrario: de las NUEVE imágenes de la
     * descripción, CINCO eran las mismas del estudio que la galería. El filtro se llevó esas cinco y
     * dejó las cuatro exclusivas, que eran tres carteles del proveedor y una foto. La sección de
     * detalle quedaba sin contenido útil y llena de marketing.
     *
     * <p>En 1688 la descripción REPITE fotos de la galería a propósito, en otro orden y con otro
     * contexto. Y no van al mismo sitio: el carrusel es una galería y el detalle es otra, así que
     * repetirlas no molesta. Distinto es la foto de VARIANTE, que sí entra en el carrusel y ahí sí se
     * deduplica.
     *
     * <p>A diferencia de {@link #imageUrlsOf}, no exige que haya ninguna: un producto sin descripción
     * ilustrada es lo normal —la mayoría de los ya cargados no la tienen— y no es motivo de rechazo.
     */
    public static List<String> detailImageUrlsOf(BulkProductDtoIn r) {
        if (r.getDetailImageUrls() == null || r.getDetailImageUrls().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> detalle = new LinkedHashSet<>();
        addNonBlank(detalle, r.getDetailImageUrls());
        return List.copyOf(detalle);
    }

    private static void addNonBlank(LinkedHashSet<String> target, Iterable<String> source) {
        for (String u : source) {
            if (isProductPhoto(u)) {
                target.add(u.trim());
            }
        }
    }
}
