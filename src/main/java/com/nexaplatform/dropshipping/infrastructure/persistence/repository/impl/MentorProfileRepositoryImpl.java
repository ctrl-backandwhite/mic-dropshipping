package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.MentorProfile;
import com.nexaplatform.dropshipping.domain.repository.MentorProfileRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.MentorProfileEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.MentorProfileEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.MentorProfileJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link MentorProfileRepository} domain
 * port on top of Spring Data JPA. Owns the persistence-only concern of resolving
 * the {@code user} relation from the flattened {@code userId} on a managed entity.
 * {@code findActive} preserves the legacy storefront contract (active mentors
 * ordered by creation date, descending).
 */
@Repository
@RequiredArgsConstructor
public class MentorProfileRepositoryImpl implements MentorProfileRepository {

    private final MentorProfileEntityMapper mentorProfileEntityMapper;
    private final MentorProfileJpaRepositoryAdapter mentorProfileJpaRepositoryAdapter;
    private final UserRepository userRepository;

    @Override
    public MentorProfile save(MentorProfile model) {
        MentorProfileEntity entity = resolveEntity(model);
        applyModel(entity, model);
        MentorProfileEntity saved = mentorProfileJpaRepositoryAdapter.save(entity);
        return mentorProfileEntityMapper.toDomain(saved);
    }

    @Override
    public List<MentorProfile> findActive() {
        return mentorProfileEntityMapper.toDomainList(
                mentorProfileJpaRepositoryAdapter.findByActiveTrueOrderByCreatedAtDesc());
    }

    @Override
    public MentorProfile update(MentorProfile model) {
        return this.save(model);
    }

    @Override
    public MentorProfile getById(UUID id) {
        return mentorProfileJpaRepositoryAdapter.findById(id)
                .map(mentorProfileEntityMapper::toDomain)
                .orElse(null);
    }

    @Override
    public void delete(UUID id) {
        mentorProfileJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return mentorProfileJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private MentorProfileEntity resolveEntity(MentorProfile model) {
        if (model.getId() != null) {
            return mentorProfileJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Mentor"));
        }
        return new MentorProfileEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving the user relation. */
    private void applyModel(MentorProfileEntity entity, MentorProfile model) {
        if (model.getUserId() != null) {
            entity.setUser(userRepository.findById(model.getUserId())
                    .orElseThrow(() -> new NotFoundException("User")));
        }
        entity.setHeadline(model.getHeadline());
        entity.setBio(model.getBio());
        entity.setExpertise(model.getExpertise());
        entity.setLanguages(model.getLanguages());
        entity.setHourlyRateUsdCents(model.getHourlyRateUsdCents());
        entity.setTimezone(model.getTimezone());
        entity.setActive(model.isActive());
    }
}
