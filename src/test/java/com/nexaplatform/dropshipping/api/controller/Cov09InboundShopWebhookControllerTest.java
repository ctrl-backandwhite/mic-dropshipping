package com.nexaplatform.dropshipping.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.CreateOrderRequest;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderView;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.PartnerOrderDtoMapper;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopConnectionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopProductListingEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopProductListingRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.HmacVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pedido que entra desde la tienda del cliente (Shopify/WooCommerce → NexaDrop).
 *
 * <p>Es una puerta abierta a internet que CREA PEDIDOS, así que lo que se fija aquí es sobre todo lo que
 * NO debe pasar: sin firma válida no se crea nada, un cuerpo que no se entiende se rechaza con 400 en vez
 * de reventar a medio camino, y una línea cuyo SKU no sabemos mapear devuelve 422 en lugar de un pedido
 * incompleto. La firma se calcula con el verificador REAL: si se cambiara el algoritmo, estos tests caen.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov09InboundShopWebhookControllerTest {

    @Mock
    ShopConnectionRepository shopRepo;
    @Mock
    ShopProductListingRepository listingRepo;
    @Mock
    ProductRepository productRepo;
    @Mock
    OrderUseCase orderUseCase;
    @Mock
    PartnerOrderDtoMapper partnerOrderDtoMapper;

    private final HmacVerifier hmac = new HmacVerifier();
    private final ObjectMapper json = new ObjectMapper();

    private static final String SECRET = "s3cr3t-de-la-tienda";
    private final UUID shopId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final UUID ownerId = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private final UUID productId = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private InboundShopWebhookController subject;

    @BeforeEach
    void buildSubject() {
        subject = new InboundShopWebhookController(shopRepo, listingRepo, productRepo, orderUseCase,
                partnerOrderDtoMapper, hmac, json);
        when(partnerOrderDtoMapper.toOrderView(any())).thenReturn(new OrderView(UUID.randomUUID(), "NX-1", "PENDING",
                null, null, null, null, "USD", null, null, List.of()));
        when(orderUseCase.createOrder(any(), any(), any())).thenReturn(new Order());
    }

    /** Tienda conectada con secreto de entrada configurado y dueño conocido. */
    private ShopConnectionEntity shop(String secret) {
        UserEntity owner = new UserEntity();
        owner.setId(ownerId);
        Map<String, Object> metadata = new HashMap<>();
        if (secret != null) {
            metadata.put("inboundSecret", secret);
        }
        ShopConnectionEntity shop = ShopConnectionEntity.builder().user(owner).platform("shopify").metadata(metadata)
                .build();
        shop.setId(shopId);
        when(shopRepo.findById(shopId)).thenReturn(Optional.of(shop));
        return shop;
    }

    private static byte[] bytes(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /** Firma hexadecimal correcta del cuerpo, la misma que calcularía la tienda. */
    private String firma(byte[] body) {
        return hmac.sign(SECRET, body);
    }

    private ResponseEntity<OrderView> post(String body, String idempotencyKey) {
        byte[] raw = bytes(body);
        return subject.receiveOrder(shopId, firma(raw), idempotencyKey, raw);
    }

    private String canonico() {
        return """
                {"items":[{"productId":"%s","quantity":3}],
                 "shippingAddress":{"fullName":"Ada","line1":"C/ Uno","city":"Madrid","postalCode":"28001",
                 "country":"ES"}}""".formatted(productId);
    }

    // ---------------------------------------------------------------- autenticación

    @Test
    void unaTiendaDesconocidaNoPuedeMeterPedidos() {
        when(shopRepo.findById(shopId)).thenReturn(Optional.empty());
        byte[] raw = bytes("{}");

        assertThatThrownBy(() -> subject.receiveOrder(shopId, "cafe", null, raw)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void sinSecretoConfiguradoLaTiendaNoPuedeFirmarNadaYSeRechaza() {
        // Sin secreto no hay nada contra lo que verificar: aceptar el pedido sería aceptarlo sin firma.
        shop(null);
        byte[] raw = bytes(canonico());

        ResponseEntity<OrderView> res = subject.receiveOrder(shopId, "cafe", null, raw);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
        verify(orderUseCase, never()).createOrder(any(), any(), any());
    }

    @Test
    void unaFirmaQueNoCuadraConElCuerpoNoCreaPedido() {
        shop(SECRET);
        byte[] raw = bytes(canonico());

        ResponseEntity<OrderView> res = subject.receiveOrder(shopId, firma(bytes("otro cuerpo")), null, raw);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
        verify(orderUseCase, never()).createOrder(any(), any(), any());
    }

    // ---------------------------------------------------------------- cuerpos que no se entienden

    @Test
    void unCuerpoQueNoEsUnObjetoJsonSeRechazaConPeticionIncorrecta() {
        // Una lista o un número suelto no se pueden recorrer por clave: mejor 400 que reventar al leer.
        shop(SECRET);

        assertThat(post("[1,2,3]", null).getStatusCode().value()).isEqualTo(400);
        assertThat(post("42", null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unPedidoSinLineasSeRechaza() {
        shop(SECRET);

        ResponseEntity<OrderView> res = post("{\"shippingAddress\":{\"city\":\"Madrid\"}}", null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(orderUseCase, never()).createOrder(any(), any(), any());
    }

    @Test
    void unPedidoConLaListaDeLineasVaciaSeRechaza() {
        shop(SECRET);

        assertThat(post("{\"items\":[],\"shippingAddress\":{\"city\":\"Madrid\"}}", null).getStatusCode().value())
                .isEqualTo(400);
    }

    @Test
    void unPedidoSinDireccionDeEnvioSeRechazaAntesDeCrearlo() {
        // Dejarlo pasar solo aplaza el fallo hasta que el transportista pide destinatario: ya está cobrado.
        shop(SECRET);

        ResponseEntity<OrderView> res = post("{\"items\":[{\"productId\":\"%s\",\"quantity\":1}]}".formatted(productId),
                null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(orderUseCase, never()).createOrder(any(), any(), any());
    }

    @Test
    void unaLineaConUnSkuQueNoSabemosMapearDevuelveNoProcesable() {
        // 422 y no 400: el cuerpo es correcto, lo que no sabemos es a qué producto nuestro corresponde.
        shop(SECRET);
        when(listingRepo.findByShopConnection_IdAndRemoteProductId(shopId, "SKU-DESCONOCIDO"))
                .thenReturn(Optional.empty());
        when(productRepo.findFirstByExternalId("SKU-DESCONOCIDO")).thenReturn(Optional.empty());

        ResponseEntity<OrderView> res = post("""
                {"line_items":[{"sku":"SKU-DESCONOCIDO","quantity":1}],
                 "shipping_address":{"name":"Ada","address1":"C/ Uno","city":"Madrid","zip":"28001",
                 "country_code":"ES"}}""", null);

        assertThat(res.getStatusCode().value()).isEqualTo(422);
        verify(orderUseCase, never()).createOrder(any(), any(), any());
    }

    @Test
    void unaLineaSinNingunIdentificadorDeProductoDevuelveNoProcesable() {
        shop(SECRET);

        ResponseEntity<OrderView> res = post("""
                {"items":[{"quantity":1}],
                 "shippingAddress":{"fullName":"Ada","line1":"C/ Uno","city":"Madrid","country":"ES"}}""", null);

        assertThat(res.getStatusCode().value()).isEqualTo(422);
    }

    // ---------------------------------------------------------------- pedido aceptado

    @Test
    void unPedidoCanonicoFirmadoSeCreaAnombreDelDuenoDeLaTienda() {
        // El pedido tiene que quedar en la cuenta del dueño de la conexión, no huérfano.
        shop(SECRET);
        when(listingRepo.findByShopConnection_IdAndProduct_Id(shopId, productId))
                .thenReturn(Optional.of(mock(ShopProductListingEntity.class)));

        ResponseEntity<OrderView> res = post(canonico(), "IDEM-1");

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderUseCase).createOrder(isNull(), eq(ownerId), captor.capture());
        CreateOrderRequest req = captor.getValue();
        assertThat(req.items()).singleElement().satisfies(it -> assertThat(it.productId()).isEqualTo(productId));
        assertThat(req.items().get(0).quantity()).isEqualTo(3);
    }

    @Test
    void laClaveDeIdempotenciaMandaSobreElIdDelPayload() {
        // Es la que la tienda reenvía al reintentar: usar la del cuerpo duplicaría el pedido.
        shop(SECRET);
        when(listingRepo.findByShopConnection_IdAndProduct_Id(shopId, productId))
                .thenReturn(Optional.of(mock(ShopProductListingEntity.class)));

        post("""
                {"id":"DEL-PAYLOAD","items":[{"productId":"%s","quantity":1}],
                 "shippingAddress":{"fullName":"Ada","line1":"C/ Uno","city":"Madrid","country":"ES"}}"""
                .formatted(productId), "IDEM-1");

        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderUseCase).createOrder(any(), any(), captor.capture());
        assertThat(captor.getValue().externalOrderId()).isEqualTo("IDEM-1");
    }

    @Test
    void sinClaveDeIdempotenciaSeUsaElIdentificadorDelPayload() {
        shop(SECRET);
        when(listingRepo.findByShopConnection_IdAndProduct_Id(shopId, productId))
                .thenReturn(Optional.of(mock(ShopProductListingEntity.class)));

        post("""
                {"id":"DEL-PAYLOAD","items":[{"productId":"%s","quantity":1}],
                 "shippingAddress":{"fullName":"Ada","line1":"C/ Uno","city":"Madrid","country":"ES"}}"""
                .formatted(productId), null);

        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderUseCase).createOrder(any(), any(), captor.capture());
        assertThat(captor.getValue().externalOrderId()).isEqualTo("DEL-PAYLOAD");
    }

    @Test
    void sinIdentificadorDeNingunTipoSeSintetizaUnoTrazableALaTienda() {
        shop(SECRET);
        when(listingRepo.findByShopConnection_IdAndProduct_Id(shopId, productId))
                .thenReturn(Optional.of(mock(ShopProductListingEntity.class)));

        post(canonico(), null);

        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderUseCase).createOrder(any(), any(), captor.capture());
        assertThat(captor.getValue().externalOrderId()).startsWith("shop-" + shopId);
    }

    @Test
    void elFormatoShopifySeLeeIgualQueElCanonico() {
        // line_items/shipping_address/zip/address1/country_code son la misma información con otra clave.
        shop(SECRET);
        ProductEntity product = new ProductEntity();
        product.setId(productId);
        ShopProductListingEntity listing = ShopProductListingEntity.builder().product(product).build();
        when(listingRepo.findByShopConnection_IdAndRemoteProductId(shopId, "NX-SKU-001"))
                .thenReturn(Optional.of(listing));
        when(listingRepo.findByShopConnection_IdAndProduct_Id(shopId, productId)).thenReturn(Optional.of(listing));

        ResponseEntity<OrderView> res = post("""
                {"line_items":[{"sku":"NX-SKU-001","quantity":2}],
                 "shipping_address":{"name":"Ada Lovelace","address1":"1 Babbage Way","city":"London",
                 "zip":"EC1A1","country_code":"GB"}}""", null);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderUseCase).createOrder(any(), any(), captor.capture());
        assertThat(captor.getValue().shippingAddress().fullName()).isEqualTo("Ada Lovelace");
        assertThat(captor.getValue().shippingAddress().line1()).isEqualTo("1 Babbage Way");
        assertThat(captor.getValue().shippingAddress().postalCode()).isEqualTo("EC1A1");
        assertThat(captor.getValue().shippingAddress().country()).isEqualTo("GB");
        assertThat(captor.getValue().items().get(0).productId()).isEqualTo(productId);
    }

    @Test
    void unSkuSinListadoSeResuelvePorElIdentificadorExternoDelCatalogo() {
        shop(SECRET);
        ProductEntity product = new ProductEntity();
        product.setId(productId);
        when(listingRepo.findByShopConnection_IdAndRemoteProductId(shopId, "1688-123")).thenReturn(Optional.empty());
        when(productRepo.findFirstByExternalId("1688-123")).thenReturn(Optional.of(product));
        when(listingRepo.findByShopConnection_IdAndProduct_Id(shopId, productId))
                .thenReturn(Optional.of(mock(ShopProductListingEntity.class)));

        ResponseEntity<OrderView> res = post("""
                {"items":[{"sku":"1688-123","quantity":1}],
                 "shippingAddress":{"fullName":"Ada","line1":"C/ Uno","city":"Madrid","country":"ES"}}""", null);

        assertThat(res.getStatusCode().value()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- auto-listado (DROP-548)

    @Test
    void unProductoVendidoQueNoEstabaPublicadoSeAutoListaEnLaTienda() {
        // Si no, el contador de "productos publicados" se queda a 0 mientras hay ventas reales.
        shop(SECRET);
        ProductEntity product = new ProductEntity();
        product.setId(productId);
        when(listingRepo.findByShopConnection_IdAndProduct_Id(shopId, productId)).thenReturn(Optional.empty());
        when(productRepo.findById(productId)).thenReturn(Optional.of(product));

        post(canonico(), null);

        ArgumentCaptor<ShopProductListingEntity> captor = ArgumentCaptor.forClass(ShopProductListingEntity.class);
        verify(listingRepo).save(captor.capture());
        assertThat(captor.getValue().getProduct()).isSameAs(product);
        assertThat(captor.getValue().getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void unProductoYaPublicadoNoSeVuelveAListar() {
        shop(SECRET);
        when(listingRepo.findByShopConnection_IdAndProduct_Id(shopId, productId))
                .thenReturn(Optional.of(mock(ShopProductListingEntity.class)));

        post(canonico(), null);

        verify(listingRepo, never()).save(any());
    }

    @Test
    void siElProductoNoExisteEnElCatalogoNoSeCreaListadoAlguno() {
        shop(SECRET);
        when(listingRepo.findByShopConnection_IdAndProduct_Id(shopId, productId)).thenReturn(Optional.empty());
        when(productRepo.findById(productId)).thenReturn(Optional.empty());

        post(canonico(), null);

        verify(listingRepo, never()).save(any());
    }
}
