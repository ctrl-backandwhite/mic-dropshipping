package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserAddressEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code UserAddressRepository} domain port. */
public interface UserAddressJpaRepositoryAdapter extends JpaRepository<UserAddressEntity, UUID> {

    List<UserAddressEntity> findByUser_IdOrderByIsDefaultDescCreatedAtDesc(UUID userId);
}
