package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.MentorProfile;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link MentorProfile}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface MentorProfileRepository extends BaseRepository<MentorProfile, MentorProfile, UUID> {

    /** Active mentors ordered by creation date (descending) for the storefront listing. */
    List<MentorProfile> findActive();
}
