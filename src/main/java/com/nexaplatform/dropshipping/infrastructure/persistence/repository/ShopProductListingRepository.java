package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopProductListingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShopProductListingRepository extends JpaRepository<ShopProductListingEntity, UUID> {
    List<ShopProductListingEntity> findByShopConnection_Id(UUID shopConnectionId);

    Optional<ShopProductListingEntity> findByShopConnection_IdAndProduct_Id(UUID shopConnectionId, UUID productId);

    Optional<ShopProductListingEntity> findByShopConnection_IdAndRemoteProductId(UUID shopConnectionId,
            String remoteProductId);
}
