package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.MentorBookingUseCase;
import com.nexaplatform.dropshipping.domain.model.MentorBooking;
import com.nexaplatform.dropshipping.domain.repository.MentorBookingRepository;
import com.nexaplatform.dropshipping.domain.repository.MentorProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Mentor-booking use case. Operates on the {@link MentorBooking} model and
 * delegates persistence to the domain port. Holds the logic that used to live in
 * {@code AcademyController}: validating the mentor, stamping the learner/ownership
 * and the initial {@code REQUESTED} status, and listing the current user's
 * bookings. Bookings are scoped to the authenticated learner.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MentorBookingUseCaseImpl implements MentorBookingUseCase {

    private final MentorBookingRepository mentorBookingRepository;
    private final MentorProfileRepository mentorProfileRepository;

    @Override
    @Transactional
    public MentorBooking book(UUID learnerId, MentorBooking model) {
        if (!mentorProfileRepository.existsById(model.getMentorId())) {
            throw new NotFoundException("Mentor");
        }
        MentorBooking toSave = model.withLearnerId(learnerId).withStatus("REQUESTED");
        return mentorBookingRepository.save(toSave);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MentorBooking> findByLearner(UUID learnerId) {
        return mentorBookingRepository.findByLearnerId(learnerId);
    }
}
