package com.nexaplatform.dropshipping.application.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 *   <li><b>Productos distintos con la misma clasificación son UNA línea.</b> El ejemplo oficial es
 *       explícito: un anorak, un cortavientos y una cazadora que comparten la subpartida 6104 19 pagan
 *       3 EUR en total, no 9. Antes se contaban productos distintos, y ahí es donde se cobraba de más.</li>
 *   <li><b>Se cuenta por envío, no por pedido.</b> Un «consignment» es lo que ampara un mismo contrato de
 *       transporte; cada bulto lleva su propia declaración. Por eso se reparte primero la mercancía en
 *       bultos con el MISMO criterio que usará el transportista ({@link ParcelSplitter}) y se cuentan las
 *       líneas dentro de cada uno. El límite de 150 EUR también se mide bulto a bulto.</li>
 * </ol>
 *
 * <p><b>Nivel de la clasificación.</b> Se agrupa por los 6 primeros dígitos (subpartida del Sistema
 * Armonizado), que es lo que se declara en el H7 —la declaración reducida de los envíos de bajo valor con
 * IOSS, que es nuestro caso—. Si algún día se declarase en H1 habría que afinar a 10 dígitos TARIC y separar
 * además por país de origen, porque allí cada combinación es una línea distinta.
 *
 * <p><b>Sin clasificación no se agrupa.</b> Un producto sin código HS cuenta como línea propia: se cobra de
 * más en el peor caso, nunca de menos. Agruparlo con otros sería atribuirle una clasificación que nadie ha
 * verificado, y responder de ella ante la aduana es del declarante.
 */
@Slf4j
@Service
public class CustomsDutyLinesService {

    /**
     * Tope de unidades que se expanden para repartir en bultos. Muy por encima de cualquier pedido real
     * (el carrito limita la cantidad por línea) y suficientemente bajo para que una cantidad inventada no
     * pueda agotar la memoria del proceso.
     */
    private static final int MAX_UNITS = 10_000;

    /** Una línea del carrito o del pedido, con lo que hace falta para clasificarla y para pesarla. */
    public record Line(UUID productId, String hsCode, int quantity, int unitPriceCents, int unitWeightGrams,
                       int lengthMm, int widthMm, int heightMm, boolean withBattery) {
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
     * Reparte las líneas en bultos y devuelve, por bulto, su valor y su número de partidas arancelarias.
     *
     * <p>Si no hay nada que declarar devuelve la lista vacía: sin mercancía no hay envío ni derecho.
     */
    public List<DutyParcel> parcelsOf(List<Line> lines) {
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
                new ParcelSplitter.Limits(maxParcelWeightGrams, maxParcelValueCents, maxParcelUnits));

        List<DutyParcel> parcels = new ArrayList<>(bins.size());
        for (ParcelSplitter.Bin bin : bins) {
            parcels.add(new DutyParcel(bin.valueCents(), tariffLinesIn(bin, lines)));
        }
        return parcels;
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
     * Clave por la que se agrupan dos mercancías en la misma línea de declaración: la subpartida del SA.
     * Sin código, la clave es el propio producto (no se agrupa con nadie).
     */
    private static String classificationKey(Line line) {
        String hs = line.hsCode() == null ? "" : line.hsCode().replaceAll("[^0-9]", "");
        if (hs.length() < 6) {
            return "SIN-HS:" + line.productId();
        }
        return hs.substring(0, 6);
    }
}
