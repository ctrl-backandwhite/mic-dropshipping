package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Reparto de un pedido en varios bultos antes de pedir las guías.
 *
 * <p>El canal del transportista impone topes de peso, valor y número de unidades por bulto; lo que no
 * cabe viaja en otra guía. El reparto es SÓLO por esos límites físicos: partir un pedido para quedar por
 * debajo de un umbral aduanero sería fraccionamiento artificial, que en la UE está prohibido.
 *
 * <p>Un pedido sin dimensiones ni peso no puede quedarse sin repartir: si el peso unitario se leyera como
 * 0, todo cabría en un bulto y el transportista rechazaría la guía o cobraría la diferencia después.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class YunExpressOrderSplitTest {

    @Mock
    ProductRepository productRepository;

    /** Servicio con los topes del canal de pruebas: 2 kg, 24 $ y 10 unidades por bulto. */
    private YunExpressFulfillmentService service(int maxWeightGrams, int maxValueCents, int maxUnits) {
        YunExpressFulfillmentService s =
                new YunExpressFulfillmentService(null, null, null, new CustomsDutyLinesService(null), productRepository, null, null, null);
        ReflectionTestUtils.setField(s, "maxParcelWeightGrams", maxWeightGrams);
        ReflectionTestUtils.setField(s, "maxParcelValueCents", maxValueCents);
        ReflectionTestUtils.setField(s, "maxParcelUnits", maxUnits);
        return s;
    }

    private OrderItem line(UUID productId, int qty, int unitPriceCents) {
        OrderItem it = new OrderItem();
        it.setProductId(productId);
        it.setQuantity(qty);
        it.setUnitPriceCents(unitPriceCents);
        it.setSkuSnapshot("SKU-" + productId.toString().substring(0, 4));
        return it;
    }

    private UUID productWeighing(int grams) {
        UUID id = UUID.randomUUID();
        ProductEntity p = new ProductEntity();
        p.setId(id);
        p.setPackageWeightGrams(grams);
        when(productRepository.findById(id)).thenReturn(Optional.of(p));
        return id;
    }

    private Order order(OrderItem... items) {
        Order o = new Order();
        o.setOrderNumber("NX-1");
        o.setCurrency("USD");
        o.setItems(new ArrayList<>(List.of(items)));
        return o;
    }

    private List<?> bins(YunExpressFulfillmentService s, Order o) {
        return s.splitOrderBins(o).bins();
    }

    // ---------------------------------------------------------------- cuándo NO se reparte

    @Test
    void unPedidoQueCabeEnElCanalViajaEnUnSoloBulto() {
        UUID p = productWeighing(300);

        assertThat(bins(service(2000, 2400, 10), order(line(p, 2, 500)))).hasSize(1);
    }

    @Test
    void conLosLimitesDesactivadosTodoViajaJunto() {
        // Límite 0 = "sin tope configurado": el comportamiento de antes de que existiera el reparto.
        UUID p = productWeighing(3000);

        assertThat(bins(service(0, 0, 0), order(line(p, 10, 5000)))).hasSize(1);
    }

    // ---------------------------------------------------------------- por qué se reparte

    @Test
    void sePartePorPesoCuandoElCanalNoAdmiteMas() {
        UUID p = productWeighing(800);   // 3 unidades = 2400 g > 2000 g

        assertThat(bins(service(2000, 100_000, 100), order(line(p, 3, 100)))).hasSize(2);
    }

    @Test
    void sePartePorValorDeclaradoCuandoElCanalNoAdmiteMas() {
        UUID p = productWeighing(10);

        // 3 × 10,00 $ = 30,00 $ por encima del tope de 24,00 $ del canal
        assertThat(bins(service(100_000, 2400, 100), order(line(p, 3, 1000)))).hasSize(2);
    }

    @Test
    void sePartePorNumeroDeUnidadesCuandoElCanalNoAdmiteMas() {
        UUID p = productWeighing(10);

        assertThat(bins(service(100_000, 100_000, 2), order(line(p, 5, 10)))).hasSize(3);
    }

    @Test
    void unPedidoDeVariasLineasSeRepartePorLosLimitesYNoPorLinea() {
        // Dos líneas ligeras caben juntas: repartir por línea generaría guías de más y costaría dinero.
        UUID ligero1 = productWeighing(200);
        UUID ligero2 = productWeighing(200);

        assertThat(bins(service(2000, 100_000, 100), order(line(ligero1, 1, 100), line(ligero2, 1, 100))))
                .hasSize(1);
    }

    // ---------------------------------------------------------------- datos incompletos

    @Test
    void unProductoQueNoEstaEnCatalogoCuentaComoMedioKiloYNoComoCero() {
        // Si contara 0 g, un pedido entero de productos borrados cabría en un bulto y el transportista
        // rechazaría la guía al pesarlo.
        OrderItem huerfano = line(UUID.randomUUID(), 5, 100);
        when(productRepository.findById(huerfano.getProductId())).thenReturn(Optional.empty());

        // 5 × 500 g = 2500 g > 2000 g -> hay que repartir
        assertThat(bins(service(2000, 100_000, 100), order(huerfano))).hasSize(2);
    }

    @Test
    void unaLineaSinCantidadCuentaComoUnaUnidad() {
        UUID p = productWeighing(300);
        OrderItem it = line(p, 0, 100);

        assertThat(bins(service(2000, 100_000, 100), order(it))).hasSize(1);
    }

    @Test
    void elPesoDeLaVarianteCompradaMandaSobreElDelProducto() {
        // El reparto tiene que usar el peso de lo que de verdad se envía.
        UUID id = UUID.randomUUID();
        ProductEntity p = new ProductEntity();
        p.setId(id);
        p.setPackageWeightGrams(100);          // el genérico es ligero...
        UUID variantId = UUID.randomUUID();
        ProductVariantEntity v = new ProductVariantEntity();
        v.setId(variantId);
        v.setPackageWeightGrams(900);          // ...pero la variante comprada pesa
        p.setVariants(List.of(v));
        when(productRepository.findById(id)).thenReturn(Optional.of(p));

        OrderItem it = line(id, 3, 100);
        it.setVariantId(variantId);

        // 3 × 900 g = 2700 g: con el peso del producto (300 g) habría cabido en uno solo.
        assertThat(bins(service(2000, 100_000, 100), order(it))).hasSize(2);
    }

    @Test
    void unaUnidadQueYaSuperaElTopeViajaSolaEnSuBulto() {
        // No se puede partir una unidad: se emite su guía y que el canal la rechace si no la admite, en
        // vez de mezclarla con otras y tumbar el pedido entero.
        UUID pesado = productWeighing(5000);
        UUID ligero = productWeighing(100);

        assertThat(bins(service(2000, 100_000, 100), order(line(pesado, 1, 100), line(ligero, 1, 100))))
                .hasSize(2);
    }
}
