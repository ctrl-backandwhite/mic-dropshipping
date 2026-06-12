package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.MentorBookingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface MentorBookingRepository extends JpaRepository<MentorBookingEntity, UUID> {
    List<MentorBookingEntity> findByLearner_IdOrderByStartsAtDesc(UUID learnerId);

    List<MentorBookingEntity> findByMentor_IdOrderByStartsAtDesc(UUID mentorId);
}
