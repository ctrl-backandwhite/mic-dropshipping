package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.MentorBooking;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link MentorBooking}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface MentorBookingRepository extends BaseRepository<MentorBooking, MentorBooking, UUID> {

    /** Bookings made by a learner, ordered by start date (descending). */
    List<MentorBooking> findByLearnerId(UUID learnerId);
}
