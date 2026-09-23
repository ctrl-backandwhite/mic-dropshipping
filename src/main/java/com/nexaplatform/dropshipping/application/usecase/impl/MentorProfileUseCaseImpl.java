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
        return mentorProfileRepository.findActive().stream().filter(MentorProfileUseCaseImpl::isPublicMentor).toList();
    }

    /**
     * DROP-575: filtra los mentores "falsos" sembrados desde las cuentas de sistema (NX036 Admin, NX036
     * Operator, NX036 Customer, NX036 Partner) y desde las empresas partner (p. ej. "Demo Partner —
     * Sandbox"). No son mentores reales: se colaron al poblar la tabla. Se ocultan del listado público
     * hasta que el equipo decida si se borran del dataset o se crean mentores de verdad.
     *
     * <p>DROP-667: las cuentas de QA/prueba tampoco pueden salir como mentores públicos.
     */
    private static boolean isPublicMentor(MentorProfile m) {
        String name = m.getDisplayName() != null ? m.getDisplayName() : "";
        String email = m.getEmail() != null ? m.getEmail().toLowerCase() : "";
        if (name.startsWith("NX036 ") || name.toUpperCase().startsWith("QA ")) {
            return false;
        }
        return !email.startsWith("admin@") && !email.startsWith("operator@") && !email.startsWith("customer@")
                && !email.startsWith("partner@") && !email.endsWith("@partners.nx036.local") && !email.contains("qa-")
                && !email.endsWith("@example.com");
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
