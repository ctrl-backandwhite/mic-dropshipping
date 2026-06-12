package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.MentorProfileUseCase;
import com.nexaplatform.dropshipping.domain.model.MentorProfile;
import com.nexaplatform.dropshipping.domain.repository.MentorProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Mentor-profile use case. Operates on the {@link MentorProfile} model and
 * delegates persistence to the domain port. Holds the storefront logic that used
 * to live in {@code AcademyController}: listing active mentors while hiding the
 * seeded "fake" system/partner accounts (DROP-575) and resolving a mentor by id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MentorProfileUseCaseImpl implements MentorProfileUseCase {

    private final MentorProfileRepository mentorProfileRepository;

    @Override
    @Transactional(readOnly = true)
    public List<MentorProfile> listActive() {
        // DROP-575: filter "fake" mentors seeded from the system accounts (NX036
        // Admin, NX036 Operator, NX036 Customer, NX036 Partner) and partner
        // companies (e.g. "Demo Partner — Sandbox"). They are not real mentors —
        // they leaked in when the table was populated. We hide them from the
        // public listing until the team decides whether to delete them from the
        // dataset or create real mentors.
        return mentorProfileRepository.findActive().stream().filter(m -> {
            String name = m.getDisplayName() != null ? m.getDisplayName() : "";
            String email = m.getEmail() != null ? m.getEmail().toLowerCase() : "";
            if (name.startsWith("NX036 ")) {
                return false;
            }
            if (email.startsWith("admin@") || email.startsWith("operator@") || email.startsWith("customer@")
                    || email.startsWith("partner@")) {
                return false;
            }
            if (email.endsWith("@partners.nx036.local")) {
                return false;
            }
            return true;
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MentorProfile getById(UUID id) {
        MentorProfile model = mentorProfileRepository.getById(id);
        if (model == null) {
            throw new NotFoundException("Mentor");
        }
        return model;
    }
}
