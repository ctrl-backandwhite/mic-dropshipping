package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.domain.model.AcademyCourse;
import com.nexaplatform.dropshipping.domain.repository.AcademyCourseRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.AcademyCourseEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.AcademyCourseJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link AcademyCourseRepository} domain
 * port on top of Spring Data JPA. {@code findPublished} preserves the legacy
 * storefront contract (published courses ordered by creation date, descending).
 */
@Repository
@RequiredArgsConstructor
public class AcademyCourseRepositoryImpl implements AcademyCourseRepository {

    private final AcademyCourseEntityMapper academyCourseEntityMapper;
    private final AcademyCourseJpaRepositoryAdapter academyCourseJpaRepositoryAdapter;

    @Override
    public AcademyCourse save(AcademyCourse model) {
        var entity = academyCourseJpaRepositoryAdapter.save(academyCourseEntityMapper.toEntity(model));
        return academyCourseEntityMapper.toDomain(entity);
    }

    @Override
    public List<AcademyCourse> findAll() {
        return academyCourseEntityMapper.toDomainList(academyCourseJpaRepositoryAdapter.findAll());
    }

    @Override
    public List<AcademyCourse> findPublished() {
        return academyCourseEntityMapper
                .toDomainList(academyCourseJpaRepositoryAdapter.findByPublishedTrueOrderByCreatedAtDesc());
    }

    @Override
    public Optional<AcademyCourse> findBySlug(String slug) {
        return academyCourseJpaRepositoryAdapter.findBySlug(slug).map(academyCourseEntityMapper::toDomain);
    }

    @Override
    public AcademyCourse update(AcademyCourse model) {
        return this.save(model);
    }

    @Override
    public AcademyCourse getById(UUID id) {
        return academyCourseJpaRepositoryAdapter.findById(id).map(academyCourseEntityMapper::toDomain).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        academyCourseJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return academyCourseJpaRepositoryAdapter.existsById(id);
    }
}
