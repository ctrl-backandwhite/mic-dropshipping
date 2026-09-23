package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.UserAddress;
import com.nexaplatform.dropshipping.domain.repository.UserAddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserAddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.UserAddressEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link UserAddressRepository} domain port
 * on top of Spring Data JPA. {@code findByUserId} preserves the legacy contract of
 * the "my addresses" list (default first, then newest). The owning {@code user}
 * relation is resolved here from the model's {@code userId}.
 */
@Repository
@RequiredArgsConstructor
public class UserAddressRepositoryImpl implements UserAddressRepository {

    private final UserAddressEntityMapper userAddressEntityMapper;
    private final UserAddressJpaRepositoryAdapter userAddressJpaRepositoryAdapter;
    private final UserRepository userRepository;

    @Override
    public UserAddress save(UserAddress model) {
        UserAddressEntity entity = userAddressEntityMapper.toEntity(model);
        UserEntity user = userRepository.findById(model.getUserId()).orElseThrow(() -> new NotFoundException("User"));
        entity.setUser(user);
        return userAddressEntityMapper.toDomain(userAddressJpaRepositoryAdapter.save(entity));
    }

    @Override
    public List<UserAddress> findByUserId(UUID userId) {
        return userAddressEntityMapper
                .toDomainList(userAddressJpaRepositoryAdapter.findByUser_IdOrderByIsDefaultDescCreatedAtDesc(userId));
    }

    @Override
    public UserAddress update(UserAddress model) {
        return this.save(model);
    }

    @Override
    public UserAddress getById(UUID id) {
        return userAddressJpaRepositoryAdapter.findById(id).map(userAddressEntityMapper::toDomain).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        userAddressJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return userAddressJpaRepositoryAdapter.existsById(id);
    }
}
