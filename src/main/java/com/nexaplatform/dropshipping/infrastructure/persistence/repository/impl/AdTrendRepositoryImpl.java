package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.domain.model.AdTrend;
import com.nexaplatform.dropshipping.domain.repository.AdTrendRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AdTrendEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.AdTrendEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AdTrendJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link AdTrendRepository} domain port
 * on top of Spring Data JPA. The score-descending ordering of the legacy
 * intelligence listing is preserved by the finder methods.
 */
@Repository
@RequiredArgsConstructor
public class AdTrendRepositoryImpl implements AdTrendRepository {

    private final AdTrendEntityMapper adTrendEntityMapper;
    private final AdTrendJpaRepositoryAdapter adTrendJpaRepositoryAdapter;

    @Override
    public AdTrend save(AdTrend model) {
        AdTrendEntity entity = adTrendJpaRepositoryAdapter.save(adTrendEntityMapper.toEntity(model));
        return adTrendEntityMapper.toDomain(entity);
    }

    @Override
    public List<AdTrend> findAll() {
        return adTrendEntityMapper.toDomainList(adTrendJpaRepositoryAdapter.findAll());
    }

    @Override
    public List<AdTrend> findAllByScoreDesc() {
        return adTrendEntityMapper.toDomainList(adTrendJpaRepositoryAdapter.findAllByOrderByScoreDesc());
    }

    @Override
    public List<AdTrend> findBySourceByScoreDesc(String source) {
        return adTrendEntityMapper.toDomainList(adTrendJpaRepositoryAdapter.findBySourceOrderByScoreDesc(source));
    }

    @Override
    public AdTrend update(AdTrend model) {
        return this.save(model);
    }

    @Override
    public AdTrend getById(UUID id) {
        return adTrendJpaRepositoryAdapter.findById(id).map(adTrendEntityMapper::toDomain).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        adTrendJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return adTrendJpaRepositoryAdapter.existsById(id);
    }
}
