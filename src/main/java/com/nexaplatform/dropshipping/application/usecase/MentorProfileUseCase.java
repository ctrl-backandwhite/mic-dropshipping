package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.MentorProfile;

import java.util.List;
import java.util.UUID;

/** Use-case port for mentor profiles; operates on the {@link MentorProfile} domain model. */
public interface MentorProfileUseCase extends BaseUseCase<MentorProfile, MentorProfile, UUID> {

    /** Lists active mentors, hiding the seeded "fake" system/partner accounts (DROP-575). */
    List<MentorProfile> listActive();
}
