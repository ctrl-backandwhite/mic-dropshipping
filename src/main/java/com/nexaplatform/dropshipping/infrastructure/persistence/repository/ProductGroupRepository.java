package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data repository for product groups (collections of products for shared margin rules). */
public interface ProductGroupRepository extends JpaRepository<ProductGroupEntity, UUID> {

    List<ProductGroupEntity> findAllByOrderByNameAsc();
}
