package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService.ParcelDeclaration;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * La guía tiene que declarar lo MISMO que se le cobró al cliente.
 *
 * <p>El derecho de 3 EUR se cobra por línea de declaración. Si la vista previa cuenta una línea —porque
 * los dos productos comparten grupo aprobado— y la guía transmite dos porque emite una entrada por
 * artículo, la aduana cobra dos derechos y el segundo lo pone el comercio al despachar. Aquí se fija que
 * la guía fusione exactamente igual que cuenta el checkout.
 *
 * <p>Y se fija algo que no puede cambiar al fusionar: <b>el valor declarado total</b>. Sobre él se miden
 * el umbral de 150 EUR del régimen y la base del IVA; repartirlo distinto entre líneas está bien, alterar
 * la suma no.
 */
class YunExpressDeclaracionAgrupadaTest {

    private final ProductRepository products = mock(ProductRepository.class);

    /** Solo se ejercita la declaración: el resto de colaboradores no participan en este cálculo. */
    private final YunExpressFulfillmentService service = new YunExpressFulfillmentService(null, null, null,
            null, products, null, null, null);

    @Test
    void fusionaLasLineasQueSeDeclaranIgualYConservaElValorTotal() {
        Order pedido = pedidoCon(
                linea("Men's woven cotton trousers", "620443", 1, 1240),
                linea("Men's woven cotton trousers", "620443", 1, 980),
                linea("Knitted cotton t-shirts", "610990", 1, 620));

        List<ParcelDeclaration> lineas = service.declaredParcels(pedido);

        assertThat(lineas).hasSize(2);
        ParcelDeclaration pantalones = lineas.stream()
                .filter(l -> "Men's woven cotton trousers".equals(l.eName())).findFirst().orElseThrow();
        assertThat(pantalones.quantity()).isEqualTo(2);
        assertThat(pantalones.quantity() * pantalones.unitPrice()).isEqualTo(22.20, within(0.01));

        double total = lineas.stream().mapToDouble(l -> l.quantity() * l.unitPrice()).sum();
        assertThat(total).isEqualTo(28.40, within(0.01));
    }

    @Test
    void laMismaPartidaConDescripcionesDISTINTASSiguenSiendoDosLineas() {
        // Sin grupo aprobado cada producto conserva su descripción, y la aduana los cuenta por separado.
        // Fusionarlos aquí cobraría de menos y la diferencia la pondría el comercio.
        Order pedido = pedidoCon(
                linea("Blue denim jeans", "620443", 1, 1240),
                linea("Beige chino trousers", "620443", 1, 980));

        assertThat(service.declaredParcels(pedido)).hasSize(2);
    }

    /**
     * Sin partida arancelaria, cada producto es su propia línea — igual que al cobrar.
     *
     * <p>El cobro ({@code CustomsDutyLinesService.classificationKey}) NUNCA agrupa sin código HS: la clave
     * es el propio producto, porque sin ese dato nada permite afirmar que dos mercancías se declaran
     * juntas. La guía sí las agrupaba, por descripción y origen. Resultado: dos productos sin HS con la
     * misma descripción y el mismo origen se cobraban como DOS derechos y se declaraban como UNA línea.
     *
     * <p>Los dos javadoc prometían que la terna era la misma en ambos lados. No lo era. Ahora la clave la
     * compone una sola función y no pueden volver a separarse.
     */
    @Test
    void sinPartidaArancelariaCadaProductoEsSuPropiaLineaComoAlCobrar() {
        Order pedido = pedidoCon(
                linea("Cotton tote bag", null, 1, 1240),
                linea("Cotton tote bag", null, 1, 1240));

        assertThat(service.declaredParcels(pedido))
                .as("sin HS el cobro cuenta dos derechos; la guía tiene que declarar dos líneas")
                .hasSize(2);
    }

    @Test
    void laCantidadSeSumaSinMultiplicarElDerecho() {
        // Cinco unidades de la misma referencia son UNA línea: la cantidad no multiplica el derecho.
        Order pedido = pedidoCon(
                linea("Knitted cotton t-shirts", "610990", 3, 620),
                linea("Knitted cotton t-shirts", "610990", 2, 620));

        List<ParcelDeclaration> lineas = service.declaredParcels(pedido);

        assertThat(lineas).singleElement().extracting(ParcelDeclaration::quantity).isEqualTo(5);
    }

    @Test
    void laLineaFusionadaDeclaraElChinoConElQueSeCongeloElPedido() {
        // El snapshot manda también en chino: la línea lleva un solo CName, y tiene que describir lo mismo
        // que su EName. Sin esto, la línea saldría con el genérico en inglés y el título concreto del
        // primer artículo en chino — la aduana leería dos mercancías distintas en la misma línea.
        Order pedido = pedidoCon(
                lineaZh("Women's or girls' dresses, of synthetic fibres", "女式合成纤维制连衣裙", "620443", 1, 1240),
                lineaZh("Women's or girls' dresses, of synthetic fibres", "女式合成纤维制连衣裙", "620443", 1, 980));

        List<ParcelDeclaration> lineas = service.declaredParcels(pedido);

        assertThat(lineas).singleElement()
                .extracting(ParcelDeclaration::cName).isEqualTo("女式合成纤维制连衣裙");
    }

    @Test
    void sinChinoCongeladoLaGuiaSigueTomandoElTituloDelProducto() {
        // Los pedidos anteriores a la columna no tienen snapshot en chino y se declaran como siempre.
        Order pedido = pedidoCon(lineaZh("Blue denim jeans", null, "620443", 1, 1240));

        assertThat(service.declaredParcels(pedido)).singleElement()
                .extracting(ParcelDeclaration::cName).isEqualTo("蓝色牛仔裤");
    }

    private Order pedidoCon(OrderItem... items) {
        Order o = Order.builder().currency("EUR").items(new ArrayList<>(List.of(items))).build();
        o.setId(UUID.randomUUID());
        return o;
    }

    private OrderItem linea(String descripcionDeclarada, String hs, int cantidad, int unitarioCents) {
        UUID productId = UUID.randomUUID();
        ProductEntity p = new ProductEntity();
        p.setId(productId);
        p.setHsCode(hs);
        p.setCountryOfOrigin("CN");
        p.setCustomsMaterial("COTTON");
        p.setCustomsUsage("CASUAL WEAR");
        p.setPackageWeightGrams(200);
        lenient().when(products.findById(productId)).thenReturn(Optional.of(p));
        return OrderItem.builder().productId(productId).declaredDescription(descripcionDeclarada)
                .quantity(cantidad).unitPriceCents(unitarioCents).build();
    }

    private OrderItem lineaZh(String descripcionDeclarada, String chinoCongelado, String hs, int cantidad,
            int unitarioCents) {
        UUID productId = UUID.randomUUID();
        ProductEntity p = new ProductEntity();
        p.setId(productId);
        p.setHsCode(hs);
        p.setCountryOfOrigin("CN");
        p.setCustomsMaterial("POLYESTER");
        p.setCustomsUsage("CASUAL WEAR");
        p.setPackageWeightGrams(200);
        p.setTitleZh("蓝色牛仔裤");
        lenient().when(products.findById(productId)).thenReturn(Optional.of(p));
        return OrderItem.builder().productId(productId).declaredDescription(descripcionDeclarada)
                .declaredDescriptionZh(chinoCongelado).quantity(cantidad).unitPriceCents(unitarioCents).build();
    }
}
