package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.MentorBooking;
import com.nexaplatform.dropshipping.domain.repository.MentorBookingRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.MentorBookingEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.MentorBookingEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.MentorBookingJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.MentorProfileJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link MentorBookingRepository} domain
 * port on top of Spring Data JPA. Owns the persistence-only concerns the domain
 * model abstracts away: resolving the {@code mentor} and {@code learner}
 * relations from the flattened {@code mentorId}/{@code learnerId} on a managed
 * entity. {@code findByLearnerId} preserves the legacy ordering (by start date,
 * descending).
 */
@Repository
@RequiredArgsConstructor
public class MentorBookingRepositoryImpl implements MentorBookingRepository {

    private final MentorBookingEntityMapper mentorBookingEntityMapper;
    private final MentorBookingJpaRepositoryAdapter mentorBookingJpaRepositoryAdapter;
    private final MentorProfileJpaRepositoryAdapter mentorProfileJpaRepositoryAdapter;
    private final UserRepository userRepository;

    @Override
    public MentorBooking save(MentorBooking model) {
        MentorBookingEntity entity = resolveEntity(model);
        applyModel(entity, model);
        MentorBookingEntity saved = mentorBookingJpaRepositoryAdapter.save(entity);
        return mentorBookingEntityMapper.toDomain(saved);
    }

    @Override
    public List<MentorBooking> findByLearnerId(UUID learnerId) {
        return mentorBookingEntityMapper.toDomainList(
                mentorBookingJpaRepositoryAdapter.findByLearner_IdOrderByStartsAtDesc(learnerId));
    }

    @Override
    public MentorBooking update(MentorBooking model) {
        return this.save(model);
    }

    @Override
    public MentorBooking getById(UUID id) {
        return mentorBookingJpaRepositoryAdapter.findById(id)
                .map(mentorBookingEntityMapper::toDomain)
                .orElse(null);
    }

    @Override
    public void delete(UUID id) {
        mentorBookingJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return mentorBookingJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private MentorBookingEntity resolveEntity(MentorBooking model) {
        if (model.getId() != null) {
            return mentorBookingJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Booking"));
        }
        return new MentorBookingEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving mentor/learner relations. */
    private void applyModel(MentorBookingEntity entity, MentorBooking model) {
        if (model.getMentorId() != null) {
            entity.setMentor(mentorProfileJpaRepositoryAdapter.findById(model.getMentorId())
                    .orElseThrow(() -> new NotFoundException("Mentor")));
        }
        if (model.getLearnerId() != null) {
            entity.setLearner(userRepository.findById(model.getLearnerId())
                    .orElseThrow(() -> new NotFoundException("User")));
        }
        entity.setStartsAt(model.getStartsAt());
        entity.setDurationMin(model.getDurationMin());
        entity.setStatus(model.getStatus());
        entity.setTopic(model.getTopic());
    }
}
