package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService.ParcelDeclaration;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * De dónde salen los datos que viajan en la declaración aduanera.
 *
 * <p>Cada dato tiene varias fuentes posibles y el orden importa. El nombre chino ya provocó un rechazo
 * real de guía: {@code product.title_zh} está poblado en el catálogo con el título en ESPAÑOL, así que
 * mirar ahí primero mandaba texto latino en un campo que YunExpress exige con ideogramas. El chino de
 * verdad vive en {@code product_translation}. El peso también dio problemas: un bulto declarado a 0
 * hacía que el transportista cotizara mal.
 *
 * <p>El pedido guarda una FOTO de los datos en el momento de la compra. Esa foto manda sobre el producto
 * vivo: si alguien reedita el producto un mes después, la guía de un pedido antiguo tiene que seguir
 * declarando lo que se vendió, no lo que ahora dice la ficha.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class YunExpressDeclarationSourcesTest {

    @Mock
    ProductRepository productRepository;

    private YunExpressFulfillmentService service() {
        return new YunExpressFulfillmentService(null, null, null, new CustomsDutyLinesService(null), productRepository, null, null, null);
    }

    private final UUID productId = UUID.randomUUID();

    private static ProductTranslationEntity translation(String lang, String title) {
        ProductTranslationEntity t = new ProductTranslationEntity();
        t.setLanguage(lang);
        t.setTitle(title);
        return t;
    }

    private ProductEntity product() {
        ProductEntity p = new ProductEntity();
        p.setId(productId);
        p.setHsCode("6109100000");
        when(productRepository.findById(productId)).thenReturn(Optional.of(p));
        return p;
    }

    private Order orderWith(OrderItem item) {
        Order o = new Order();
        o.setOrderNumber("NX-1");
        o.setCurrency("USD");
        o.setItems(List.of(item));
        return o;
    }

    private OrderItem item() {
        OrderItem it = new OrderItem();
        it.setProductId(productId);
        it.setQuantity(2);
        it.setUnitPriceCents(1250);
        it.setSkuSnapshot("SKU-1");
        return it;
    }

    private ParcelDeclaration declare(Order order) {
        return service().declaredParcels(order).get(0);
    }

    // ---------------------------------------------------------------- nombre chino

    @Test
    void elNombreChinoSaleDeLaTraduccionZhYNoDelTituloConTextoLatino() {
        // El bug real: title_zh contiene español, y mandarlo hace que YunExpress rechace la guía con
        // "CName : 必填，且不得为纯数字或纯字母".
        ProductEntity p = product();
        p.setTitleZh("Reloj de pulsera para hombre");
        p.setTranslations(List.of(translation("zh", "男士石英手表"), translation("en", "Men's quartz watch")));

        assertThat(declare(orderWith(item())).cName()).isEqualTo("男士石英手表");
    }

    @Test
    void siElPedidoYaGuardoElChinoSeUsaEseYNoSeMiraElProducto() {
        // La foto del pedido manda: reeditar el producto no debe cambiar la guía de una venta pasada.
        ProductEntity p = product();
        p.setTranslations(List.of(translation("zh", "编辑后的名称")));
        OrderItem it = item();
        it.setProductTitleZh("男士石英手表");

        assertThat(declare(orderWith(it)).cName()).isEqualTo("男士石英手表");
    }

    @Test
    void unChinoGuardadoEnElPedidoPeroEnLatinoSeIgnoraYSeBuscaElDeVerdad() {
        ProductEntity p = product();
        p.setTranslations(List.of(translation("zh", "男士石英手表")));
        OrderItem it = item();
        it.setProductTitleZh("Reloj de pulsera");   // latino: no sirve para la aduana china

        assertThat(declare(orderWith(it)).cName()).isEqualTo("男士石英手表");
    }

    @Test
    void sinNingunNombreChinoValidoElCampoQuedaVacioYLoDetectaElChequeoDeHuecos() {
        // Vale más una guía que no sale que una que sale mal: el hueco se detecta ANTES de despachar.
        ProductEntity p = product();
        p.setTitleZh("Reloj de pulsera");
        p.setTranslations(List.of(translation("es", "Reloj de pulsera")));

        ParcelDeclaration d = declare(orderWith(item()));

        assertThat(d.cName()).isNull();
        assertThat(service().customsGaps(List.of(d)))
                .anyMatch(g -> g.startsWith("sin nombre en chino"));
    }

    // ---------------------------------------------------------------- nombre inglés

    @Test
    void elNombreInglesPrefiereLaFotoDelPedidoLuegoLaTraduccionYPorUltimoElTituloGuardado() {
        ProductEntity p = product();
        p.setTranslations(List.of(translation("en", "Men's quartz watch")));

        OrderItem conFoto = item();
        conFoto.setProductTitles(Map.of("en", "Watch as sold"));
        assertThat(declare(orderWith(conFoto)).eName()).isEqualTo("Watch as sold");

        OrderItem sinFoto = item();
        assertThat(declare(orderWith(sinFoto)).eName()).isEqualTo("Men's quartz watch");

        OrderItem soloSnapshot = item();
        soloSnapshot.setTitleSnapshot("Reloj vendido");
        p.setTranslations(List.of());
        assertThat(declare(orderWith(soloSnapshot)).eName()).isEqualTo("Reloj vendido");
    }

    @Test
    void unNombreInglesEnBlancoEnElPedidoNoTapaLaTraduccionBuena() {
        ProductEntity p = product();
        p.setTranslations(List.of(translation("en", "Men's quartz watch")));
        OrderItem it = item();
        it.setProductTitles(Map.of("en", "   "));

        assertThat(declare(orderWith(it)).eName()).isEqualTo("Men's quartz watch");
    }

    // ---------------------------------------------------------------- peso

    @Test
    void elPesoSaleDeLaVarianteCompradaYNoDelProductoGenerico() {
        // Un mismo producto pesa distinto según la talla o el modelo; declarar el del producto hace que
        // el transportista cotice mal y reclame la diferencia después.
        ProductEntity p = product();
        p.setPackageWeightGrams(500);
        UUID variantId = UUID.randomUUID();
        ProductVariantEntity v = new ProductVariantEntity();
        v.setId(variantId);
        v.setPackageWeightGrams(300);
        p.setVariants(List.of(v));
        OrderItem it = item();
        it.setVariantId(variantId);

        assertThat(declare(orderWith(it)).unitWeightKg()).isEqualTo(0.3);
    }

    @Test
    void siLaVarianteNoTraePesoDeEmbalajeSeUsaElSuyoPropioYLuegoElDelProducto() {
        ProductEntity p = product();
        p.setPackageWeightGrams(500);
        UUID variantId = UUID.randomUUID();
        ProductVariantEntity v = new ProductVariantEntity();
        v.setId(variantId);
        v.setWeightGrams(250);          // sin peso de embalaje, pero sí peso propio
        p.setVariants(List.of(v));
        OrderItem it = item();
        it.setVariantId(variantId);

        assertThat(declare(orderWith(it)).unitWeightKg()).isEqualTo(0.25);

        ProductVariantEntity sinPeso = new ProductVariantEntity();
        sinPeso.setId(variantId);
        p.setVariants(List.of(sinPeso));
        assertThat(declare(orderWith(it)).unitWeightKg()).isEqualTo(0.5);
    }

    @Test
    void unPesoCeroSeDeclaraComoCeroYLoDetectaElChequeoDeHuecos() {
        // Un bulto a 0 kg es un error de datos, no un envío gratis: hay que verlo antes de despachar.
        product();

        ParcelDeclaration d = declare(orderWith(item()));

        assertThat(d.unitWeightKg()).isZero();
        assertThat(service().customsGaps(List.of(d))).anyMatch(g -> g.startsWith("sin peso unitario"));
    }

    // ---------------------------------------------------------------- importe y cantidad

    @Test
    void elValorDeclaradoEsElPrecioUnitarioPagadoEnLaDivisaDelPedido() {
        product();
        Order o = orderWith(item());
        o.setCurrency("eur");

        ParcelDeclaration d = declare(o);

        assertThat(d.unitPrice()).isEqualTo(12.5);
        assertThat(d.currencyCode()).isEqualTo("EUR");     // normalizada a mayúsculas
        assertThat(d.quantity()).isEqualTo(2);
    }

    @Test
    void sinDivisaEnElPedidoSeDeclaraEnDolares() {
        product();
        Order o = orderWith(item());
        o.setCurrency(null);

        assertThat(declare(o).currencyCode()).isEqualTo("USD");
    }

    @Test
    void unaCantidadCeroSeDeclaraComoUnaUnidad() {
        // La aduana no admite cantidad 0; una línea existe, luego va al menos una unidad.
        product();
        OrderItem it = item();
        it.setQuantity(0);

        assertThat(declare(orderWith(it)).quantity()).isEqualTo(1);
    }

    @Test
    void unaLineaSinProductoEnCatalogoSeDeclaraConLoQueGuardoElPedido() {
        // El producto puede haberse borrado después de la venta; la guía tiene que poder emitirse igual.
        OrderItem it = new OrderItem();
        it.setProductId(null);
        it.setQuantity(1);
        it.setUnitPriceCents(999);
        it.setTitleSnapshot("Producto retirado");
        it.setSkuSnapshot("SKU-X");

        ParcelDeclaration d = declare(orderWith(it));

        assertThat(d.eName()).isEqualTo("Producto retirado");
        assertThat(d.hsCode()).isNull();
        assertThat(d.unitPrice()).isEqualTo(9.99);
    }
}
