package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.MentorBookingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code MentorBookingRepository} domain port. */
public interface MentorBookingJpaRepositoryAdapter extends JpaRepository<MentorBookingEntity, UUID> {

    List<MentorBookingEntity> findByLearner_IdOrderByStartsAtDesc(UUID learnerId);
}
