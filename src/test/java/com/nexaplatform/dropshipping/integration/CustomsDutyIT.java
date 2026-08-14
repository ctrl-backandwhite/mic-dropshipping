package com.nexaplatform.dropshipping.integration;

import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService;
import com.nexaplatform.dropshipping.application.service.CheckoutTotalsService.CheckoutTotals;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.DutyParcel;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.Line;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService.CustomsValuation;
import com.nexaplatform.dropshipping.config.BaseIntegration;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Certificación CALC-ADU: el dinero que se cobra por el despacho de aduana, comprobado AL CÉNTIMO contra
 * un importe calculado a mano y contra los umbrales REALES de la base de datos.
 *
 * <p><b>Por qué existe esta clase.</b> El derecho temporal de 3 EUR de la Unión Europea estuvo semanas
 * cobrándose POR PRODUCTO en vez de POR PARTIDA ARANCELARIA con la suite entera en verde: había pruebas de
 * que «se cobraba algo», ninguna de «se cobraba ESTO». Aquí no se comprueba que el cálculo devuelva un
 * número, sino que devuelve EXACTAMENTE el número que sale de aplicar la norma a mano.
 *
 * <p><b>Las tres cifras de referencia</b> (con la tasa sembrada EUR = 0,92 USD, {@code currency_rate}):
 * <ul>
 *   <li>Derecho por línea: 3 EUR / 0,92 = 3,2609 USD → <b>326 céntimos USD</b>.</li>
 *   <li>Franquicia UE: 150 EUR / 0,92 = 163,0435 USD → <b>16.304 céntimos USD</b>.</li>
 *   <li>Límites del canal contratado (BPA de YunExpress): <b>2.000 g</b> y <b>15.500 céntimos</b>, los
 *       mismos que {@code infra/docker/.env} pone en producción.</li>
 * </ul>
 *
 * <p><b>Semillas.</b> {@code BaseIntegration} vacía TODAS las tablas antes de cada test, incluidas
 * {@code country_customs_rule} y {@code currency_rate}, que son datos de migración y no de negocio. Se
 * reponen releyendo las propias migraciones (ver {@link #restauraSemillasDeMigraciones()}), nunca
 * copiándolas a mano: si mañana una migración cambia un umbral, estos casos siguen valiendo.
 */
@TestPropertySource(properties = {
        // Límites REALES del canal contratado. Sin ellos (por defecto 0 = sin límite) todo viajaría en un
        // solo bulto y el reparto —que es lo que decide cuántas veces se cobra el derecho— no se probaría.
        "nexadrop.yunexpress.max-parcel-weight-grams=2000",
        "nexadrop.yunexpress.max-parcel-value-cents=15500",
        "nexadrop.yunexpress.max-parcel-units=0"
})
class CustomsDutyIT extends BaseIntegration {

    /** Los 27 Estados miembros: lista LEGAL, no un importe. Solo ellos llevan el derecho de 3 EUR. */
    private static final Set<String> UE_27 = Set.of("AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI",
            "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI",
            "ES", "SE");

    /** Subpartidas del Sistema Armonizado usadas en los casos (6 dígitos = lo que declara el H7). */
    private static final String HS_CAMISETAS = "610910";
    private static final String HS_VAQUEROS = "620342";
    private static final String HS_ANORAKS = "610419";

    private static final Pattern VERSION_DE_MIGRACION = Pattern.compile("schema-v(\\d+)");

    @Autowired
    private CustomsDutyLinesService lineasAduaneras;
    @Autowired
    private CustomsValuationService valoracion;
    @Autowired
    private CheckoutTotalsService totalesCheckout;
    @Autowired
    private CurrencyRateService divisas;

    @BeforeEach
    void reponSemillasDeCalculo() {
        restauraSemillasDeMigraciones();
        // La caché de divisas (TTL 5 min) se calentó al arrancar el contexto y no se entera del TRUNCATE.
        // `overrideRate` es el único punto público que la refresca; el valor es el mismo (USD = 1), así que
        // la operación es neutra y deja la caché exactamente igual que la tabla recién repuesta.
        divisas.overrideRate("USD", BigDecimal.ONE);
    }

    /* ==================================================================================
     * 1. El derecho de 3 EUR: por PARTIDA ARANCELARIA y por BULTO
     * ================================================================================== */

    @Test
    @DisplayName("Un pedido con una sola referencia a España paga UN derecho de 3 EUR (326 céntimos USD)")
    void unaSolaReferenciaPagaUnSoloDerecho() {
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(linea(HS_CAMISETAS, 1, 1000, 500)));

        assertThat(bultos).hasSize(1);
        assertThat(bultos.get(0).tariffLines()).isEqualTo(1);

        CustomsValuation v = valoracion.valuate("ES", 1000, 0, bultos);

        // 3 EUR ÷ 0,92 = 3,2609 USD → 326 céntimos. Ni 300 (tratar el euro como dólar) ni 3 (confundir
        // unidades): el importe exacto, y comprobado además contra la tasa que hay en la tabla.
        assertThat(v.handlingFeeCents()).isEqualTo(326);
        assertThat(v.handlingFeeCents()).isEqualTo(aCentimosUsd(new BigDecimal("3.00"), "EUR"));
        assertThat(v.deMinimisExceeded()).isFalse();
        assertThat(v.blocked()).isFalse();
    }

    @Test
    @DisplayName("Cinco unidades de la misma referencia son UNA línea: 326 céntimos, no 1.630")
    void laCantidadNoMultiplicaElDerecho() {
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(linea(HS_CAMISETAS, 5, 200, 300)));

        assertThat(bultos).hasSize(1);
        assertThat(bultos.get(0).tariffLines()).isEqualTo(1);
        assertThat(valoracion.valuate("ES", 1000, 0, bultos).handlingFeeCents()).isEqualTo(326);
    }

    @Test
    @DisplayName("Productos DISTINTOS con la misma subpartida HS6 son UNA sola línea: 326, no 978")
    void productosDistintosConLaMismaSubpartidaSonUnaLinea() {
        // El ejemplo de la propia guía de la Comisión: anorak, cortavientos y cazadora comparten la
        // subpartida 6104 19 y pagan 3 EUR EN TOTAL. Contarlos como tres productos cobraba 9 EUR.
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(
                linea(HS_ANORAKS + "0000", 1, 300, 400),
                linea(HS_ANORAKS + "9010", 1, 300, 400),
                linea(HS_ANORAKS, 1, 300, 400)));

        assertThat(bultos).hasSize(1);
        assertThat(bultos.get(0).tariffLines()).isEqualTo(1);
        assertThat(valoracion.valuate("ES", 900, 0, bultos).handlingFeeCents()).isEqualTo(326);
    }

    @Test
    @DisplayName("Muchas referencias distintas con el mismo HS6 siguen siendo UNA línea (326)")
    void muchasReferenciasConElMismoHs6SiguenSiendoUnaLinea() {
        List<Line> lineas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            lineas.add(linea(HS_CAMISETAS, 2, 100, 100));
        }
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(lineas);

        assertThat(bultos).hasSize(1);
        assertThat(bultos.get(0).tariffLines()).isEqualTo(1);
        assertThat(valoracion.valuate("ES", 1600, 0, bultos).handlingFeeCents()).isEqualTo(326);
    }

    @Test
    @DisplayName("Dos subpartidas distintas son dos líneas: 652 céntimos exactos")
    void dosSubpartidasDistintasSonDosLineas() {
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(
                linea(HS_CAMISETAS, 3, 200, 200),
                linea(HS_VAQUEROS, 2, 500, 400)));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(2);
        assertThat(valoracion.valuate("ES", 1600, 0, bultos).handlingFeeCents()).isEqualTo(652);
    }

    @Test
    @DisplayName("Un producto SIN código HS cuenta como línea propia (se cobra de más, nunca de menos)")
    void unProductoSinCodigoHsEsLineaPropia() {
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(
                linea(HS_CAMISETAS, 1, 300, 200),
                linea(null, 1, 300, 200)));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(2);
        assertThat(valoracion.valuate("ES", 600, 0, bultos).handlingFeeCents()).isEqualTo(652);
    }

    @Test
    @DisplayName("Dos productos con un HS de menos de 6 dígitos NO se agrupan: dos líneas (652)")
    void unHsIncompletoNoAgrupaConNadie() {
        // "6109" no llega a subpartida: no se puede afirmar que dos productos compartan clasificación, así
        // que cada uno declara por su cuenta. Cobrar de más es recuperable; infradeclarar, no.
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(
                linea("6109", 1, 300, 200),
                linea("6109", 1, 300, 200)));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(2);
        assertThat(valoracion.valuate("ES", 600, 0, bultos).handlingFeeCents()).isEqualTo(652);
    }

    @Test
    @DisplayName("Un HS con puntos o espacios se normaliza y SÍ agrupa: una línea (326)")
    void unHsConSeparadoresSeNormaliza() {
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(
                linea("6109.10.00", 1, 300, 200),
                linea(" 6109 10 ", 1, 300, 200),
                linea(HS_CAMISETAS, 1, 300, 200)));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(1);
        assertThat(valoracion.valuate("ES", 900, 0, bultos).handlingFeeCents()).isEqualTo(326);
    }

    @Test
    @DisplayName("Un carrito vacío no declara nada y no cobra derecho: 0 céntimos")
    void unCarritoVacioNoCobraDerecho() {
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of());

        assertThat(bultos).isEmpty();

        CustomsValuation v = valoracion.valuate("ES", 0, 0, bultos);
        assertThat(v.handlingFeeCents()).isZero();
        assertThat(v.deMinimisExceeded()).isFalse();

        CheckoutTotals totales = totalesCheckout.compute("ES", null, 0, 0, bultos);
        assertThat(totales.customsHandlingCents()).isZero();
        assertThat(totales.shippingCents()).isZero();
        assertThat(totales.taxCents()).isZero();
        assertThat(totales.totalCents(0)).isZero();
    }

    /* ==================================================================================
     * 2. Reparto en bultos: los bordes exactos del canal (2 kg / 155 USD)
     * ================================================================================== */

    @Test
    @DisplayName("Peso EXACTO de 2 kg: un solo bulto y un solo derecho (326)")
    void elPesoExactoDelLimiteCabeEnUnBulto() {
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(linea(HS_CAMISETAS, 4, 100, 500)));

        assertThat(bultos).hasSize(1);
        assertThat(valoracion.valuate("ES", 400, 0, bultos).handlingFeeCents()).isEqualTo(326);
    }

    @Test
    @DisplayName("2 kg más 4 g: se parte en dos bultos y el derecho se cobra DOS veces (652)")
    void unGramoDeMasParteElPedidoYDuplicaElDerecho() {
        // 4 × 501 g = 2.004 g. El primer bulto admite tres unidades (1.503 g); la cuarta abre bulto nuevo.
        // Cada bulto lleva su propia declaración, así que cada uno paga su línea: 2 × 326 = 652.
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(linea(HS_CAMISETAS, 4, 100, 501)));

        assertThat(bultos).hasSize(2);
        assertThat(bultos).allMatch(b -> b.tariffLines() == 1);
        assertThat(valoracion.valuate("ES", 400, 0, bultos).handlingFeeCents()).isEqualTo(652);
    }

    @Test
    @DisplayName("Valor EXACTO de 155 USD: un solo bulto (326)")
    void elValorExactoDelLimiteCabeEnUnBulto() {
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(linea(HS_CAMISETAS, 2, 7750, 400)));

        assertThat(bultos).hasSize(1);
        assertThat(bultos.get(0).valueCents()).isEqualTo(15500);
        assertThat(valoracion.valuate("ES", 15500, 0, bultos).handlingFeeCents()).isEqualTo(326);
    }

    @Test
    @DisplayName("155 USD más 2 céntimos: dos bultos y dos derechos (652)")
    void unCentimoDeMasEnValorParteElPedido() {
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(linea(HS_CAMISETAS, 2, 7751, 400)));

        assertThat(bultos).hasSize(2);
        assertThat(valoracion.valuate("ES", 15502, 0, bultos).handlingFeeCents()).isEqualTo(652);
    }

    @Test
    @DisplayName("Un artículo que por sí solo excede el bulto viaja igual y paga UN derecho (326)")
    void unArticuloQueNoCabeEnElCanalViajaSoloYPagaUnaLinea() {
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(linea(HS_VAQUEROS, 1, 900, 2500)));

        assertThat(bultos).hasSize(1);
        assertThat(bultos.get(0).tariffLines()).isEqualTo(1);
        assertThat(valoracion.valuate("ES", 900, 0, bultos).handlingFeeCents()).isEqualTo(326);
    }

    @Test
    @DisplayName("Sin peso declarado se asumen 500 g por unidad: cuatro caben, cinco no")
    void elPesoQueFaltaSeAsumeEnQuinientosGramos() {
        assertThat(lineasAduaneras.parcelsOf(List.of(linea(HS_CAMISETAS, 4, 100, 0)))).hasSize(1);

        List<DutyParcel> cinco = lineasAduaneras.parcelsOf(List.of(linea(HS_CAMISETAS, 5, 100, 0)));
        assertThat(cinco).hasSize(2);
        assertThat(valoracion.valuate("ES", 500, 0, cinco).handlingFeeCents()).isEqualTo(652);
    }

    @Test
    @DisplayName("Dos subpartidas repartidas en dos bultos: cada bulto declara SOLO lo que lleva")
    void cadaBultoDeclaraSusPropiasLineas() {
        // Dos unidades de 1.100 g cada una: no caben juntas (2.200 > 2.000), así que va una por bulto y
        // cada bulto tiene UNA sola clasificación. Total 2 líneas = 652, no 4 líneas = 1.304.
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(
                linea(HS_CAMISETAS, 1, 500, 1100),
                linea(HS_VAQUEROS, 1, 500, 1100)));

        assertThat(bultos).hasSize(2);
        assertThat(bultos).allMatch(b -> b.tariffLines() == 1);
        assertThat(valoracion.valuate("ES", 1000, 0, bultos).handlingFeeCents()).isEqualTo(652);
    }

    /* ==================================================================================
     * 3. El umbral de minimis: el borde exacto, país por país
     * ================================================================================== */

    @Test
    @DisplayName("Hay 52 países con umbral configurado y ninguno lleva recargos de gestión")
    void laConfiguracionDeUmbralesEsLaEsperada() {
        List<Map<String, Object>> reglas = reglasConUmbral();

        assertThat(reglas).as("países con de_minimis_amount > 0").hasSize(52);

        // Todo el recargo de despacho es HOY el derecho por partida: si alguien siembra un handling fee o
        // un porcentaje, los importes de esta clase dejan de cuadrar y hay que revisar el cobro entero.
        for (Map<String, Object> r : reglas) {
            String pais = (String) r.get("country_code");
            assertThat(entero(r.get("handling_fee_cents"))).as("handling fijo de %s", pais).isZero();
            assertThat(entero(r.get("handling_percent_bps"))).as("handling %% de %s", pais).isZero();
            assertThat(entero(r.get("vat_prepay_percent_bps"))).as("prepago IVA de %s", pais).isZero();
        }
    }

    @Test
    @DisplayName("Para CADA país con umbral: un céntimo por debajo queda DENTRO del régimen de bajo valor")
    void justoPorDebajoDelUmbralTodosLosPaisesCobranLoEsperado() {
        List<String> descuadres = new ArrayList<>();
        for (Map<String, Object> regla : reglasConUmbral()) {
            String pais = (String) regla.get("country_code");
            int umbral = umbralEnCentimosUsd(regla);
            int derecho = derechoPorLineaEnCentimosUsd(regla);

            CustomsValuation v = valoracion.valuate(pais, umbral - 1, 0, List.of(new DutyParcel(umbral - 1, 1)));

            anota(descuadres, pais, "umbral-1 debía quedar dentro", false, v.deMinimisExceeded());
            anota(descuadres, pais, "umbral-1 no debía bloquear", false, v.blocked());
            anota(descuadres, pais, "recargo con una línea", derecho, v.handlingFeeCents());
        }
        assertThat(descuadres).as("países cuyo importe no cuadra").isEmpty();
    }

    @Test
    @DisplayName("Para CADA país: el importe EXACTAMENTE igual al umbral queda DENTRO («no excede»)")
    void elImporteExactamenteIgualAlUmbralQuedaDentro() {
        // Es el borde que más dinero mueve: la norma dice «whose intrinsic value does not exceed EUR 150»,
        // así que 150,00 € clavados son bajo valor. Un `>=` donde va un `>` bloquearía el pedido más
        // frecuente de todos y además le negaría el régimen simplificado sin motivo legal.
        List<String> descuadres = new ArrayList<>();
        for (Map<String, Object> regla : reglasConUmbral()) {
            String pais = (String) regla.get("country_code");
            int umbral = umbralEnCentimosUsd(regla);
            int derecho = derechoPorLineaEnCentimosUsd(regla);

            CustomsValuation v = valoracion.valuate(pais, umbral, 0, List.of(new DutyParcel(umbral, 1)));

            anota(descuadres, pais, "umbral exacto debía quedar dentro", false, v.deMinimisExceeded());
            anota(descuadres, pais, "umbral exacto no debía bloquear", false, v.blocked());
            anota(descuadres, pais, "recargo en el umbral exacto", derecho, v.handlingFeeCents());
        }
        assertThat(descuadres).as("países cuyo borde exacto no cuadra").isEmpty();
    }

    @Test
    @DisplayName("Para CADA país: un céntimo por encima del umbral queda FUERA y se bloquea antes de cobrar")
    void unCentimoPorEncimaDelUmbralQuedaFueraYSeBloquea() {
        List<String> descuadres = new ArrayList<>();
        for (Map<String, Object> regla : reglasConUmbral()) {
            String pais = (String) regla.get("country_code");
            int umbral = umbralEnCentimosUsd(regla);

            CustomsValuation v = valoracion.valuate(pais, umbral + 1, 0, List.of(new DutyParcel(umbral + 1, 1)));

            anota(descuadres, pais, "umbral+1 debía quedar fuera", true, v.deMinimisExceeded());
            // Todas las reglas con umbral quedaron en BLOCK (migración v100/v101): la línea contratada no
            // despacha formalmente, así que aceptar el pedido sería cobrar algo que luego hay que devolver.
            anota(descuadres, pais, "umbral+1 debía bloquear", true, v.blocked());
            // Por encima de la franquicia NO se aplica el importe fijo, sino el arancel normal del TARIC:
            // el bulto excedido no suma derecho por línea.
            anota(descuadres, pais, "recargo por encima del umbral", 0, v.handlingFeeCents());
        }
        assertThat(descuadres).as("países cuyo borde superior no cuadra").isEmpty();
    }

    @Test
    @DisplayName("El umbral de la UE son 16.304 céntimos USD (150 EUR a 0,92) y el borde está ahí")
    void elUmbralDeLaUnionEuropeaEstaEnDieciseisMilTrescientosCuatro() {
        assertThat(aCentimosUsd(new BigDecimal("150.00"), "EUR")).isEqualTo(16304);

        assertThat(valoracion.valuate("FR", 16304, 0, List.of(new DutyParcel(16304, 1))).deMinimisExceeded())
                .isFalse();
        assertThat(valoracion.valuate("FR", 16305, 0, List.of(new DutyParcel(16305, 1))).deMinimisExceeded())
                .isTrue();
    }

    @Test
    @DisplayName("Un umbral en divisa no euro se convierte con la tasa: Canadá son 1.471 céntimos USD")
    void unUmbralEnOtraDivisaSeEvaluaEnSuDivisaLegal() {
        // 20 CAD ÷ 1,36 = 14,705882… → 14,7059 USD → 1.470,59 céntimos → 1.471 (HALF_UP). El redondeo del
        // borde importa: con truncamiento serían 1.470 y un pedido de 1.471 se bloquearía sin motivo.
        assertThat(aCentimosUsd(new BigDecimal("20.00"), "CAD")).isEqualTo(1471);
        assertThat(valoracion.valuate("CA", 1471, 0, List.of(new DutyParcel(1471, 1))).deMinimisExceeded())
                .isFalse();
        assertThat(valoracion.valuate("CA", 1472, 0, List.of(new DutyParcel(1472, 1))).blocked()).isTrue();

        // Suiza: 62 CHF ÷ 0,91 = 68,1319 USD → 6.813 céntimos.
        assertThat(aCentimosUsd(new BigDecimal("62.00"), "CHF")).isEqualTo(6813);
        assertThat(valoracion.valuate("CH", 6813, 0, List.of(new DutyParcel(6813, 1))).deMinimisExceeded())
                .isFalse();
        assertThat(valoracion.valuate("CH", 6814, 0, List.of(new DutyParcel(6814, 1))).deMinimisExceeded())
                .isTrue();

        // Reino Unido: 135 GBP ÷ 0,79 = 170,8861 USD → 17.089 céntimos. Y NO lleva el derecho de 3 EUR.
        assertThat(aCentimosUsd(new BigDecimal("135.00"), "GBP")).isEqualTo(17089);
        CustomsValuation uk = valoracion.valuate("GB", 17089, 0, List.of(new DutyParcel(17089, 1)));
        assertThat(uk.deMinimisExceeded()).isFalse();
        assertThat(uk.handlingFeeCents()).isZero();
    }

    @Test
    @DisplayName("El borde se mueve con el tipo de cambio: el mismo pedido cambia de lado si el euro sube")
    void elBordeSeMueveConElTipoDeCambio() {
        // 160,00 USD de valor intrínseco. Con EUR = 0,92 el umbral son 16.304 céntimos → DENTRO.
        assertThat(valoracion.valuate("ES", 16000, 0, List.of(new DutyParcel(16000, 1))).blocked()).isFalse();

        // Con EUR = 0,95 el mismo umbral legal (150 EUR) vale 15.789 céntimos USD → el MISMO pedido queda
        // FUERA y se bloquea. No es un fallo: es que el borde está en euros y el cobro en dólares. Queda
        // documentado para que nadie lo lea como una regresión cuando cambie la cotización del día.
        divisas.overrideRate("EUR", new BigDecimal("0.95"));
        assertThat(aCentimosUsd(new BigDecimal("150.00"), "EUR")).isEqualTo(15789);
        assertThat(valoracion.valuate("ES", 16000, 0, List.of(new DutyParcel(16000, 1))).blocked()).isTrue();

        // Y el derecho por línea también se mueve: 3 EUR ÷ 0,95 = 3,1579 USD → 316 céntimos.
        assertThat(valoracion.valuate("ES", 1000, 0, List.of(new DutyParcel(1000, 1))).handlingFeeCents())
                .isEqualTo(316);
    }

    @Test
    @DisplayName("Un país con umbral 0 no se evalúa y NO encarece el pedido")
    void unPaisSinUmbralConfiguradoNoEncarece() {
        // Estados Unidos está sembrado con de_minimis 0 (la franquicia de 800 USD dejó de aplicar al
        // origen chino). 0 significa «sin dato», no «umbral cero»: ni se marca excedido ni se recarga.
        CustomsValuation v = valoracion.valuate("US", 100_000, 0, List.of(new DutyParcel(100_000, 1)));

        assertThat(v.deMinimisExceeded()).isFalse();
        assertThat(v.blocked()).isFalse();
        assertThat(v.handlingFeeCents()).isZero();
        assertThat(v.deMinimisLabel()).isEmpty();
    }

    @Test
    @DisplayName("Un carrito de muchos artículos baratos que suma por encima del umbral también queda fuera")
    void elUmbralSeMideSobreElAcumuladoNoPorArticulo() {
        // 60 unidades de 3,00 USD = 180,00 USD. Ninguna se acerca al umbral; el acumulado sí lo pasa.
        // Sin bultos calculados, la valoración cae al valor del pedido completo, que es lo correcto aquí.
        CustomsValuation v = valoracion.valuate("ES", 18_000, 0, List.of());

        assertThat(v.deMinimisExceeded()).isTrue();
        assertThat(v.blocked()).isTrue();
        assertThat(v.handlingFeeCents()).isZero();
    }

    /* ==================================================================================
     * 4. Quién lleva el derecho y quién no
     * ================================================================================== */

    @Test
    @DisplayName("Los 27 de la UE cobran el derecho por partida; ninguno se lo salta ni lo duplica")
    void losVeintisieteCobranElDerechoYSoloEllos() {
        List<String> descuadres = new ArrayList<>();
        for (String pais : UE_27) {
            CustomsValuation una = valoracion.valuate(pais, 5000, 0, List.of(new DutyParcel(5000, 1)));
            CustomsValuation tres = valoracion.valuate(pais, 5000, 0, List.of(new DutyParcel(5000, 3)));
            anota(descuadres, pais, "una partida", 326, una.handlingFeeCents());
            anota(descuadres, pais, "tres partidas", 978, tres.handlingFeeCents());
        }
        assertThat(descuadres).as("países de la UE con importe incorrecto").isEmpty();

        // Y el derecho es EXCLUSIVO de los 27: ninguna otra fila de la tabla lo lleva sembrado.
        List<String> conDerecho = jdbcTemplate.queryForList(
                "SELECT country_code FROM country_customs_rule WHERE per_article_fee_amount > 0",
                String.class);
        assertThat(conDerecho).containsExactlyInAnyOrderElementsOf(UE_27);
    }

    @Test
    @DisplayName("Fuera de la UE-27 no hay derecho fijo: mismo pedido a Brasil o a Australia, 0 de recargo")
    void fueraDeLaUnionEuropeaNoHayDerechoFijo() {
        List<DutyParcel> bultos = List.of(new DutyParcel(5000, 3));

        assertThat(valoracion.valuate("BR", 5000, 0, bultos).handlingFeeCents()).isZero();
        assertThat(valoracion.valuate("AU", 5000, 0, bultos).handlingFeeCents()).isZero();
        assertThat(valoracion.valuate("GB", 5000, 0, bultos).handlingFeeCents()).isZero();
        assertThat(valoracion.valuate("US", 5000, 0, bultos).handlingFeeCents()).isZero();
    }

    @Test
    @DisplayName("HALLAZGO Mónaco: mismo umbral de 150 EUR que la UE-27, pero sin el derecho de 3 EUR")
    void monacoTieneUmbralDeLaUnionPeroNoSuDerecho() {
        List<DutyParcel> bultos = List.of(new DutyParcel(5000, 2));

        CustomsValuation francia = valoracion.valuate("FR", 5000, 0, bultos);
        CustomsValuation monaco = valoracion.valuate("MC", 5000, 0, bultos);

        // Mismo umbral legal…
        assertThat(monaco.deMinimisLabel()).isEqualTo(francia.deMinimisLabel()).isEqualTo("150 EUR");
        // …y sin embargo un pedido idéntico cuesta 6,52 USD más en Francia que en Mónaco.
        assertThat(francia.handlingFeeCents()).isEqualTo(652);
        assertThat(monaco.handlingFeeCents()).isZero();

        // Mónaco está en el territorio aduanero de la UE (unión aduanera con Francia) y sus envíos se
        // despachan como franceses: o le falta el derecho, o le sobra el umbral. Este test NO decide cuál
        // de las dos: fija la asimetría por escrito para que se resuelva como decisión de negocio y no
        // siga viva por descuido. El día que se corrija, este caso falla y obliga a mirarlo.
    }

    /* ==================================================================================
     * 5. Valor declarado y totales del checkout
     * ================================================================================== */

    @Test
    @DisplayName("El valor declarado es el intrínseco que paga el cliente, nunca el coste de compra")
    void elValorDeclaradoEsElQuePagaElCliente() {
        CustomsValuation v = valoracion.valuate("ES", 12_345, 0, List.of(new DutyParcel(12_345, 1)));

        assertThat(v.declaredValueCents()).isEqualTo(12_345);
        assertThat(v.intrinsicValueCents()).isEqualTo(12_345);

        // Un intrínseco negativo (descuento mayor que el subtotal) se normaliza a 0, no se declara en
        // negativo ni se convierte en un recargo al revés.
        assertThat(valoracion.valuate("ES", -500, 0, List.of()).declaredValueCents()).isZero();
    }

    @Test
    @DisplayName("El recargo aduanero se suma al ENVÍO, no a la base imponible del IVA")
    void elRecargoAduaneroNoEntraEnLaBaseDelImpuesto() {
        dameTasaDeImpuesto("ES", 2100);

        // Subtotal descontado 100,00 $ + envío base 5,00 $ → base imponible 105,00 $ → IVA 21% = 22,05 $.
        // Aduana: un bulto con DOS partidas → 2 × 3,26 = 6,52 $, que se suma al envío DESPUÉS del IVA.
        List<DutyParcel> bultos = List.of(new DutyParcel(10_000, 2));
        CheckoutTotals t = totalesCheckout.compute("ES", null, 10_000, 500, bultos);

        assertThat(t.taxRateBps()).isEqualTo(2100);
        assertThat(t.taxCents()).isEqualTo(2205);
        assertThat(t.customsHandlingCents()).isEqualTo(652);
        assertThat(t.shippingBaseCents()).isEqualTo(500);
        assertThat(t.shippingCents()).isEqualTo(1152);
        assertThat(t.totalCents(10_000)).isEqualTo(13_357);

        // Si el recargo entrase en la base, el IVA sería (10.500 + 652) × 21% = 2.342 y el total 13.494:
        // 1,37 $ de más por pedido cobrados sobre la comisión del transportista.
        assertThat(t.taxCents()).isNotEqualTo(2342);
    }

    @Test
    @DisplayName("El impuesto se redondea UNA sola vez y al alza en el medio céntimo exacto")
    void elImpuestoSeRedondeaUnaVezYAlAlzaEnElMedioCentimo() {
        dameTasaDeImpuesto("ES", 2100);

        // 50 céntimos × 21% = 10,5 céntimos exactos → HALF_UP → 11. Es el único punto de redondeo: no se
        // redondea antes por línea ni se acumulan medios céntimos por el camino.
        CheckoutTotals t = totalesCheckout.compute("ES", null, 50, 0, List.of());
        assertThat(t.taxCents()).isEqualTo(11);
        assertThat(t.totalCents(50)).isEqualTo(61);

        // Un céntimo menos de base cae por debajo del medio céntimo y baja: 49 × 21% = 10,29 → 10.
        assertThat(totalesCheckout.compute("ES", null, 49, 0, List.of()).taxCents()).isEqualTo(10);
    }

    @Test
    @DisplayName("La vista previa y el cobro real dan el mismo importe: mismo servicio, mismas entradas")
    void laVistaPreviaYElCobroRealCoinciden() {
        dameTasaDeImpuesto("ES", 2100);
        List<DutyParcel> bultos = List.of(new DutyParcel(7_777, 3));

        // CheckoutPreviewService (previsualización) y OrderUseCaseImpl (cobro) delegan los dos en este
        // mismo CheckoutTotalsService; lo que se comprueba aquí es que el cálculo es DETERMINISTA, que es
        // lo que hace que ambos coincidan al céntimo llamada tras llamada.
        CheckoutTotals previa = totalesCheckout.compute("ES", null, 7_777, 349, bultos);
        CheckoutTotals cobro = totalesCheckout.compute("ES", null, 7_777, 349, bultos);

        assertThat(cobro).isEqualTo(previa);
        assertThat(previa.taxCents()).isEqualTo(1706);            // (7.777 + 349) × 21% = 1.706,46 → 1.706
        assertThat(previa.customsHandlingCents()).isEqualTo(978); // 3 partidas × 3,26 $
        assertThat(previa.shippingCents()).isEqualTo(1327);       // 349 + 978
        assertThat(previa.totalCents(7_777)).isEqualTo(10_810);   // 7.777 + 1.327 + 1.706
    }

    @Test
    @DisplayName("Un país sin regla aduanera no altera nada: envío tal cual y sin recargo")
    void unPaisSinReglaConfiguradaEsNeutro() {
        CustomsValuation v = valoracion.valuate("ZW", 20_000, 0, List.of(new DutyParcel(20_000, 4)));

        assertThat(v.handlingFeeCents()).isZero();
        assertThat(v.deMinimisExceeded()).isFalse();
        assertThat(v.blocked()).isFalse();
        assertThat(v.declaredValueCents()).isEqualTo(20_000);
    }

    /* ==================================================================================
     * 6. Hallazgos abiertos (deshabilitados a propósito: documentan un cobro que hoy NO cuadra)
     * ================================================================================== */

    @Test
    @Disabled("HALLAZGO ABIERTO: con el tope de valor del canal (15.500 c) por debajo de la franquicia de "
            + "la UE (16.304 c), el pedido se parte en bultos ANTES de medir el umbral y el bloqueo por "
            + "superar los 150 EUR nunca llega a dispararse. Requiere decisión: medir el umbral sobre el "
            + "pedido completo (como dice el javadoc de ParcelSplitter) o sobre cada bulto. Se deja "
            + "escrito, no se 'arregla' desde el test.")
    @DisplayName("Un pedido de 200 EUR a España debería bloquearse aunque viaje en varios bultos")
    void unPedidoPorEncimaDeLaFranquiciaDeberiaBloquearseAunqueSeReparta() {
        // 3 unidades de 70,00 USD = 210,00 USD de valor intrínseco, muy por encima de los 163,04 USD de
        // franquicia. El reparto por valor del canal (155 USD por bulto) deja dos bultos de 140 y 70, y
        // como el umbral se evalúa BULTO A BULTO, ninguno lo supera → no se bloquea y se cobra un pedido
        // que la línea contratada rechazará en destino.
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(linea(HS_VAQUEROS, 3, 7000, 400)));

        assertThat(bultos).hasSize(2);
        CustomsValuation v = valoracion.valuate("ES", 21_000, 0, bultos);

        assertThat(v.deMinimisExceeded()).as("210 $ superan la franquicia de 163,04 $").isTrue();
        assertThat(v.blocked()).as("debería impedirse antes de cobrar").isTrue();
    }

    @Test
    @DisplayName("Constatación del reparto que provoca el hallazgo anterior (sin juzgarlo)")
    void elRepartoPorValorDejaCadaBultoPorDebajoDeLaFranquicia() {
        // Este caso SÍ corre: fija los hechos medibles del hallazgo para que no se pierdan. El pedido de
        // 210,00 $ se reparte en dos bultos y cada uno queda por debajo de los 16.304 céntimos, que es
        // exactamente lo que impide que el umbral se dispare.
        List<DutyParcel> bultos = lineasAduaneras.parcelsOf(List.of(linea(HS_VAQUEROS, 3, 7000, 400)));

        assertThat(bultos).hasSize(2);
        assertThat(bultos.stream().mapToInt(DutyParcel::valueCents).sum()).isEqualTo(21_000);
        assertThat(bultos).allMatch(b -> b.valueCents() <= 15_500);
        assertThat(bultos).allMatch(b -> b.valueCents() < aCentimosUsd(new BigDecimal("150.00"), "EUR"));
    }

    /* ==================================================================================
     * Utilidades
     * ================================================================================== */

    /** Una línea de pedido lista para clasificar y pesar. Sin dimensiones ni batería: no influyen aquí. */
    private static Line linea(String hs, int cantidad, int precioUnitarioCents, int pesoGramos) {
        return new Line(UUID.randomUUID(), hs, cantidad, precioUnitarioCents, pesoGramos, 0, 0, 0, false);
    }

    /** Las reglas con franquicia configurada, que son las que tienen borde que comprobar. */
    private List<Map<String, Object>> reglasConUmbral() {
        return jdbcTemplate.queryForList("SELECT country_code, de_minimis_amount, de_minimis_currency, "
                + "per_article_fee_amount, per_article_fee_currency, over_threshold_policy, "
                + "handling_fee_cents, handling_percent_bps, vat_prepay_percent_bps "
                + "FROM country_customs_rule WHERE active = TRUE AND de_minimis_amount > 0 "
                + "ORDER BY country_code");
    }

    private int umbralEnCentimosUsd(Map<String, Object> regla) {
        return aCentimosUsd((BigDecimal) regla.get("de_minimis_amount"),
                (String) regla.get("de_minimis_currency"));
    }

    private int derechoPorLineaEnCentimosUsd(Map<String, Object> regla) {
        return aCentimosUsd((BigDecimal) regla.get("per_article_fee_amount"),
                (String) regla.get("per_article_fee_currency"));
    }

    /**
     * Importe legal → céntimos USD, calculado AQUÍ y no pidiéndoselo al servicio que se está probando:
     * dividir por la tasa con 4 decimales (HALF_UP) y pasar a céntimos con otro HALF_UP. Una divisa que no
     * esté en {@code currency_rate} se interpreta como si ya viniera en dólares.
     */
    private int aCentimosUsd(BigDecimal importe, String divisa) {
        if (importe == null || importe.signum() <= 0) {
            return 0;
        }
        BigDecimal enUsd = importe.setScale(4, RoundingMode.HALF_UP);
        if (divisa != null && !divisa.isBlank() && !"USD".equalsIgnoreCase(divisa)) {
            BigDecimal tasa = tasaVsUsd(divisa);
            if (tasa != null) {
                enUsd = importe.divide(tasa, 4, RoundingMode.HALF_UP);
            }
        }
        return enUsd.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** Tasa de la divisa contra el dólar tal y como está en la tabla; null si esa divisa no está. */
    private BigDecimal tasaVsUsd(String divisa) {
        List<BigDecimal> tasas = jdbcTemplate.queryForList(
                "SELECT rate_vs_usd FROM currency_rate WHERE UPPER(code) = UPPER(?)", BigDecimal.class,
                divisa);
        return tasas.isEmpty() ? null : tasas.get(0);
    }

    private void dameTasaDeImpuesto(String pais, int bps) {
        jdbcTemplate.update("INSERT INTO country_tax_rate (id, country_code, label, rate_bps, active) "
                + "VALUES (gen_random_uuid(), ?, ?, ?, TRUE) ON CONFLICT (country_code) DO UPDATE "
                + "SET rate_bps = EXCLUDED.rate_bps, active = TRUE", pais, "IVA " + pais, bps);
    }

    /** Acumula descuadres en vez de cortar en el primero: interesa la lista COMPLETA de países que fallan. */
    private static void anota(List<String> descuadres, String pais, String concepto, Object esperado,
            Object obtenido) {
        if (!esperado.equals(obtenido)) {
            descuadres.add("%s · %s: esperado %s, obtenido %s".formatted(pais, concepto, esperado, obtenido));
        }
    }

    private static int entero(Object valor) {
        return valor == null ? 0 : ((Number) valor).intValue();
    }

    /**
     * Repone {@code country_customs_rule} y {@code currency_rate} releyendo las migraciones de Liquibase y
     * reejecutando SOLO sus INSERT/UPDATE sobre esas dos tablas, en orden de versión.
     *
     * <p>No se copian los 52 umbrales a mano a propósito: un test que lleve su propia copia de los datos
     * deja de comprobar la configuración real en cuanto alguien cambie un umbral en una migración. Todas
     * esas sentencias son idempotentes por diseño ({@code ON CONFLICT DO NOTHING} en las inserciones), así
     * que reejecutarlas reconstruye exactamente el estado que dejó el arranque.
     */
    private void restauraSemillasDeMigraciones() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] migraciones = resolver.getResources("classpath*:db/changelog/*.sql");
            List<Resource> enOrden = Arrays.stream(migraciones)
                    .sorted(Comparator.comparingInt(CustomsDutyIT::versionDe)
                            .thenComparing(r -> r.getFilename() == null ? "" : r.getFilename()))
                    .toList();
            for (Resource migracion : enOrden) {
                for (String sentencia : sentenciasDe(migracion.getContentAsString(StandardCharsets.UTF_8))) {
                    if (afectaASemillasDeCalculo(sentencia)) {
                        jdbcTemplate.execute(sentencia);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudieron releer las migraciones de semillas", e);
        }
    }

    /** Número de versión del nombre del fichero ({@code schema-v88-...} → 88) para respetar el orden real. */
    private static int versionDe(Resource migracion) {
        Matcher m = VERSION_DE_MIGRACION.matcher(migracion.getFilename() == null ? "" : migracion.getFilename());
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    /** Sentencias sueltas del fichero, sin comentarios (que además llevan «;» dentro y romperían el corte). */
    private static List<String> sentenciasDe(String sql) {
        String sinComentarios = sql.replaceAll("--[^\n]*", "");
        List<String> sentencias = new ArrayList<>();
        for (String trozo : sinComentarios.split(";")) {
            String limpia = trozo.trim();
            if (!limpia.isEmpty()) {
                sentencias.add(limpia);
            }
        }
        return sentencias;
    }

    /** Solo se reejecutan las que siembran datos de cálculo; el DDL ya lo aplicó Liquibase al arrancar. */
    private static boolean afectaASemillasDeCalculo(String sentencia) {
        String normalizada = sentencia.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        return normalizada.startsWith("INSERT INTO COUNTRY_CUSTOMS_RULE")
                || normalizada.startsWith("UPDATE COUNTRY_CUSTOMS_RULE")
                || normalizada.startsWith("INSERT INTO CURRENCY_RATE")
                || normalizada.startsWith("UPDATE CURRENCY_RATE");
    }
}
