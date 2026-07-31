package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.ParcelSpec;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reparte las líneas de un pedido en los bultos necesarios para que cada uno quepa en el canal del
 * transportista.
 *
 * <p>Cada producto logístico impone un peso y un valor declarado máximos —el canal BPA rechaza más de
 * 2 kg o más de 24 $— así que un carrito grande no viaja en un solo paquete. Este repartidor decide
 * cuántos bultos hacen falta y qué unidades van en cada uno; después, cada bulto se da de alta como una
 * guía independiente.
 *
 * <p><b>El reparto es SOLO por límites físicos del canal.</b> Nunca se parte para que cada envío quede
 * por debajo del umbral de minimis del destino: eso es fraccionamiento artificial, está prohibido en la
 * UE —que agrega los envíos del mismo pedido al mismo destinatario— y expondría al comercio a sanción.
 * El umbral se sigue evaluando sobre el valor del pedido completo.
 *
 * <p>El algoritmo es un empaquetado <i>first-fit</i> sobre las unidades ordenadas de más pesada a menos:
 * es el que menos bultos genera de los simples, y menos bultos es menos coste de envío. Una unidad que
 * por sí sola excede el límite viaja igualmente sola —no se puede partir un artículo—, y se registra
 * para que se vea que ese producto no encaja en el canal contratado.
 */
@Slf4j
public final class ParcelSplitter {

    private ParcelSplitter() {
    }

    /** Una unidad suelta a colocar: de qué línea viene, cuánto pesa y cuánto declara. */
    public record Unit(int lineIndex, int weightGrams, int valueCents, int lengthMm, int widthMm, int heightMm,
                       boolean withBattery) {
    }

    /** Un bulto ya formado: sus unidades y el {@link ParcelSpec} con el que se cotiza y se despacha. */
    public record Bin(List<Unit> units, ParcelSpec spec, int valueCents) {

        /** Cuántas unidades de una línea del pedido caen en este bulto. */
        public int quantityOfLine(int lineIndex) {
            return (int) units.stream().filter(u -> u.lineIndex() == lineIndex).count();
        }
    }

    /**
     * Límites del canal. Un valor {@code <= 0} significa "sin límite", que es el comportamiento cuando no
     * se ha configurado nada: entonces todo viaja en un solo bulto, como antes.
     */
    public record Limits(int maxWeightGrams, int maxValueCents, int maxUnitsPerParcel) {

        public boolean unlimited() {
            return maxWeightGrams <= 0 && maxValueCents <= 0 && maxUnitsPerParcel <= 0;
        }
    }

    /**
     * Reparte las unidades en bultos que respeten los límites. Devuelve siempre al menos un bulto (aunque
     * el pedido esté vacío) para que el flujo de creación de envío no tenga que tratar el caso especial.
     */
    public static List<Bin> split(List<Unit> units, Limits limits) {
        if (units.isEmpty()) {
            return List.of(new Bin(List.of(), ParcelSpec.ofWeight(1), 0));
        }
        if (limits.unlimited()) {
            return List.of(toBin(new ArrayList<>(units)));
        }
        // De más pesada a menos: first-fit sobre elementos grandes deja menos huecos y menos bultos.
        List<Unit> ordered = new ArrayList<>(units);
        ordered.sort(Comparator.comparingInt(Unit::weightGrams).reversed());

        List<List<Unit>> bins = new ArrayList<>();
        for (Unit unit : ordered) {
            List<Unit> target = null;
            for (List<Unit> bin : bins) {
                if (fits(bin, unit, limits)) {
                    target = bin;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                bins.add(target);
                if (exceedsAlone(unit, limits)) {
                    // No se puede partir un artículo: viaja solo aunque no cumpla. Que quede constancia,
                    // porque significa que ese producto no encaja en el canal contratado.
                    log.warn("::> [FULFILLMENT] Unidad de la línea {} excede por sí sola los límites del canal "
                            + "(peso={} g, valor={} céntimos)", unit.lineIndex(), unit.weightGrams(),
                            unit.valueCents());
                }
            }
            target.add(unit);
        }
        return bins.stream().map(ParcelSplitter::toBin).toList();
    }

    /** ¿Cabe la unidad en el bulto sin pasarse de ningún límite? */
    private static boolean fits(List<Unit> bin, Unit unit, Limits limits) {
        if (limits.maxUnitsPerParcel() > 0 && bin.size() + 1 > limits.maxUnitsPerParcel()) {
            return false;
        }
        if (limits.maxWeightGrams() > 0
                && sum(bin, Unit::weightGrams) + unit.weightGrams() > limits.maxWeightGrams()) {
            return false;
        }
        return limits.maxValueCents() <= 0
                || sum(bin, Unit::valueCents) + unit.valueCents() <= limits.maxValueCents();
    }

    /** ¿La unidad se pasa de los límites ella sola? Entonces ningún reparto la va a acomodar. */
    private static boolean exceedsAlone(Unit unit, Limits limits) {
        return (limits.maxWeightGrams() > 0 && unit.weightGrams() > limits.maxWeightGrams())
                || (limits.maxValueCents() > 0 && unit.valueCents() > limits.maxValueCents());
    }

    private static int sum(List<Unit> bin, java.util.function.ToIntFunction<Unit> field) {
        return bin.stream().mapToInt(field).sum();
    }

    /**
     * Convierte las unidades de un bulto en su {@link ParcelSpec}: pesos y valores se suman, y las
     * medidas se apilan igual que en {@link ParcelAggregator} —mayor largo, mayor ancho, alturas
     * sumadas—, que es la aproximación conservadora habitual para un paquete único.
     */
    private static Bin toBin(List<Unit> units) {
        int weight = 0;
        int value = 0;
        int maxLength = 0;
        int maxWidth = 0;
        int totalHeight = 0;
        boolean battery = false;
        for (Unit unit : units) {
            weight += unit.weightGrams();
            value += unit.valueCents();
            if (unit.lengthMm() > 0 && unit.widthMm() > 0 && unit.heightMm() > 0) {
                maxLength = Math.max(maxLength, unit.lengthMm());
                maxWidth = Math.max(maxWidth, unit.widthMm());
                totalHeight += unit.heightMm();
            }
            battery |= unit.withBattery();
        }
        return new Bin(List.copyOf(units),
                new ParcelSpec(Math.max(1, weight), maxLength, maxWidth, totalHeight, battery), value);
    }
}
