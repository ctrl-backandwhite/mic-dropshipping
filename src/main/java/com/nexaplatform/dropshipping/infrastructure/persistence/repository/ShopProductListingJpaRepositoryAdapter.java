package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopProductListingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code ShopProductListingRepository} domain port. */
public interface ShopProductListingJpaRepositoryAdapter extends JpaRepository<ShopProductListingEntity, UUID> {

    List<ShopProductListingEntity> findByShopConnection_Id(UUID shopConnectionId);

    Optional<ShopProductListingEntity> findByShopConnection_IdAndProduct_Id(UUID shopConnectionId, UUID productId);
}
