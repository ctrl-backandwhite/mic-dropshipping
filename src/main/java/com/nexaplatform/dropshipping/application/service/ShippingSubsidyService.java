package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cuánto dinero hay para abaratarle el envío al cliente, y de dónde sale.
 *
 * <p>Dos fuentes, y ninguna es una promoción inventada:
 *
 * <ol>
 *   <li><b>El porte repetido.</b> El porte del proveedor viaja dentro del precio unitario, así que tres
 *       unidades del mismo producto cobran tres portes chinos. Pero el proveedor manda <b>un solo bulto</b>
 *       al almacén tenga el cliente una unidad o cinco, y da igual que sean de colores o tallas distintas:
 *       sigue siendo un envío. Del segundo en adelante es dinero que entra y no se gasta, así que vuelve.
 *       Esto <b>no es una promoción</b> —es devolver un gasto que no existe— y por eso vale en cualquier
 *       destino.</li>
 *   <li><b>La ganancia sobre 5 EUR.</b> Del margen del pedido entero se aparta ese suelo para el negocio y
 *       el resto se devuelve en forma de envío más barato. Esta sí es una decisión comercial, y por
 *       decisión del dueño se aplica <b>solo en la Unión Europea</b>.</li>
 * </ol>
 *
 * <p><b>La bolsa baja lo que el cliente PAGA, nunca lo que se declara.</b> El derecho de aduana se sigue
 * calculando, guardando y remitiendo íntegro; la subvención es un concepto aparte del desglose. Confundir
 * las dos cosas sería infradeclarar ante 27 aduanas, y de eso responde el declarante.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShippingSubsidyService {

    private final CustomsValuationService customsValuation;
    private final CurrencyRateService currencyService;

    /**
     * Lo que se queda el negocio antes de subvencionar nada, en EUROS.
     *
     * <p>Va en euros porque la regla se pensó en euros y solo aplica en la Unión; se convierte a dólares
     * —la moneda canónica del cobro— con la tasa del día, no con un número escrito a mano.
     */
    @Value("${nexadrop.shipping.subsidy-profit-floor-eur:5}")
    private BigDecimal sueloDeGananciaEur;

    /**
     * Una línea del carrito, con lo justo para hacer las dos cuentas.
     *
     * @param productId          agrupa por PRODUCTO, no por variante: dos tallas del mismo artículo viajan
     *                           en el mismo bulto del proveedor
     * @param unitProfitUsdCents ganancia por unidad = base con margen − coste. NO incluye el IVA ni el
     *                           porte del proveedor, que son dinero que se le debe a él y no ganancia
     *                           nuestra; contarlos como tal regalaría margen que no existe
     */
    public record Linea(UUID productId, int quantity, int unitProfitUsdCents, int unitShippingUsdCents) {
    }

    /** Cuántos céntimos de dólar hay en la bolsa para este carrito y este destino. */
    @Transactional(readOnly = true)
    public int subsidyUsdCents(List<Linea> lineas, String countryCode) {
        if (lineas == null || lineas.isEmpty()) {
            return 0;
        }
        int bolsa = porteRepetido(lineas);
        // La regla en euros es de la UE-27 y solo de ella. Se reconoce por el mismo dato que decide todo
        // lo demás del arancel —el importe por artículo del país—, para no tener una segunda lista de
        // países que alguien tenga que mantener al día por su cuenta.
        if (customsValuation.perArticleFeeUsdCents(countryCode) > 0) {
            bolsa = Math.addExact(bolsa, gananciaSobrante(lineas));
        }
        return bolsa;
    }

    /**
     * Los portes que se cobran y no se pagan: uno por cada unidad a partir de la primera de cada producto.
     *
     * <p>Se agrupa por producto y no por línea del carrito porque el mismo artículo en dos variantes son
     * dos líneas y un solo bulto.
     */
    private static int porteRepetido(List<Linea> lineas) {
        Map<UUID, Integer> unidades = new HashMap<>();
        Map<UUID, Integer> porteUnitario = new HashMap<>();
        for (Linea l : lineas) {
            if (l.productId() == null) {
                continue;
            }
            unidades.merge(l.productId(), Math.max(0, l.quantity()), Integer::sum);
            porteUnitario.putIfAbsent(l.productId(), Math.max(0, l.unitShippingUsdCents()));
        }
        int total = 0;
        for (Map.Entry<UUID, Integer> e : unidades.entrySet()) {
            int repetidas = Math.max(0, e.getValue() - 1);
            total = Math.addExact(total, Math.multiplyExact(repetidas, porteUnitario.get(e.getKey())));
        }
        return total;
    }

    /**
     * Lo que sobra de la ganancia del pedido tras apartar el suelo del negocio.
     *
     * <p>La ganancia se mira del <b>pedido entero</b>: una sola cuenta, explicable al cliente y auditable
     * en el pedido. Y nunca es negativa: una línea vendida a pérdida no puede comerse la subvención que
     * han generado las demás por la vía de restar.
     */
    private int gananciaSobrante(List<Linea> lineas) {
        long ganancia = 0;
        for (Linea l : lineas) {
            ganancia += (long) l.unitProfitUsdCents() * Math.max(0, l.quantity());
        }
        int suelo = sueloUsdCents();
        return ganancia > suelo ? Math.toIntExact(ganancia - suelo) : 0;
    }

    /** El suelo de ganancia en céntimos de dólar, con la tasa del día. */
    private int sueloUsdCents() {
        BigDecimal enDolares = currencyService.toUsd(sueloDeGananciaEur, "EUR");
        return enDolares == null ? 0 : enDolares.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValue();
    }
}
