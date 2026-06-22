package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.ShopConnectionUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.ShopConnection;
import com.nexaplatform.dropshipping.domain.model.ShopInboundSecret;
import com.nexaplatform.dropshipping.domain.model.ShopProductListing;
import com.nexaplatform.dropshipping.domain.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.domain.repository.ShopProductListingRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.shop.ShopConnector;
import com.nexaplatform.dropshipping.infrastructure.integration.shop.ShopConnectorRegistry;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.security.crypto.TokenCryptoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopConnectionUseCaseImplTest {

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
    com.nexaplatform.dropshipping.application.service.PlanLimitService planLimitService;
    @InjectMocks
    ShopConnectionUseCaseImpl useCase;

    @Test
    void connect_encryptsTokenLowercasesPlatformAndDefaultsStatus() {
        UUID userId = UUID.randomUUID();
        ShopConnection incoming = ShopConnection.builder().platform("Shopify").shopHandle("my-store")
                .accessTokenEnc("raw-token").build();
        when(tokenCrypto.encrypt("raw-token")).thenReturn("gcm:encrypted");
        when(shopRepository.save(any(ShopConnection.class)))
                .thenAnswer(inv -> ((ShopConnection) inv.getArgument(0)).withId(UUID.randomUUID()));

        ShopConnection saved = useCase.connect(userId, incoming);

        ArgumentCaptor<ShopConnection> captor = ArgumentCaptor.forClass(ShopConnection.class);
        verify(shopRepository).save(captor.capture());
        ShopConnection persisted = captor.getValue();
        assertThat(persisted.getUserId()).isEqualTo(userId);
        assertThat(persisted.getPlatform()).isEqualTo("shopify");
        assertThat(persisted.getAccessTokenEnc()).isEqualTo("gcm:encrypted");
        assertThat(persisted.getStatus()).isEqualTo("CONNECTED");
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getListings()).isZero();
    }

    @Test
    void listByUser_fillsListingsCount() {
        UUID userId = UUID.randomUUID();
        UUID shopId = UUID.randomUUID();
        ShopConnection shop = ShopConnection.builder().id(shopId).userId(userId).build();
        when(shopRepository.findByUserId(userId)).thenReturn(List.of(shop));
        when(listingRepository.countByShopConnectionId(shopId)).thenReturn(3);

        List<ShopConnection> result = useCase.listByUser(userId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getListings()).isEqualTo(3);
    }

    @Test
    void sync_throwsWhenNotOwner() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        ShopConnection foreign = ShopConnection.builder().id(id).userId(UUID.randomUUID()).build();
        when(shopRepository.getById(id)).thenReturn(foreign);

        assertThatThrownBy(() -> useCase.sync(userId, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void sync_throwsWhenMissing() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(shopRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.sync(userId, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void rotateInboundSecret_storesSecretInMetadataAndReturnsUrl() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        ShopConnection shop = ShopConnection.builder().id(id).userId(userId).build();
        when(shopRepository.getById(id)).thenReturn(shop);

        ShopInboundSecret secret = useCase.rotateInboundSecret(userId, id);

        assertThat(secret.getInboundSecret()).isNotBlank();
        assertThat(secret.getInboundUrl()).contains(id.toString());
        assertThat(shop.getMetadata()).containsKey("inboundSecret");
        verify(shopRepository).update(shop);
    }

    @Test
    void listProduct_upsertsAndMarksListed() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        ShopConnection shop = ShopConnection.builder().id(id).userId(userId).build();
        when(shopRepository.getById(id)).thenReturn(shop);
        when(listingRepository.findByShopConnectionIdAndProductId(id, productId)).thenReturn(Optional.empty());
        when(listingRepository.save(any(ShopProductListing.class)))
                .thenAnswer(inv -> ((ShopProductListing) inv.getArgument(0)).withId(UUID.randomUUID()));
        // DROP-701: el push al conector debe tener éxito para que el listing quede LISTED.
        ShopConnector connector = mock(ShopConnector.class);
        when(connectorRegistry.connectorFor(any())).thenReturn(Optional.of(connector));
        when(productRepository.findById(productId)).thenReturn(Optional.of(new ProductEntity()));
        when(connector.push(any(), any(), any())).thenReturn(new ShopConnector.PushResult(true, "remote-123", null));

        ShopProductListing listing = useCase.listProduct(userId, id, productId);

        ArgumentCaptor<ShopProductListing> captor = ArgumentCaptor.forClass(ShopProductListing.class);
        verify(listingRepository).save(captor.capture());
        ShopProductListing persisted = captor.getValue();
        assertThat(persisted.getShopConnectionId()).isEqualTo(id);
        assertThat(persisted.getProductId()).isEqualTo(productId);
        assertThat(persisted.getStatus()).isEqualTo("LISTED");
        assertThat(persisted.getRemoteProductId()).startsWith("remote-");
        assertThat(listing.getId()).isNotNull();
    }
}
