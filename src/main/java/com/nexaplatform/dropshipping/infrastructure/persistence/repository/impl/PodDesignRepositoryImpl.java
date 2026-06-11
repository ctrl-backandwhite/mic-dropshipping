package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.PodDesign;
import com.nexaplatform.dropshipping.domain.repository.PodDesignRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PodDesignEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.PodDesignEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PodDesignJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link PodDesignRepository} domain port
 * on top of Spring Data JPA. Owns the persistence-only concerns the domain model
 * abstracts away: resolving the {@code user} and {@code product} relations from
 * the flattened {@code userId} / {@code productId}. {@code findByUserId} preserves
 * the legacy ordering (newest first).
 */
@Repository
@RequiredArgsConstructor
public class PodDesignRepositoryImpl implements PodDesignRepository {

    private final PodDesignEntityMapper podDesignEntityMapper;
    private final PodDesignJpaRepositoryAdapter podDesignJpaRepositoryAdapter;
    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    @Override
    public PodDesign save(PodDesign model) {
        PodDesignEntity entity = podDesignEntityMapper.toEntity(model);
        entity.setUser(userRepository.findById(model.getUserId())
                .orElseThrow(() -> new NotFoundException("User")));
        entity.setProduct(productRepository.findById(model.getProductId())
                .orElseThrow(() -> new NotFoundException("Product")));
        PodDesignEntity saved = podDesignJpaRepositoryAdapter.save(entity);
        return podDesignEntityMapper.toDomain(saved);
    }

    @Override
    public List<PodDesign> findByUserId(UUID userId) {
        return podDesignEntityMapper.toDomainList(
                podDesignJpaRepositoryAdapter.findByUser_IdOrderByCreatedAtDesc(userId));
    }

    @Override
    public PodDesign getById(UUID id) {
        return podDesignJpaRepositoryAdapter.findById(id)
                .map(podDesignEntityMapper::toDomain)
                .orElse(null);
    }

    @Override
    public void delete(UUID id) {
        podDesignJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return podDesignJpaRepositoryAdapter.existsById(id);
    }
}
