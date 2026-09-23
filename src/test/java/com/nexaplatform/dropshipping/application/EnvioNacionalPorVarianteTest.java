package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantView;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.SupplierMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El envío nacional chino viaja POR VARIANTE, y tiene que llegar hasta lo que se pinta.
 *
 * <p>El scraper lo calcula por tramos de peso —6 CNY por debajo de 500 g, 10 hasta 1 kg, 16 por
 * encima— porque una misma ficha tiene tallas que pesan el doble que otras. Antes de esto el
 * ecommerce sólo conocía el envío del PRODUCTO, así que a la talla pesada se le cobraba el flete
 * de la ligera.
 *
 * <p>Lo que se rompería si estas pruebas fallasen: el importe llega a la base de datos, nadie lo
 * pinta, y el panel sigue enseñando un único envío para todas las variantes. Es el fallo más caro
 * de detectar, porque no hay error en ninguna parte — simplemente falta un número en la pantalla.
 */
class EnvioNacionalPorVarianteTest {

    private ProductMapper productMapper;

    @BeforeEach
    void setup() {
        SupplierMapper supplierMapper = mock(SupplierMapper.class);

        CurrencyRateService currencyService = mock(CurrencyRateService.class);
        when(currencyService.toUsd(Mockito.any(), Mockito.anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.usdToDisplay(Mockito.any())).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(Mockito.anyString())).thenReturn("$");
        when(currencyService.localeOf(Mockito.anyString())).thenReturn("en-US");

        MarginService marginService = mock(MarginService.class);
        when(marginService.apply(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> new MarginService.PriceWithMargin(inv.getArgument(0), inv.getArgument(0), null,
                        BigDecimal.ZERO));

        CustomsValuationService aduanas = mock(CustomsValuationService.class);
        lenient().when(aduanas.perArticleFeeUsdCents(any())).thenReturn(0);
        PromotionService sinPromociones = sinPromociones();
        PricingService pricingService = new PricingService(currencyService, sinPromociones, marginService, aduanas);

        productMapper = new ProductMapper(supplierMapper, pricingService, currencyService, marginService,
                mock(com.nexaplatform.dropshipping.application.service.EuComplianceService.class));
    }

    @Test
    void loQueSePintaDeUnaVarianteLlevaSuPropioEnvio() {
        ProductVariantEntity v = varianteCon(new BigDecimal("10"), 800);

        VariantView vista = productMapper.toVariantView(v.getProduct(), v, "es");

        assertThat(vista.shippingCny()).isEqualByComparingTo("10");
    }

    @Test
    void dosVariantesDelMismoProductoPintanImportesDistintos() {
        // EL caso que motivó todo: una talla de 800 g y otra de 1,2 kg no cuestan lo mismo de
        // enviar. Si esta prueba fallase volveríamos a un único importe por ficha y la diferencia
        // la pagaría el vendedor en cada pedido de la talla grande.
        ProductVariantEntity ligera = varianteCon(new BigDecimal("10"), 800);
        ProductVariantEntity pesada = varianteCon(new BigDecimal("16"), 1200);

        VariantView vistaLigera = productMapper.toVariantView(ligera.getProduct(), ligera, "es");
        VariantView vistaPesada = productMapper.toVariantView(pesada.getProduct(), pesada, "es");

        assertThat(vistaLigera.shippingCny()).isEqualByComparingTo("10");
        assertThat(vistaPesada.shippingCny()).isEqualByComparingTo("16");
    }

    @Test
    void unaVarianteSinEnvioPropioLoPintaVacioYNoACero() {
        // Un cero dice "el envío es gratis" y un vacío dice "esta variante no declara envío
        // propio". Confundirlos haría que las 225.959 variantes ya cargadas —que no traen el
        // campo— apareciesen como envío gratuito en el panel.
        ProductVariantEntity v = varianteCon(null, 300);

        VariantView vista = productMapper.toVariantView(v.getProduct(), v, "es");

        assertThat(vista.shippingCny()).isNull();
    }

    /** Sin promociones, pero con un descuento neutro: un mock a secas devuelve null y revienta. */
    private static PromotionService sinPromociones() {
        PromotionService p = mock(PromotionService.class);
        lenient().when(p.applyAutomatic(any(), any(), any())).thenAnswer(inv -> {
            BigDecimal precio = inv.getArgument(1);
            return new PromotionService.Discounted(precio, precio, BigDecimal.ZERO, null, null);
        });
        return p;
    }

    @Test
    void elExportDelBackendNoPierdeElEnvioDeCadaVariante() {
        // Lo que se rompería: el backend re-exporta el catálogo en el mismo formato bulk que
        // importa, y ese volcado es lo que se vuelve a subir. Si el envío por variante no sale,
        // cada ida y vuelta lo borra en silencio — y el dato sólo se echa de menos cuando llega la
        // factura del transportista.
        ProductVariantEntity v = varianteCon(new java.math.BigDecimal("16"), 1200);

        com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn exportado = new com.nexaplatform.dropshipping.api.mapper.ProductBulkExportMapper()
                .toBulk(v.getProduct(), List.of(), List.of(), List.of(), List.of());

        assertThat(exportado.getVariants().get(0).getShippingCny()).isEqualByComparingTo("16");
    }

    private static ProductVariantEntity varianteCon(BigDecimal envio, Integer pesoGramos) {
        ProductEntity p = ProductEntity.builder().source("1688").externalId("X").basePrice(new BigDecimal("10"))
                .currency("CNY").build();
        ProductVariantEntity v = ProductVariantEntity.builder().product(p).sku("SKU-1").price(new BigDecimal("10"))
                .stock(5).options(Map.of("Talla", "M")).weightGrams(pesoGramos).shippingCny(envio).active(true).build();
        p.setVariants(List.of(v));
        return v;
    }
}
