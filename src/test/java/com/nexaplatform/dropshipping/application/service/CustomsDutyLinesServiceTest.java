package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.DutyParcel;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService.Line;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Derecho temporal de 3 EUR de la Unión Europea (Reglamento (UE) 2026/382, en vigor del 1-jul-2026 al
 * 1-jul-2028).
 *
 * <p>Lo que se protege aquí es dinero real en las dos direcciones: cobrar de más al cliente por artículos
 * que la aduana cuenta como UNA sola línea, o cobrar de menos y pagar nosotros la diferencia al despachar.
 * La regla que se comprueba es la de la guía de la Comisión: el derecho «will automatically apply per
 * declaration line irrespective of the quantity (number of the articles) in that declaration line», siendo
 * una línea el conjunto de mercancías que comparten clasificación arancelaria.
 */
class CustomsDutyLinesServiceTest {

    private CustomsDutyLinesService service;

    @BeforeEach
    void sinLimitesDeBulto() {
        service = new CustomsDutyLinesService();
        // 0 = sin límite: todo viaja en un bulto. Los límites reales del canal se prueban aparte.
        ReflectionTestUtils.setField(service, "maxParcelWeightGrams", 0);
        ReflectionTestUtils.setField(service, "maxParcelValueCents", 0);
        ReflectionTestUtils.setField(service, "maxParcelUnits", 0);
    }

    /** Línea sin descripción: el caso del catálogo incompleto, donde solo la partida puede agrupar. */
    private static Line linea(String hs, int cantidad) {
        return linea(hs, cantidad, null);
    }

    /** Línea tal y como se declara de verdad: con la descripción que se le transmite al transportista. */
    private static Line linea(String hs, int cantidad, String descripcion) {
        return new Line(UUID.randomUUID(), hs, descripcion, "CN", cantidad, 1000, 500, 0, 0, 0, false);
    }

    /**
     * El pedido tipo de la tienda: cinco vaqueros, tres camisetas, unos zapatos, tres bóxer y un abrigo,
     * cada familia con su clasificación. Son CINCO líneas de declaración → 5 × 3 EUR = 15 EUR.
     * Contando artículos serían trece → 39 EUR, es decir 24 EUR de más al cliente en un solo pedido.
     */
    @Test
    void unPedidoDeCincoFamiliasPagaCincoLineas() {
        List<DutyParcel> bultos = service.parcelsOf(List.of(
                linea("620342", 5),   // vaqueros
                linea("610910", 3),   // camisetas
                linea("640399", 1),   // zapatos
                linea("610711", 3),   // bóxer
                linea("620293", 1))); // abrigo

        assertThat(bultos).hasSize(1);
        assertThat(bultos.get(0).tariffLines()).isEqualTo(5);
    }

    /** La cantidad NO multiplica: cinco unidades de la misma referencia son una línea, no cinco. */
    @Test
    void laCantidadNoMultiplicaElDerecho() {
        assertThat(service.parcelsOf(List.of(linea("620342", 5))).get(0).tariffLines()).isEqualTo(1);
        assertThat(service.parcelsOf(List.of(linea("620342", 1))).get(0).tariffLines()).isEqualTo(1);
    }

    /**
     * Ejemplo literal de la guía de la Comisión: un anorak, un cortavientos y una cazadora de tres
     * referencias DISTINTAS que comparten la subpartida 6104 19 son UNA línea (3 EUR), no tres (9 EUR).
     * Es justo el caso que antes se cobraba de más, porque se contaban productos distintos.
     *
     * <p>La condición que lo hace cierto es que las tres se declaren con la MISMA descripción, que es lo
     * que hace el declarante del ejemplo al meterlas en una sola línea del H7. Por eso van aquí con el
     * mismo texto y no con tres títulos: con tres descripciones serían tres «items» (art. 1(61)) y tres
     * derechos, que es lo que comprueba
     * {@link #dosDescripcionesDistintasConLaMismaSubpartidaSonDosLineas()}.
     */
    @Test
    void productosDistintosConLaMismaSubpartidaSonUnaSolaLinea() {
        List<DutyParcel> bultos = service.parcelsOf(List.of(
                linea("610419", 1, "Women's suit"),
                linea("610419", 1, "Women's suit"),
                linea("610419", 1, "Women's suit")));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(1);
    }

    /** Se agrupa por los 6 primeros dígitos (subpartida del H7), aunque el código venga con más detalle. */
    @Test
    void seAgrupaPorLosSeisPrimerosDigitos() {
        List<DutyParcel> bultos = service.parcelsOf(List.of(
                linea("6104199010", 1), linea("610419 90 20", 1), linea("6104.19.90.90", 1)));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(1);
    }

    /**
     * Sin clasificación no se puede agrupar: cada producto cuenta como línea propia. Se cobra de más en el
     * peor caso, nunca de menos — una línea sin cubrir la pagaríamos nosotros en el despacho.
     */
    @Test
    void unProductoSinClasificacionCuentaComoLineaPropia() {
        List<DutyParcel> bultos = service.parcelsOf(List.of(
                linea(null, 1), linea("", 2), linea("123", 1), linea("620342", 4)));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(4);
    }

    /**
     * La fuga: dos productos con la MISMA subpartida pero DISTINTA descripción son dos líneas de
     * declaración, porque la declaración que se transmite lleva una entrada por cada descripción y el
     * art. 1(61) del Reglamento Delegado (UE) 2015/2446 define «item» como las mercancías que comparten
     * clasificación, descripción Y origen — las tres, no solo la primera.
     *
     * <p>Agrupando solo por partida se cobraba UNA línea (3 EUR) y el transportista despachaba DOS
     * (6 EUR): los 3 EUR de diferencia los ponía el comercio en cada pedido con este patrón.
     */
    @Test
    void dosDescripcionesDistintasConLaMismaSubpartidaSonDosLineas() {
        List<DutyParcel> bultos = service.parcelsOf(List.of(
                linea("620342", 1, "Men denim trousers"),
                linea("620342", 1, "Women denim skirt")));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(2);
    }

    /**
     * La misma descripción escrita con otro espaciado o en otras mayúsculas es la misma mercancía: cobrar
     * dos derechos por una diferencia de tecleo sería cobrarle al cliente 3 EUR que no debe.
     */
    @Test
    void laDescripcionSeNormalizaEnEspaciosYMayusculas() {
        List<DutyParcel> bultos = service.parcelsOf(List.of(
                linea("620342", 1, "Cotton T-Shirt"),
                linea("620342", 1, "  cotton   t-shirt "),
                linea("620342", 1, "COTTON T-SHIRT")));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(1);
    }

    /**
     * Misma partida y misma descripción, orígenes distintos: son dos «items» según el art. 1(61), que
     * cierra la terna con «<i>and, if provided…, origin</i>».
     */
    @Test
    void dosOrigenesDistintosConLaMismaDescripcionSonDosLineas() {
        List<DutyParcel> bultos = service.parcelsOf(List.of(
                new Line(UUID.randomUUID(), "620342", "Denim trousers", "CN", 1, 1000, 500, 0, 0, 0, false),
                new Line(UUID.randomUUID(), "620342", "Denim trousers", "VN", 1, 1000, 500, 0, 0, 0, false)));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(2);
    }

    /**
     * Sin descripción no se separa: no hay dato que permita afirmar que dos mercancías de la misma partida
     * se declaran por separado, y separarlas por si acaso le cobraría al cliente un derecho de más.
     */
    @Test
    void sinDescripcionSoloAgrupaLaSubpartida() {
        List<DutyParcel> bultos = service.parcelsOf(List.of(
                linea("610419", 1, null),
                linea("610419", 1, "   "),
                linea("610419", 1, null)));

        assertThat(bultos.get(0).tariffLines()).isEqualTo(1);
    }

    /** Un carrito vacío no declara nada: no hay envío y no hay derecho que cobrar. */
    @Test
    void sinLineasNoHayBultosQueDeclarar() {
        assertThat(service.parcelsOf(List.of())).isEmpty();
        assertThat(service.parcelsOf(null)).isEmpty();
    }

    /**
     * La descripción que agrupa es el título en inglés del producto, que es el {@code EName} que se le
     * transmite al transportista. Sin traducción inglesa no hay descripción declarada y se devuelve nula:
     * inventarse una haría que la vista previa y el despacho contasen líneas distintas.
     */
    @Test
    void laDescripcionDeclaradaEsElTituloEnIngles() {
        assertThat(CustomsDutyLinesService.declaredDescriptionOf(producto("620342", "Men denim trousers")))
                .isEqualTo("Men denim trousers");
        assertThat(CustomsDutyLinesService.declaredDescriptionOf(producto("620342", null))).isNull();
        assertThat(CustomsDutyLinesService.declaredDescriptionOf(null)).isNull();
    }

    /**
     * La vista previa del checkout y el pedido arman la línea aduanera en clases distintas, y la única
     * forma de que el cliente vea un importe y se le cobre otro es que una clasifique distinto que la
     * otra. Aquí se reproducen las DOS construcciones sobre el mismo catálogo —la del pedido no pasa
     * medidas, la de la vista previa sí, que es la única diferencia real entre ambas— y se exige que
     * cuenten lo mismo.
     */
    @Test
    void laVistaPreviaYElPedidoCuentanLasMismasLineas() {
        ProductEntity pantalon = producto("620342", "Men denim trousers");
        ProductEntity falda = producto("620342", "Women denim skirt");

        List<DutyParcel> comoLaVistaPrevia = service.parcelsOf(List.of(
                lineaDe(pantalon, 200, 150, 30), lineaDe(falda, 180, 140, 25)));
        List<DutyParcel> comoElPedido = service.parcelsOf(List.of(
                lineaDe(pantalon, 0, 0, 0), lineaDe(falda, 0, 0, 0)));

        assertThat(comoLaVistaPrevia).isEqualTo(comoElPedido);
        assertThat(comoLaVistaPrevia.get(0).tariffLines()).isEqualTo(2);
    }

    /** Producto de catálogo con su partida y, si se le pasa, su título en inglés (el {@code EName}). */
    private static ProductEntity producto(String hs, String tituloEn) {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        p.setHsCode(hs);
        p.setCountryOfOrigin("CN");
        if (tituloEn != null) {
            ProductTranslationEntity en = new ProductTranslationEntity();
            en.setLanguage("en");
            en.setTitle(tituloEn);
            p.setTranslations(List.of(en));
        }
        return p;
    }

    /** La línea aduanera tal y como la construyen los dos llamantes reales, a partir del producto. */
    private static Line lineaDe(ProductEntity p, int largoMm, int anchoMm, int altoMm) {
        return new Line(p.getId(), p.getHsCode(), CustomsDutyLinesService.declaredDescriptionOf(p),
                p.getCountryOfOrigin(), 1, 1000, 500, largoMm, anchoMm, altoMm, false);
    }

    /**
     * Cuando el transportista parte la mercancía en varios bultos, cada uno lleva SU declaración: las
     * líneas se cuentan dentro de cada bulto, no una sola vez para todo el pedido.
     */
    @Test
    void conVariosBultosCadaUnoDeclaraSusPropiasLineas() {
        ReflectionTestUtils.setField(service, "maxParcelUnits", 2);

        List<DutyParcel> bultos = service.parcelsOf(List.of(linea("620342", 2), linea("610910", 2)));

        assertThat(bultos).hasSizeGreaterThan(1);
        assertThat(bultos.stream().mapToInt(DutyParcel::tariffLines).sum()).isGreaterThanOrEqualTo(2);
    }
}
