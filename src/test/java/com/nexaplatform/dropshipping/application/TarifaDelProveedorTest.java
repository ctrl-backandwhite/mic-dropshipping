package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.mapper.ProductBulkExportMapper;
import com.nexaplatform.dropshipping.application.service.BulkProductFields;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lo que el proveedor cobra de verdad por el porte nacional chino, medido en su ficha.
 *
 * <p>Hasta ahora el ecommerce recibía un ÚNICO importe por variante —{@code shippingCny}, los 6 /
 * 10 / 16 CNY por tramos de peso— y eso sirve para una unidad. Pero el proveedor no cobra por
 * peso: cobra <b>una primera unidad y un incremento por cada siguiente</b>. Medido sobre diez
 * puntos de una ficha real el 25-sep-2026: ¥8 la primera y ¥3 cada siguiente, exacto.
 *
 * <p>Con sólo el importe de una unidad, un pedido de cinco calculaba 5 × 8 = ¥40 cuando el
 * proveedor cobra 8 + 4 × 3 = ¥20. El error es del doble, y siempre en contra: se cotiza de más y
 * se pierde la venta.
 *
 * <p><b>Las dos direcciones tienen que seguir funcionando.</b> Un crawler que todavía no mande la
 * tarifa no puede romper la carga, y este backend no puede exigirla: los 7.649 productos ya
 * cargados no la traen.
 */
class TarifaDelProveedorTest {

    private static BulkProductDtoIn.BulkVariant variante(BigDecimal primera, BigDecimal siguiente) {
        BulkProductDtoIn.BulkVariant v = new BulkProductDtoIn.BulkVariant();
        v.setSku("SKU-1");
        v.setOptionValues(Map.of("Talla", "M"));
        if (primera != null || siguiente != null) {
            v.setSupplierShipping(new BulkProductDtoIn.BulkSupplierShipping(primera, siguiente));
        }
        return v;
    }

    private static ProductEntity productoCon(BulkProductDtoIn.BulkVariant... variantes) {
        ProductEntity p = new ProductEntity();
        ProductVariantEntity pv = new ProductVariantEntity();
        pv.setSku("SKU-1");
        p.setVariants(new java.util.ArrayList<>(List.of(pv)));
        BulkProductDtoIn r = new BulkProductDtoIn();
        r.setVariants(List.of(variantes));
        BulkProductFields.applyVariantLogistics(p, r);
        return p;
    }

    @Test
    void la_tarifa_llega_hasta_la_variante() {
        ProductEntity p = productoCon(variante(new BigDecimal("8"), new BigDecimal("3")));

        ProductVariantEntity pv = p.getVariants().get(0);
        assertThat(pv.getSupplierShipFirstCny()).isEqualByComparingTo("8");
        assertThat(pv.getSupplierShipExtraCny()).isEqualByComparingTo("3");
    }

    @Test
    void el_porte_de_cinco_unidades_no_es_cinco_veces_el_de_una() {
        ProductVariantEntity pv = productoCon(variante(new BigDecimal("8"), new BigDecimal("3"))).getVariants().get(0);

        assertThat(pv.porteDe(5)).isEqualByComparingTo("20");
    }

    @Test
    void una_sola_unidad_paga_la_primera() {
        ProductVariantEntity pv = productoCon(variante(new BigDecimal("8"), new BigDecimal("3"))).getVariants().get(0);

        assertThat(pv.porteDe(1)).isEqualByComparingTo("8");
    }

    @Test
    void sin_tarifa_medida_no_se_inventa_un_porte() {
        ProductVariantEntity pv = productoCon(variante(null, null)).getVariants().get(0);

        assertThat(pv.getSupplierShipFirstCny()).isNull();
        assertThat(pv.porteDe(5)).isNull();
    }

    @Test
    void un_pedido_de_cero_unidades_no_paga_porte() {
        ProductVariantEntity pv = productoCon(variante(new BigDecimal("8"), new BigDecimal("3"))).getVariants().get(0);

        assertThat(pv.porteDe(0)).isEqualByComparingTo("0");
    }

    /**
     * EL control de la retrocompatibilidad hacia atrás: el crawler viejo manda la variante sin
     * hablar de la tarifa. Antes de esto había 7.649 productos así, y una carga que exigiera el
     * campo los habría tumbado todos.
     */
    @Test
    void un_bulk_que_no_habla_de_la_tarifa_no_la_borra() {
        ProductEntity p = new ProductEntity();
        ProductVariantEntity pv = new ProductVariantEntity();
        pv.setSku("SKU-1");
        pv.setSupplierShipFirstCny(new BigDecimal("8"));
        pv.setSupplierShipExtraCny(new BigDecimal("3"));
        p.setVariants(new java.util.ArrayList<>(List.of(pv)));

        BulkProductDtoIn r = new BulkProductDtoIn();
        r.setVariants(List.of(variante(null, null)));
        BulkProductFields.applyVariantLogistics(p, r);

        assertThat(p.getVariants().get(0).getSupplierShipFirstCny()).isEqualByComparingTo("8");
    }

    /**
     * Un incremento de CERO sí es un dato: hay proveedores con porte plano. Tratarlo como ausencia
     * dejaría puesto el incremento anterior y cobraría de más en cada unidad extra.
     */
    @Test
    void un_incremento_de_cero_se_guarda() {
        ProductVariantEntity pv = productoCon(variante(new BigDecimal("8"), BigDecimal.ZERO)).getVariants().get(0);

        assertThat(pv.getSupplierShipExtraCny()).isEqualByComparingTo("0");
        assertThat(pv.porteDe(10)).isEqualByComparingTo("8");
    }

    /**
     * La tarifa a medias, que es el hueco que descubrió la mutación.
     *
     * <p>Un crawler puede mandar la primera unidad y callarse el incremento —midió un solo punto,
     * o la página no recalculó—. Escribir un nulo encima del incremento medido lo borraría, y a
     * partir de ahí cada unidad extra saldría gratis.
     */
    @Test
    void un_bulk_que_solo_manda_la_primera_no_borra_el_incremento() {
        ProductEntity p = new ProductEntity();
        ProductVariantEntity pv = new ProductVariantEntity();
        pv.setSku("SKU-1");
        pv.setSupplierShipExtraCny(new BigDecimal("3"));
        p.setVariants(new java.util.ArrayList<>(List.of(pv)));

        BulkProductDtoIn r = new BulkProductDtoIn();
        r.setVariants(List.of(variante(new BigDecimal("9"), null)));
        BulkProductFields.applyVariantLogistics(p, r);

        ProductVariantEntity guardada = p.getVariants().get(0);
        assertThat(guardada.getSupplierShipFirstCny()).isEqualByComparingTo("9");
        assertThat(guardada.getSupplierShipExtraCny()).isEqualByComparingTo("3");
    }

    /**
     * Y si de verdad no hay incremento en ninguna parte, el porte no puede reventar: una tarifa a
     * medias es peor que ninguna si tumba el cálculo del pedido entero.
     */
    @Test
    void sin_incremento_el_porte_es_el_de_la_primera_unidad() {
        ProductVariantEntity pv = productoCon(variante(new BigDecimal("8"), null)).getVariants().get(0);

        assertThat(pv.getSupplierShipExtraCny()).isNull();
        assertThat(pv.porteDe(5)).isEqualByComparingTo("8");
    }

    /**
     * Y al revés: un bulk que manda sólo el incremento no borra la primera unidad.
     *
     * <p>Lo destapó la mutación. Sin este control, la simetría del método era una apariencia:
     * una mitad estaba protegida por su test y la otra no, y una tarifa con la primera unidad a
     * nulo no se puede calcular — {@code porteDe} devuelve nulo y el pedido se cotiza sin flete.
     */
    @Test
    void un_bulk_que_solo_manda_el_incremento_no_borra_la_primera() {
        ProductEntity p = new ProductEntity();
        ProductVariantEntity pv = new ProductVariantEntity();
        pv.setSku("SKU-1");
        pv.setSupplierShipFirstCny(new BigDecimal("8"));
        p.setVariants(new java.util.ArrayList<>(List.of(pv)));

        BulkProductDtoIn r = new BulkProductDtoIn();
        r.setVariants(List.of(variante(null, new BigDecimal("4"))));
        BulkProductFields.applyVariantLogistics(p, r);

        ProductVariantEntity guardada = p.getVariants().get(0);
        assertThat(guardada.getSupplierShipFirstCny()).isEqualByComparingTo("8");
        assertThat(guardada.getSupplierShipExtraCny()).isEqualByComparingTo("4");
    }

    /**
     * La tarifa es del PROVEEDOR, así que la variante que no declara la suya hereda la del
     * producto.
     *
     * <p>No es un adorno: el crawler sólo consigue medir el 67% de las fichas, y cuando lo logra
     * la medida vale para el proveedor entero. Sin la herencia, una variante añadida después se
     * quedaría sin tarifa aunque su producto la tenga.
     */
    @Test
    void la_variante_hereda_la_tarifa_del_producto() {
        ProductEntity p = new ProductEntity();
        ProductVariantEntity pv = new ProductVariantEntity();
        pv.setSku("SKU-1");
        p.setVariants(new java.util.ArrayList<>(List.of(pv)));

        BulkProductDtoIn r = new BulkProductDtoIn();
        r.setSupplierShipping(new BulkProductDtoIn.BulkSupplierShipping(new BigDecimal("6"), new BigDecimal("2")));
        r.setVariants(List.of(variante(null, null)));
        BulkProductFields.applyVariantLogistics(p, r);

        assertThat(p.getVariants().get(0).getSupplierShipFirstCny()).isEqualByComparingTo("6");
        assertThat(p.getVariants().get(0).getSupplierShipExtraCny()).isEqualByComparingTo("2");
    }

    /**
     * EL control: lo que la variante declara manda sobre lo heredado. Una talla que se midió
     * aparte no puede perder su medida por el promedio del proveedor.
     */
    @Test
    void lo_que_la_variante_declara_manda_sobre_lo_heredado() {
        ProductEntity p = new ProductEntity();
        ProductVariantEntity pv = new ProductVariantEntity();
        pv.setSku("SKU-1");
        p.setVariants(new java.util.ArrayList<>(List.of(pv)));

        BulkProductDtoIn r = new BulkProductDtoIn();
        r.setSupplierShipping(new BulkProductDtoIn.BulkSupplierShipping(new BigDecimal("6"), new BigDecimal("2")));
        r.setVariants(List.of(variante(new BigDecimal("8"), new BigDecimal("3"))));
        BulkProductFields.applyVariantLogistics(p, r);

        assertThat(p.getVariants().get(0).getSupplierShipFirstCny()).isEqualByComparingTo("8");
    }

    /**
     * La tarifa tiene que sobrevivir al volcado que propaga el producto al otro entorno.
     *
     * <p>El bus no manda el producto entero: manda identificadores, y el destino lo reimporta con
     * el MISMO formato de bulk que produce {@link ProductBulkExportMapper}. Lo que no salga en ese
     * volcado no llega a producción, y no hay ningún error que lo delate — igual que le pasó al
     * envío por variante, que se perdía en cada ida y vuelta «en silencio» hasta que se añadió su
     * línea.
     *
     * <p>Con la tarifa perdida, pro vuelve a calcular cinco unidades como cinco veces la primera:
     * ¥40 donde el proveedor cobra ¥20.
     */
    @Test
    void la_tarifa_sobrevive_al_volcado_que_propaga_a_produccion() {
        ProductVariantEntity pv = new ProductVariantEntity();
        pv.setSku("SKU-1");
        pv.setSupplierShipFirstCny(new BigDecimal("8"));
        pv.setSupplierShipExtraCny(new BigDecimal("3"));

        BulkProductDtoIn.BulkVariant volcada = volcar(pv);

        assertThat(volcada.getSupplierShipping()).isNotNull();
        assertThat(volcada.getSupplierShipping().getFirstUnitCny()).isEqualByComparingTo("8");
        assertThat(volcada.getSupplierShipping().getExtraUnitCny()).isEqualByComparingTo("3");
    }

    /**
     * EL control: una variante sin tarifa medida no inventa un bloque vacío en el volcado. Un
     * {@code supplierShipping} con los dos importes a nulo llegaría al destino y no diría nada,
     * pero ensucia el payload de las dos de cada tres fichas que no se pueden sondar.
     */
    @Test
    void sin_tarifa_el_volcado_no_lleva_el_bloque() {
        ProductVariantEntity pv = new ProductVariantEntity();
        pv.setSku("SKU-1");

        assertThat(volcar(pv).getSupplierShipping()).isNull();
    }

    /** El volcado real que usa el bus para propagar al otro entorno. */
    private static BulkProductDtoIn.BulkVariant volcar(ProductVariantEntity pv) {
        ProductEntity p = ProductEntity.builder().externalId("1688-1").build();
        p.setVariants(new java.util.ArrayList<>(List.of(pv)));

        return new ProductBulkExportMapper().toBulk(p, null, null, null, null).getVariants().get(0);
    }
}
