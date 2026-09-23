package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.PlanLimitService;
import com.nexaplatform.dropshipping.application.usecase.impl.ShopConnectionUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.ShopConnection;
import com.nexaplatform.dropshipping.domain.model.ShopInboundSecret;
import com.nexaplatform.dropshipping.domain.model.ShopPlatform;
import com.nexaplatform.dropshipping.domain.model.ShopProductListing;
import com.nexaplatform.dropshipping.domain.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.domain.repository.ShopProductListingRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.shop.ShopConnector;
import com.nexaplatform.dropshipping.infrastructure.integration.shop.ShopConnectorRegistry;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.TokenCryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sincronización de una tienda conectada y publicación de productos.
 *
 * <p>Antes de DROP-693 una tienda podía quedarse en 0 productos publicados sin decir por qué. La regla
 * es que TODA sincronización deja escrito el motivo, y que una tienda solo se marca en ERROR cuando no
 * salió NADA: con publicaciones correctas sigue conectada aunque alguna fallara.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov04ShopConnectionSyncTest {

    @Mock
    ShopConnectionRepository shopRepository;
    @Mock
    ShopProductListingRepository listingRepository;
    @Mock
    TokenCryptoService tokenCrypto;
    @Mock
    ShopConnectorRegistry connectorRegistry;
    @Mock
    ProductRepository productRepository;
    @Mock
    PlanLimitService planLimitService;
    @Mock
    ShopConnector connector;

    @InjectMocks
    ShopConnectionUseCaseImpl useCase;

    private UUID userId;
    private UUID shopId;
    private ShopConnection shop;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        shop = ShopConnection.builder().id(shopId).userId(userId).platform("shopify").accessTokenEnc("gcm:cifrado")
                .build();
        when(shopRepository.getById(shopId)).thenReturn(shop);
        // update() devuelve la tienda persistida: el use case le pone encima el contador de publicaciones.
        when(shopRepository.update(any(ShopConnection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(listingRepository.save(any(ShopProductListing.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenCrypto.isModern("gcm:cifrado")).thenReturn(true);
        when(tokenCrypto.decrypt("gcm:cifrado")).thenReturn("token-en-claro");
    }

    private ShopProductListing listing(UUID productId) {
        return ShopProductListing.builder().shopConnectionId(shopId).productId(productId).build();
    }

    private void conConector() {
        when(connectorRegistry.connectorFor("shopify")).thenReturn(Optional.of(connector));
    }

    // ── Sincronización ───────────────────────────────────────────────────────────────────────────

    @Test
    void unaPlataformaSinConectorSeMarcaEnErrorYExplicaQueAunNoEstaDisponible() {
        // "Próximamente" honesto: publicar nada en silencio hacía creer al usuario que estaba vendiendo.
        when(connectorRegistry.connectorFor("shopify")).thenReturn(Optional.empty());

        ShopConnection result = useCase.sync(userId, shopId);

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getLastSyncMessage()).contains("Próximamente");
        assertThat(result.getLastSyncError()).isNull();
        assertThat(result.getLastSyncAt()).isNotNull();
    }

    @Test
    void unaTiendaSinProductosQueSincronizarSigueConectada() {
        conConector();
        when(listingRepository.findByShopConnectionId(shopId)).thenReturn(List.of());

        ShopConnection result = useCase.sync(userId, shopId);

        assertThat(result.getStatus()).isEqualTo("CONNECTED");
        assertThat(result.getLastSyncMessage()).contains("0 productos");
        verify(connector, never()).push(any(), any(), any());
    }

    @Test
    void conAlgunaPublicacionCorrectaLaTiendaSigueConectadaAunqueOtraFalle() {
        conConector();
        UUID ok = UUID.randomUUID();
        UUID ko = UUID.randomUUID();
        ShopProductListing bueno = listing(ok);
        ShopProductListing malo = listing(ko);
        when(listingRepository.findByShopConnectionId(shopId)).thenReturn(List.of(bueno, malo));
        when(productRepository.findById(any(UUID.class))).thenReturn(Optional.of(new ProductEntity()));
        when(connector.push(any(), anyString(), any())).thenReturn(new ShopConnector.PushResult(true, "remote-1", null))
                .thenReturn(new ShopConnector.PushResult(false, null, "429 rate limited"));

        ShopConnection result = useCase.sync(userId, shopId);

        assertThat(result.getStatus()).isEqualTo("CONNECTED");
        assertThat(result.getLastSyncMessage()).isEqualTo("1 publicados, 1 con error.");
        assertThat(result.getLastSyncError()).isEqualTo("429 rate limited");
        assertThat(bueno.getStatus()).isEqualTo("LISTED");
        assertThat(bueno.getRemoteProductId()).isEqualTo("remote-1");
        assertThat(malo.getStatus()).isEqualTo("ERROR");
        assertThat(malo.getErrorMessage()).isEqualTo("429 rate limited");
    }

    @Test
    void soloSiNoSalioNingunaPublicacionLaTiendaQuedaEnError() {
        conConector();
        when(listingRepository.findByShopConnectionId(shopId)).thenReturn(List.of(listing(UUID.randomUUID())));
        when(productRepository.findById(any(UUID.class))).thenReturn(Optional.of(new ProductEntity()));
        when(connector.push(any(), anyString(), any()))
                .thenReturn(new ShopConnector.PushResult(false, null, "401 token caducado"));

        ShopConnection result = useCase.sync(userId, shopId);

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getLastSyncError()).isEqualTo("401 token caducado");
    }

    @Test
    void unProductoBorradoDelCatalogoSeReportaConSuIdYNoRompeLaSincronizacion() {
        conConector();
        UUID fantasma = UUID.randomUUID();
        ShopProductListing huerfano = listing(fantasma);
        when(listingRepository.findByShopConnectionId(shopId)).thenReturn(List.of(huerfano));
        when(productRepository.findById(fantasma)).thenReturn(Optional.empty());

        ShopConnection result = useCase.sync(userId, shopId);

        assertThat(huerfano.getStatus()).isEqualTo("ERROR");
        assertThat(huerfano.getErrorMessage()).contains(fantasma.toString());
        assertThat(result.getLastSyncError()).contains("Producto no encontrado");
        verify(connector, never()).push(any(), any(), any());
    }

    @Test
    void cadaPublicacionDejaFechaDeUltimoIntentoAunqueFalle() {
        // Sin esta marca no se puede saber si la tienda se intentó sincronizar o nunca se tocó.
        conConector();
        ShopProductListing l = listing(UUID.randomUUID());
        when(listingRepository.findByShopConnectionId(shopId)).thenReturn(List.of(l));
        when(productRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        useCase.sync(userId, shopId);

        assertThat(l.getLastPushedAt()).isNotNull();
        verify(listingRepository).save(l);
    }

    @Test
    void elTokenSeDescifraUnaSolaVezParaTodaLaTanda() {
        conConector();
        when(listingRepository.findByShopConnectionId(shopId))
                .thenReturn(List.of(listing(UUID.randomUUID()), listing(UUID.randomUUID())));
        when(productRepository.findById(any(UUID.class))).thenReturn(Optional.of(new ProductEntity()));
        when(connector.push(any(), anyString(), any()))
                .thenReturn(new ShopConnector.PushResult(true, "remote-1", null));

        useCase.sync(userId, shopId);

        verify(tokenCrypto).decrypt("gcm:cifrado");
        verify(connector, times(2)).push(any(), eq("token-en-claro"), any());
    }

    @Test
    void unTokenAntiguoEnClaroSeUsaTalCualSinIntentarDescifrarlo() {
        // Tiendas conectadas antes del cifrado: si se intentara descifrar, dejarían de publicar.
        shop.setAccessTokenEnc("shpat_legacy");
        when(tokenCrypto.isModern("shpat_legacy")).thenReturn(false);
        conConector();
        when(listingRepository.findByShopConnectionId(shopId)).thenReturn(List.of(listing(UUID.randomUUID())));
        when(productRepository.findById(any(UUID.class))).thenReturn(Optional.of(new ProductEntity()));
        when(connector.push(any(), anyString(), any()))
                .thenReturn(new ShopConnector.PushResult(true, "remote-1", null));

        useCase.sync(userId, shopId);

        verify(tokenCrypto, never()).decrypt(anyString());
        verify(connector).push(any(), eq("shpat_legacy"), any());
    }

    @Test
    void unTokenIlegibleNoTumbaLaSincronizacionEntera() {
        shop.setAccessTokenEnc("gcm:corrupto");
        when(tokenCrypto.isModern("gcm:corrupto")).thenReturn(true);
        when(tokenCrypto.decrypt("gcm:corrupto")).thenThrow(new IllegalStateException("clave rotada"));
        conConector();
        when(listingRepository.findByShopConnectionId(shopId)).thenReturn(List.of(listing(UUID.randomUUID())));
        when(productRepository.findById(any(UUID.class))).thenReturn(Optional.of(new ProductEntity()));
        when(connector.push(any(), any(), any())).thenReturn(new ShopConnector.PushResult(false, null, "sin token"));

        ShopConnection result = useCase.sync(userId, shopId);

        assertThat(result.getStatus()).isEqualTo("ERROR");
        verify(connector).push(any(), isNull(), any());
    }

    @Test
    void trasSincronizarSeRefrescaElContadorDePublicaciones() {
        conConector();
        when(listingRepository.findByShopConnectionId(shopId)).thenReturn(List.of());
        when(listingRepository.countByShopConnectionId(shopId)).thenReturn(7);

        assertThat(useCase.sync(userId, shopId).getListings()).isEqualTo(7);
    }

    // ── Publicar un producto suelto ──────────────────────────────────────────────────────────────

    @Test
    void publicarEnUnaPlataformaSinConectorNoInventaUnIdRemoto() {
        // DROP-701: antes se guardaba un "remote-xxxx" falso y el usuario creía que estaba publicado.
        when(connectorRegistry.connectorFor("shopify")).thenReturn(Optional.empty());
        UUID productId = UUID.randomUUID();
        when(listingRepository.findByShopConnectionIdAndProductId(shopId, productId)).thenReturn(Optional.empty());

        ShopProductListing result = useCase.listProduct(userId, shopId, productId);

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getRemoteProductId()).isNull();
        assertThat(result.getErrorMessage()).contains("Próximamente");
    }

    @Test
    void publicarUnProductoQueYaNoExisteDejaElMotivoEnElListing() {
        conConector();
        UUID productId = UUID.randomUUID();
        when(listingRepository.findByShopConnectionIdAndProductId(shopId, productId)).thenReturn(Optional.empty());
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        ShopProductListing result = useCase.listProduct(userId, shopId, productId);

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getErrorMessage()).contains(productId.toString());
        verify(connector, never()).push(any(), any(), any());
    }

    @Test
    void publicarConRechazoDeLaTiendaGuardaElErrorDelConector() {
        conConector();
        UUID productId = UUID.randomUUID();
        ShopProductListing existente = listing(productId);
        existente.setStatus("LISTED");
        existente.setRemoteProductId("remote-viejo");
        when(listingRepository.findByShopConnectionIdAndProductId(shopId, productId))
                .thenReturn(Optional.of(existente));
        when(productRepository.findById(productId)).thenReturn(Optional.of(new ProductEntity()));
        when(connector.push(any(), anyString(), any()))
                .thenReturn(new ShopConnector.PushResult(false, null, "422 título duplicado"));

        ShopProductListing result = useCase.listProduct(userId, shopId, productId);

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getErrorMessage()).isEqualTo("422 título duplicado");
    }

    // ── Propiedad y límites ──────────────────────────────────────────────────────────────────────

    @Test
    void nadieOperaSobreLaTiendaDeOtroUsuario() {
        // Se responde "no existe" en vez de "no es tuya": confirmar la existencia ya filtra información.
        UUID intruso = UUID.randomUUID();
        UUID otroProducto = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.disconnect(intruso, shopId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> useCase.listings(intruso, shopId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> useCase.listProduct(intruso, shopId, otroProducto))
                .isInstanceOf(NotFoundException.class);
        verify(shopRepository, never()).delete(any(UUID.class));
    }

    @Test
    void desconectarBorraLaTiendaDelPropietario() {
        useCase.disconnect(userId, shopId);

        verify(shopRepository).delete(shopId);
    }

    @Test
    void conectarUnaTiendaNuevaCuentaContraElLimiteDelPlan() {
        // El límite se comprueba con las tiendas que YA tiene: sin esto se podrían conectar sin tope.
        when(shopRepository.findByUserId(userId)).thenReturn(List.of(shop, shop));
        when(tokenCrypto.encrypt(anyString())).thenReturn("gcm:nuevo");
        when(shopRepository.save(any(ShopConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        useCase.connect(userId, ShopConnection.builder().platform("WOO").accessTokenEnc("tok").build());

        verify(planLimitService).assertWithinLimit(userId, "max_shops", 2L);
    }

    @Test
    void conectarNoRevientaConUnaPlataformaSinInformar() {
        when(shopRepository.findByUserId(userId)).thenReturn(List.of());
        when(tokenCrypto.encrypt(any())).thenReturn("gcm:nuevo");
        ArgumentCaptor<ShopConnection> captor = ArgumentCaptor.forClass(ShopConnection.class);
        when(shopRepository.save(any(ShopConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        useCase.connect(userId, ShopConnection.builder().accessTokenEnc("tok").build());

        verify(shopRepository).save(captor.capture());
        assertThat(captor.getValue().getPlatform()).isNull();
    }

    @Test
    void cadaRotacionDelSecretoDeEntradaGeneraUnoDistinto() {
        // Rotar tiene que invalidar el anterior; repetirlo dejaría válido un secreto ya filtrado.
        ShopInboundSecret primero = useCase.rotateInboundSecret(userId, shopId);
        ShopInboundSecret segundo = useCase.rotateInboundSecret(userId, shopId);

        assertThat(primero.getInboundSecret()).isNotEqualTo(segundo.getInboundSecret());
        assertThat(shop.getMetadata()).containsEntry("inboundSecret", segundo.getInboundSecret());
    }

    @Test
    void elCatalogoDePlataformasEsElQueDeclaraElRegistroDeConectores() {
        List<ShopPlatform> catalogo = List
                .of(ShopPlatform.builder().code("shopify").label("Shopify").available(true).build());
        when(connectorRegistry.catalog()).thenReturn(catalogo);

        assertThat(useCase.platforms()).isEqualTo(catalogo);
    }

    @Test
    void listarPublicacionesExigeSerElPropietario() {
        List<ShopProductListing> listings = List.of(listing(UUID.randomUUID()));
        when(listingRepository.findByShopConnectionId(shopId)).thenReturn(listings);

        assertThat(useCase.listings(userId, shopId)).isEqualTo(listings);
    }

    @Test
    void unaTiendaQueNoExisteSeTrataComoNoEncontrada() {
        UUID desconocida = UUID.randomUUID();
        when(shopRepository.getById(desconocida)).thenReturn(null);

        assertThatThrownBy(() -> useCase.disconnect(userId, desconocida)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void unaTiendaSinTokenGuardadoSePublicaSinTokenYNoRevienta() {
        shop.setAccessTokenEnc(null);
        conConector();
        when(listingRepository.findByShopConnectionId(shopId)).thenReturn(List.of(listing(UUID.randomUUID())));
        when(productRepository.findById(any(UUID.class))).thenReturn(Optional.of(new ProductEntity()));
        when(connector.push(any(), any(), any()))
                .thenReturn(new ShopConnector.PushResult(false, null, "sin credenciales"));

        useCase.sync(userId, shopId);

        verify(tokenCrypto, never()).isModern(anyString());
        verify(connector).push(any(), isNull(), any());
    }

    @Test
    void elLimiteDelPlanSeCompruebaAntesDeCifrarNada() {
        when(shopRepository.findByUserId(userId)).thenReturn(List.of());
        doThrow(new IllegalStateException("límite de tiendas alcanzado")).when(planLimitService)
                .assertWithinLimit(any(UUID.class), anyString(), anyLong());
        ShopConnection nueva = ShopConnection.builder().platform("shopify").accessTokenEnc("tok").build();

        assertThatThrownBy(() -> useCase.connect(userId, nueva)).isInstanceOf(IllegalStateException.class);
        verify(shopRepository, never()).save(any(ShopConnection.class));
    }
}
