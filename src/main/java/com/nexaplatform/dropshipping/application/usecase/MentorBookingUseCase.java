package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.application.BaseUseCase;
import com.nexaplatform.dropshipping.domain.model.MentorBooking;

import java.util.List;
import java.util.UUID;

/** Use-case port for mentor bookings; operates on the {@link MentorBooking} domain model. */
public interface MentorBookingUseCase extends BaseUseCase<MentorBooking, MentorBooking, UUID> {

    /** Books a session for a learner against the mentor referenced by the model. */
    MentorBooking book(UUID learnerId, MentorBooking model);

    /** Lists the bookings made by a learner. */
    List<MentorBooking> findByLearner(UUID learnerId);
}
