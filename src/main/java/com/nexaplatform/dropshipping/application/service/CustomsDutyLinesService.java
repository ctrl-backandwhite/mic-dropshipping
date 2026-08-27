package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.CarrierChannelLimitService.ChannelLimit;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Cuenta las <b>partidas arancelarias</b> de cada bulto — la base sobre la que se cobra el derecho
 * temporal de 3 EUR de la Unión Europea.
 *
 * <p><b>La regla, tal y como está escrita.</b> El Reglamento (UE) 2026/382 fija, del 1 de julio de 2026 al
 * 1 de julio de 2028, un derecho de 3 EUR «per item in a consignment the intrinsic value of which does not
 * exceed a total of EUR 150». Y «item» no significa «unidad» ni «producto»: el artículo 1(61) del Reglamento
 * Delegado (UE) 2015/2446 lo define como <i>«one or more goods in a consignment sharing the same tariff
 * classification, description and, if provided…, origin»</i>. La guía de la Comisión lo remata: el derecho
 * «will automatically apply <b>per declaration line</b> irrespective of the quantity (number of the
 * articles) in that declaration line».
 *
 * <p>De ahí las tres consecuencias que implementa esta clase:
 * <ol>
 *   <li><b>La cantidad no multiplica.</b> Cinco unidades de la misma referencia son UNA línea: 3 EUR.</li>
 *   <li><b>Lo que agrupa es la TERNA completa</b>, no solo la partida: clasificación, descripción y origen.
 *       Dos productos distintos que comparten subpartida <i>y</i> se declaran con la misma descripción son
 *       una sola línea —el ejemplo oficial del anorak, el cortavientos y la cazadora bajo la 6104 19, que
 *       pagan 3 EUR y no 9—; si cada uno viaja con SU descripción, son líneas distintas y pagan una cada
 *       uno, porque así es como se transmiten y así es como las cuenta la aduana.</li>
 *   <li><b>Se cuenta por envío, no por pedido.</b> Un «consignment» es lo que ampara un mismo contrato de
 *       transporte; cada bulto lleva su propia declaración. Por eso se reparte primero la mercancía en
 *       bultos con el MISMO criterio que usará el transportista ({@link ParcelSplitter}) y se cuentan las
 *       líneas dentro de cada uno. El límite de 150 EUR también se mide bulto a bulto.</li>
 * </ol>
 *
 * <p><b>Nivel de la clasificación.</b> Se agrupa por los 6 primeros dígitos (subpartida del Sistema
 * Armonizado), que es lo que se declara en el H7 —la declaración reducida de los envíos de bajo valor con
 * IOSS, que es nuestro caso—. Si algún día se declarase en H1 habría que afinar a 10 dígitos TARIC.
 *
 * <p><b>Por qué la descripción cuenta.</b> Porque la declaración que de verdad se transmite
 * ({@code declaration_info[]} de YunExpress) lleva el título del producto en cada línea: dos artículos de la
 * misma partida con títulos distintos salen como DOS líneas en la guía. Contarlos aquí como una sola dejaba
 * sin cobrar el derecho de la segunda, y esos 3 EUR los ponía el comercio al despachar.
 *
 * <p><b>Sin clasificación no se agrupa.</b> Un producto sin código HS cuenta como línea propia: se cobra de
 * más en el peor caso, nunca de menos. Agruparlo con otros sería atribuirle una clasificación que nadie ha
 * verificado, y responder de ella ante la aduana es del declarante.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomsDutyLinesService {

    /**
     * Tope de unidades que se expanden para repartir en bultos. Muy por encima de cualquier pedido real
     * (el carrito limita la cantidad por línea) y suficientemente bajo para que una cantidad inventada no
     * pueda agotar la memoria del proceso.
     */
    private static final int MAX_UNITS = 10_000;

    /**
     * De dónde sale el peso máximo por bulto: del canal y del país, no de un escalar.
     *
     * <p>Puede llegar nulo en pruebas unitarias que no tocan la tabla —igual que {@code Environment} en
     * {@link com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService}—,
     * y entonces se usa la configuración global. Es el mismo último recurso que aplica el resolutor, así
     * que el resultado no cambia por prescindir de él.
     */
    private final CarrierChannelLimitService channelLimits;

    /**
     * Una línea del carrito o del pedido, con lo que hace falta para clasificarla y para pesarla.
     *
     * <p>{@code description} y {@code originCountry} no son adorno: son, junto a la partida, lo que
     * distingue una línea de declaración de otra (ver {@link #classificationKey(Line)}). Van aquí y no se
     * deducen dentro del servicio para que quien construye la línea sea quien garantice que coinciden con
     * lo que se le va a transmitir al transportista.
     */
    public record Line(UUID productId, String hsCode, String description, String originCountry, int quantity,
                       int unitPriceCents, int unitWeightGrams, int lengthMm, int widthMm, int heightMm,
                       boolean withBattery) {
    }

    /** Un bulto ya formado: lo que declara y cuántas partidas arancelarias distintas contiene. */
    public record DutyParcel(int valueCents, int tariffLines) {
    }

    @Value("${nexadrop.yunexpress.max-parcel-weight-grams:0}")
    private int maxParcelWeightGrams;
    @Value("${nexadrop.yunexpress.max-parcel-value-cents:0}")
    private int maxParcelValueCents;
    @Value("${nexadrop.yunexpress.max-parcel-units:0}")
    private int maxParcelUnits;

    /**
     * Reparte las líneas en bultos sin saber por qué canal van a viajar: se usa el peso máximo de la
     * configuración global. Solo para llamantes que de verdad no conocen el destino ni el canal.
     */
    public List<DutyParcel> parcelsOf(List<Line> lines) {
        return parcelsOf(lines, null, null);
    }

    /**
     * Reparte las líneas en bultos y devuelve, por bulto, su valor y su número de partidas arancelarias.
     *
     * <p>El reparto tiene que ser el MISMO que hará el transportista, porque el derecho fijo de la UE se
     * cobra por línea de declaración <i>dentro de cada bulto</i>: contar con dos bultos lo que va a viajar
     * en tres cobra de menos, y la diferencia la pone el comercio al despachar. Por eso el peso máximo
     * sale de {@code (canal, país)} y no de un escalar: la línea de ropa admite 30 kg a España y 15 kg a
     * Dinamarca, así que el mismo carrito se parte distinto según a dónde vaya.
     *
     * <p>Si no hay nada que declarar devuelve la lista vacía: sin mercancía no hay envío ni derecho.
     *
     * @param channelCode canal del transportista; vacío = todavía no se sabe, se usa la configuración
     * @param countryCode país de destino (ISO-2)
     */
    public List<DutyParcel> parcelsOf(List<Line> lines, String channelCode, String countryCode) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<ParcelSplitter.Unit> units = new ArrayList<>();
        int restantes = MAX_UNITS;
        for (int i = 0; i < lines.size() && restantes > 0; i++) {
            Line l = lines.get(i);
            int weight = l.unitWeightGrams() > 0 ? l.unitWeightGrams() : 500;
            // La cantidad se acota ANTES de expandirla: repartir en bultos exige una unidad en memoria por
            // artículo, así que un "quantity" disparatado (el carrito ya lo rechaza, pero este cálculo no
            // puede ser quien reviente) se convertía en un OutOfMemoryError, es decir, en tirar el checkout
            // entero con una sola petición.
            int cantidad = Math.min(Math.max(1, l.quantity()), restantes);
            restantes -= cantidad;
            for (int q = 0; q < cantidad; q++) {
                units.add(new ParcelSplitter.Unit(i, weight, l.unitPriceCents(), l.lengthMm(), l.widthMm(),
                        l.heightMm(), l.withBattery()));
            }
        }
        List<ParcelSplitter.Bin> bins = ParcelSplitter.split(units,
                new ParcelSplitter.Limits(maxWeightGramsFor(channelCode, countryCode), maxParcelValueCents,
                        maxParcelUnits));

        List<DutyParcel> parcels = new ArrayList<>(bins.size());
        for (ParcelSplitter.Bin bin : bins) {
            parcels.add(new DutyParcel(bin.valueCents(), tariffLinesIn(bin, lines)));
        }
        if (log.isDebugEnabled()) {
            // Por qué un pedido paga las líneas que paga: es la cuenta que acaba en la factura del
            // cliente, y sin verla desglosada solo se puede especular sobre ella.
            for (int b = 0; b < bins.size(); b++) {
                Set<String> claves = new LinkedHashSet<>();
                for (int i = 0; i < lines.size(); i++) {
                    if (bins.get(b).quantityOfLine(i) > 0) {
                        claves.add(classificationKey(lines.get(i)));
                    }
                }
                log.debug("Bulto {}/{}: {} unidades, {} línea(s) de declaración -> {}", b + 1, bins.size(),
                        bins.get(b).units().size(), claves.size(), claves);
            }
        }
        return parcels;
    }

    /**
     * Peso máximo por bulto de ese canal en ese destino, con la configuración global como último recurso
     * (canal sin sembrar, o resolutor ausente en pruebas unitarias).
     */
    private int maxWeightGramsFor(String channelCode, String countryCode) {
        if (channelLimits == null) {
            return maxParcelWeightGrams;
        }
        ChannelLimit limite = channelLimits.resolve(channelCode, countryCode);
        return limite.maxWeightGrams();
    }

    /** Partidas distintas dentro de un bulto: las clasificaciones que de verdad viajan en ÉL, no en el pedido. */
    private static int tariffLinesIn(ParcelSplitter.Bin bin, List<Line> lines) {
        Set<String> claves = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            if (bin.quantityOfLine(i) <= 0) {
                continue;
            }
            claves.add(classificationKey(lines.get(i)));
        }
        return claves.size();
    }

    /**
     * Clave por la que se agrupan dos mercancías en la misma línea de declaración: subpartida del SA +
     * descripción + país de origen, que es <b>exactamente</b> la terna del art. 1(61) del Reglamento
     * Delegado (UE) 2015/2446 y, sobre todo, la terna con la que el transportista arma cada línea de
     * {@code declaration_info[]}.
     *
     * <p>Antes se agrupaba solo por la subpartida. Dos productos con la misma partida y distinta
     * descripción salían como UNA línea aquí y como DOS en la declaración transmitida: el derecho de esa
     * segunda línea no se le cobraba a nadie y lo acababa poniendo el comercio al despachar.
     *
     * <p>Sin código HS la clave es el propio producto (no se agrupa con nadie). Y si NO hay descripción no
     * se separa por ella: sin dato no hay nada que permita afirmar que dos mercancías de la misma partida
     * se declaran por separado, y inventarse la separación cobraría 3 EUR de más al cliente. En el flujo
     * real la descripción siempre existe —el transportista rechaza la guía sin {@code EName}—, así que esa
     * rama solo cubre catálogo incompleto.
     */
    private static String classificationKey(Line line) {
        String hs = line.hsCode() == null ? "" : line.hsCode().replaceAll("[^0-9]", "");
        if (hs.length() < 6) {
            return "SIN-HS:" + line.productId();
        }
        return hs.substring(0, 6) + "|" + normalize(line.description()) + "|" + normalize(line.originCountry());
    }

    /**
     * Deja el texto comparable: sin espacios de sobra y sin distinguir mayúsculas. Un «Cotton T-Shirt» y un
     * «cotton  t-shirt» son la misma mercancía descrita por dos personas distintas, y cobrar dos derechos
     * por una diferencia de tecleo sería cobrar de más.
     */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /**
     * Descripción con la que el producto viajará en la declaración: su título en inglés, que es el
     * {@code EName} que se le transmite al transportista.
     *
     * <p>Vive aquí, y no en cada llamante, porque la vista previa del checkout, el pedido y el despacho
     * TIENEN que contar las mismas líneas: si uno resolviera el título de otra forma, el cliente vería un
     * importe y se le cobraría otro. Todos leen el mismo producto, así que todos obtienen el mismo texto.
     */
    public static String declaredDescriptionOf(ProductEntity product) {
        if (product == null || product.getTranslations() == null) {
            return null;
        }
        return product.getTranslations().stream()
                .filter(t -> "en".equalsIgnoreCase(t.getLanguage()))
                .map(ProductTranslationEntity::getTitle)
                .filter(t -> t != null && !t.isBlank())
                .findFirst().orElse(null);
    }
}
