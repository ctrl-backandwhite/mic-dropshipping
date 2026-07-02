package com.nexaplatform.dropshipping.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.CainiaoLinkClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Fuente del impuesto (IVA/aduana) del pedido, CONMUTABLE por entorno:
 * <ul>
 *   <li><b>LOCAL</b> (por defecto, {@code tax-enabled=false}): tabla {@code country_tax_rate} vía
 *       {@link CountryTaxService}. Es el cálculo que se usa en las pruebas locales.</li>
 *   <li><b>PRE/PROD con Cainiao</b> ({@code tax-enabled=true} y Cainiao activo): se intenta el cálculo de
 *       Cainiao y, ante cualquier fallo/indisponibilidad (app no aprobada, error, sin dato), se cae a la
 *       tabla local. Así el checkout nunca se rompe y, en cuanto Cainiao esté operativo, empieza a mandar
 *       su cálculo sin tocar código.</li>
 * </ul>
 *
 * <p><b>Nota sobre Cainiao:</b> en la suscripción actual el impuesto REAL de aduana lo calcula Cainiao al
 * declarar y llega ASÍNCRONO por {@code GLOBAL_CUSTOMS_TAXINFO_CALLBACK} (webhook). No hay (en lo suscrito)
 * una cotización SÍNCRONA de impuesto para el checkout, por eso {@link #tryCainiaoTaxCents} está como TODO y
 * de momento el checkout estima con la tabla local. Cuando la app esté aprobada y conozcamos el msg_type +
 * payload, se completa ese método y PRE pasa a calcular con Cainiao automáticamente.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CainiaoTaxService {

    private final CountryTaxService localTax;
    private final CainiaoLinkClient linkClient;
    private final ObjectMapper objectMapper;

    @Value("${nexadrop.cainiao.enabled:false}")
    private boolean cainiaoEnabled;
    @Value("${nexadrop.cainiao.tax-enabled:false}")
    private boolean taxEnabled;

    /** true si el impuesto debe calcularse con Cainiao (flag de entorno + integración activa). */
    public boolean cainiaoTaxActive() {
        return taxEnabled && cainiaoEnabled;
    }

    /**
     * Impuesto en céntimos USD sobre la base imponible (subtotal + envío). Cainiao si está activo; si no
     * (o si Cainiao no devuelve dato), la tabla local.
     */
    public int taxCentsFor(String country, String region, int taxableBaseCents) {
        if (cainiaoTaxActive()) {
            Integer cn = tryCainiaoTaxCents(country, region, taxableBaseCents);
            if (cn != null) {
                return cn;
            }
        }
        return localTax.taxCentsFor(country, region, taxableBaseCents);
    }

    /**
     * Tasa efectiva en puntos básicos (para la etiqueta "X%"). Con Cainiao activo se deriva del impuesto
     * calculado sobre la base; si no, la tasa nacional/regional de la tabla local.
     */
    public int rateBpsFor(String country, String region, int taxableBaseCents) {
        if (cainiaoTaxActive() && taxableBaseCents > 0) {
            Integer cn = tryCainiaoTaxCents(country, region, taxableBaseCents);
            if (cn != null) {
                return (int) Math.round(cn * 10000.0 / taxableBaseCents);
            }
        }
        return localTax.rateBpsFor(country, region);
    }

    /**
     * Cálculo de impuesto vía Cainiao.
     *
     * <p><b>TODO(real):</b> cuando la app de Cainiao esté aprobada y conozcamos el msg_type + payload de la
     * API de cálculo/estimación de impuesto de aduana (o el dato de {@code GLOBAL_CUSTOMS_TAXINFO_CALLBACK}),
     * construir aquí el {@code logistics_interface} JSON (país destino, valor declarado, HS/categoría, peso),
     * llamar a {@code linkClient.invoke(MSG_TAX_QUOTE, json, toCode)} y parsear el importe → convertirlo a
     * céntimos USD. Devolver {@code null} en cualquier error para caer a la tabla local.
     *
     * <p>De momento devuelve {@code null} (sin API real disponible) → el checkout estima con la tabla local.
     */
    private Integer tryCainiaoTaxCents(String country, String region, int taxableBaseCents) {
        // Placeholder honesto: sin la API real (app pendiente de aprobación) no calculamos con Cainiao aún.
        // Estructura lista: en cuanto se cablee MSG_TAX_QUOTE + parseo, PRE calcula con Cainiao sin más cambios.
        return null;
    }
}
