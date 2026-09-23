package com.nexaplatform.dropshipping.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariant;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantOption;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ingesta desde el topic de productos crudos del crawler. Lo que se fija aquí es el contrato de
 * traducción snake_case → DTO y, sobre todo, que NINGÚN mensaje mal formado pueda tumbar al consumidor:
 * si una excepción escapara, el offset dejaría de avanzar y la cola entera se quedaría parada.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov05ProductRawConsumerTest {

    @Mock
    CatalogUseCase catalogService;

    ProductRawConsumer consumer;

    private static final UUID SUPPLIER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @BeforeEach
    void setUp() {
        // El sujeto se construye aquí (no en la declaración del campo) para que el doble ya exista.
        consumer = new ProductRawConsumer(catalogService, new ObjectMapper());
        when(catalogService.upsertProduct(any())).thenReturn(new ProductEntity());
    }

    private Map<String, Object> minimalProduct() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "1688");
        payload.put("external_id", "6543210");
        payload.put("title_zh", "连衣裙");
        payload.put("base_price", "12.50");
        payload.put("currency", "CNY");
        return payload;
    }

    private IngestProductRequest captureProduct() {
        ArgumentCaptor<IngestProductRequest> captor = ArgumentCaptor.forClass(IngestProductRequest.class);
        verify(catalogService).upsertProduct(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("un mensaje vacío se descarta sin tumbar al consumidor")
    void unMensajeVacioNoTumbaAlConsumidor() {
        assertThatCode(() -> consumer.onProductRaw(null)).doesNotThrowAnyException();
        verify(catalogService, never()).upsertProduct(any());
        verify(catalogService, never()).upsertSupplier(any());
    }

    @Test
    @DisplayName("si el catálogo falla al persistir, la excepción no sale del consumidor")
    void unFalloDelCatalogoNoSaleDelConsumidor() {
        when(catalogService.upsertProduct(any())).thenThrow(new IllegalStateException("constraint violada"));

        assertThatCode(() -> consumer.onProductRaw(minimalProduct())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("un producto sin proveedor no da de alta ningún proveedor")
    void unProductoSinProveedorNoCreaProveedor() {
        consumer.onProductRaw(minimalProduct());

        verify(catalogService, never()).upsertSupplier(any());
        assertThat(captureProduct().supplierId()).isNull();
    }

    @Test
    @DisplayName("el proveedor se da de alta antes que el producto y su id queda enlazado")
    void elProveedorSeCreaAntesYSuIdQuedaEnlazado() {
        SupplierEntity supplier = new SupplierEntity();
        supplier.setId(SUPPLIER_ID);
        when(catalogService.upsertSupplier(any())).thenReturn(supplier);
        Map<String, Object> payload = minimalProduct();
        Map<String, Object> supplierNode = new LinkedHashMap<>();
        supplierNode.put("source", "1688");
        supplierNode.put("external_id", "shop-1");
        supplierNode.put("name", "Yiwu Trading");
        supplierNode.put("name_zh", "义乌贸易");
        supplierNode.put("country", "CN");
        supplierNode.put("city", "Yiwu");
        supplierNode.put("rating", "4.8");
        supplierNode.put("years_active", 6);
        supplierNode.put("verified", true);
        supplierNode.put("trust_pass", true);
        supplierNode.put("profile_url", "https://1688.com/shop-1");
        payload.put("supplier", supplierNode);

        consumer.onProductRaw(payload);

        ArgumentCaptor<IngestSupplierRequest> captor = ArgumentCaptor.forClass(IngestSupplierRequest.class);
        verify(catalogService).upsertSupplier(captor.capture());
        assertThat(captor.getValue().externalId()).isEqualTo("shop-1");
        assertThat(captor.getValue().nameZh()).isEqualTo("义乌贸易");
        assertThat(captor.getValue().rating()).isEqualByComparingTo("4.8");
        assertThat(captor.getValue().yearsActive()).isEqualTo(6);
        assertThat(captor.getValue().verified()).isTrue();
        assertThat(captor.getValue().trustPass()).isTrue();
        assertThat(captureProduct().supplierId()).isEqualTo(SUPPLIER_ID);
    }

    @Test
    @DisplayName("un proveedor explícitamente nulo se trata como 'sin proveedor'")
    void unProveedorNuloSeTrataComoSinProveedor() {
        Map<String, Object> payload = minimalProduct();
        payload.put("supplier", null);

        consumer.onProductRaw(payload);

        verify(catalogService, never()).upsertSupplier(any());
    }

    @Test
    @DisplayName("los indicadores del proveedor que no llegan valen 'no' en vez de romper")
    void losIndicadoresDelProveedorQueFaltanValenNo() {
        SupplierEntity supplier = new SupplierEntity();
        supplier.setId(SUPPLIER_ID);
        when(catalogService.upsertSupplier(any())).thenReturn(supplier);
        Map<String, Object> payload = minimalProduct();
        payload.put("supplier", Map.of("source", "1688", "external_id", "shop-2"));

        consumer.onProductRaw(payload);

        ArgumentCaptor<IngestSupplierRequest> captor = ArgumentCaptor.forClass(IngestSupplierRequest.class);
        verify(catalogService).upsertSupplier(captor.capture());
        assertThat(captor.getValue().verified()).isFalse();
        assertThat(captor.getValue().trustPass()).isFalse();
        assertThat(captor.getValue().rating()).isNull();
        assertThat(captor.getValue().yearsActive()).isNull();
    }

    @Test
    @DisplayName("un mensaje sin colecciones produce listas vacías, nunca nulos")
    void unMensajeSinColeccionesProduceListasVacias() {
        consumer.onProductRaw(minimalProduct());

        IngestProductRequest req = captureProduct();
        assertThat(req.images()).isEmpty();
        assertThat(req.options()).isEmpty();
        assertThat(req.variants()).isEmpty();
        assertThat(req.priceTiers()).isEmpty();
    }

    @Test
    @DisplayName("las imágenes sin URL se descartan y el resto conserva su posición")
    void lasImagenesSinUrlSeDescartan() {
        Map<String, Object> payload = minimalProduct();
        List<Map<String, Object>> images = new ArrayList<>();
        images.add(Map.of("source_url", "https://cbu01.alicdn.com/a.jpg", "position", 0, "role", "MAIN"));
        images.add(Map.of("position", 1));
        images.add(Map.of("source_url", "   ", "position", 2));
        payload.put("images", images);

        consumer.onProductRaw(payload);

        IngestProductRequest req = captureProduct();
        assertThat(req.images()).hasSize(1);
        assertThat(req.images().get(0).sourceUrl()).isEqualTo("https://cbu01.alicdn.com/a.jpg");
        assertThat(req.images().get(0).role()).isEqualTo("MAIN");
    }

    @Test
    @DisplayName("los tramos de precio sin importe se descartan (un tramo sin precio no es vendible)")
    void losTramosSinImporteSeDescartan() {
        Map<String, Object> payload = minimalProduct();
        List<Map<String, Object>> tiers = new ArrayList<>();
        tiers.add(Map.of("min_qty", 1, "max_qty", 9, "unit_price", "10.00", "currency", "CNY"));
        tiers.add(Map.of("min_qty", 10));
        payload.put("price_tiers", tiers);

        consumer.onProductRaw(payload);

        IngestProductRequest req = captureProduct();
        assertThat(req.priceTiers()).hasSize(1);
        assertThat(req.priceTiers().get(0).minQty()).isEqualTo(1);
        assertThat(req.priceTiers().get(0).maxQty()).isEqualTo(9);
        assertThat(req.priceTiers().get(0).unitPrice()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("cada variante conserva sus ejes de opción como pares nombre/valor")
    void cadaVarianteConservaSusEjesDeOpcion() {
        Map<String, Object> payload = minimalProduct();
        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("external_id", "sku-1");
        variant.put("sku", "SKU-1");
        variant.put("title", "Rojo / M");
        variant.put("price", "13.90");
        variant.put("stock", 42);
        variant.put("image_source_url", "https://cbu01.alicdn.com/v.jpg");
        variant.put("options", Map.of("颜色", "红色", "尺码", "M"));
        payload.put("variants", List.of(variant));

        consumer.onProductRaw(payload);

        IngestProductRequest req = captureProduct();
        assertThat(req.variants()).hasSize(1);
        IngestVariant v = req.variants().get(0);
        assertThat(v.sku()).isEqualTo("SKU-1");
        assertThat(v.price()).isEqualByComparingTo("13.90");
        assertThat(v.stock()).isEqualTo(42);
        // Sin los ejes, la variante entraría "sin stock" al no poder emparejarse con la rejilla.
        assertThat(v.options()).containsEntry("颜色", "红色").containsEntry("尺码", "M");
    }

    @Test
    @DisplayName("una variante sin ejes de opción llega con el mapa vacío, no nulo")
    void unaVarianteSinEjesLlegaConMapaVacio() {
        Map<String, Object> payload = minimalProduct();
        payload.put("variants", List.of(Map.of("sku", "SKU-1", "price", "9.00")));

        consumer.onProductRaw(payload);

        assertThat(captureProduct().variants().get(0).options()).isEmpty();
    }

    @Test
    @DisplayName("los ejes de opción conservan sus valores, posiciones e imagen por valor")
    void losEjesDeOpcionConservanValoresYPosiciones() {
        Map<String, Object> payload = minimalProduct();
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("name_zh", "颜色");
        option.put("position", 0);
        option.put("values",
                List.of(Map.of("value_zh", "红色", "position", 0, "image_source_url", "https://cbu01.alicdn.com/red.jpg"),
                        Map.of("value_zh", "蓝色", "position", 1)));
        payload.put("options", List.of(option));

        consumer.onProductRaw(payload);

        IngestProductRequest req = captureProduct();
        IngestVariantOption opt = req.options().get(0);
        assertThat(opt.nameZh()).isEqualTo("颜色");
        assertThat(opt.values()).hasSize(2);
        assertThat(opt.values().get(0).imageSourceUrl()).isEqualTo("https://cbu01.alicdn.com/red.jpg");
        assertThat(opt.values().get(1).position()).isEqualTo(1);
        assertThat(opt.values().get(1).imageSourceUrl()).isNull();
    }

    @Test
    @DisplayName("un eje sin lista de valores no rompe el mapeo")
    void unEjeSinValoresNoRompeElMapeo() {
        Map<String, Object> payload = minimalProduct();
        payload.put("options", List.of(Map.of("name_zh", "颜色", "position", 0)));

        consumer.onProductRaw(payload);

        assertThat(captureProduct().options().get(0).values()).isEmpty();
    }

    @Test
    @DisplayName("un número que llega en blanco se interpreta como ausente, no como cero")
    void unNumeroEnBlancoSeInterpretaComoAusente() {
        Map<String, Object> payload = minimalProduct();
        payload.put("base_price", "  ");
        payload.put("rating", "");
        payload.put("moq", null);

        consumer.onProductRaw(payload);

        IngestProductRequest req = captureProduct();
        // Un 0 se leería como "gratis" / "sin valoración": debe quedar nulo.
        assertThat(req.basePrice()).isNull();
        assertThat(req.rating()).isNull();
        assertThat(req.moq()).isNull();
    }

    @Test
    @DisplayName("las colecciones que no son listas se ignoran en vez de reventar")
    void lasColeccionesMalFormadasSeIgnoran() {
        Map<String, Object> payload = minimalProduct();
        payload.put("images", "no-soy-una-lista");
        payload.put("variants", 42);
        payload.put("options", Map.of("a", "b"));
        payload.put("price_tiers", "x");

        consumer.onProductRaw(payload);

        IngestProductRequest req = captureProduct();
        assertThat(req.images()).isEmpty();
        assertThat(req.variants()).isEmpty();
        assertThat(req.options()).isEmpty();
        assertThat(req.priceTiers()).isEmpty();
    }

    @Test
    @DisplayName("los campos escalares del producto viajan tal cual desde el mensaje del crawler")
    void losCamposEscalaresViajanTalCual() {
        Map<String, Object> payload = minimalProduct();
        payload.put("short_description_zh", "短描述");
        payload.put("description_zh", "长描述");
        payload.put("brand", "NoBrand");
        payload.put("moq", 2);
        payload.put("weight_grams", 480);
        payload.put("monthly_sales", 1200);
        payload.put("repurchase_rate", "0.35");
        payload.put("rating", "4.7");
        payload.put("review_count", 88);
        payload.put("source_url", "https://detail.1688.com/offer/6543210.html");

        consumer.onProductRaw(payload);

        IngestProductRequest req = captureProduct();
        assertThat(req.source()).isEqualTo("1688");
        assertThat(req.externalId()).isEqualTo("6543210");
        assertThat(req.titleZh()).isEqualTo("连衣裙");
        assertThat(req.shortDescriptionZh()).isEqualTo("短描述");
        assertThat(req.descriptionZh()).isEqualTo("长描述");
        assertThat(req.brand()).isEqualTo("NoBrand");
        assertThat(req.moq()).isEqualTo(2);
        assertThat(req.basePrice()).isEqualByComparingTo("12.50");
        assertThat(req.currency()).isEqualTo("CNY");
        assertThat(req.weightGrams()).isEqualTo(480);
        assertThat(req.monthlySales()).isEqualTo(1200);
        assertThat(req.repurchaseRate()).isEqualByComparingTo("0.35");
        assertThat(req.rating()).isEqualByComparingTo("4.7");
        assertThat(req.reviewCount()).isEqualTo(88);
        assertThat(req.sourceUrl()).isEqualTo("https://detail.1688.com/offer/6543210.html");
        // La categoría NO viaja por Kafka: la asigna el catálogo, nunca el crawler.
        assertThat(req.categoryId()).isNull();
    }
}
