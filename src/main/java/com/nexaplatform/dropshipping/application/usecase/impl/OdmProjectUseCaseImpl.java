package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.OdmProjectUseCase;
import com.nexaplatform.dropshipping.domain.model.OdmProject;
import com.nexaplatform.dropshipping.domain.repository.OdmProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * ODM/OEM use case (DROP-7). Operates on the {@link OdmProject} model and
 * delegates persistence to the domain port. Holds the logic that used to live in
 * {@code PlatformExtrasService}: deriving the SLA from the project kind on intake,
 * the owner-scoped and admin listings and the status transition.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OdmProjectUseCaseImpl implements OdmProjectUseCase {

    private final OdmProjectRepository odmProjectRepository;

    @Override
    @Transactional
    public OdmProject create(UUID userId, OdmProject model) {
        int sla = switch (model.getKind()) {
            case "ODM_PAID" -> 7;
            case "OEM" -> 30;
            case "CUSTOM_PACKAGING" -> 14;
            default -> 21;  // ODM_FREE
        };
        model.setUserId(userId);
        model.setSlaDays(sla);
        model.setStatus("INTAKE");
        OdmProject saved = odmProjectRepository.save(model);
        log.info("::> [ODM] Project created id={}", saved.getId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OdmProject> myProjects(UUID userId) {
        return odmProjectRepository.findByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OdmProject> adminList(String status) {
        return (status == null || status.isBlank())
                ? odmProjectRepository.findAll()
                : odmProjectRepository.findByStatus(status);
    }

    @Override
    @Transactional
    public OdmProject setStatus(UUID id, String status) {
        OdmProject existing = odmProjectRepository.getById(id);
        if (Objects.isNull(existing)) {
            throw new NotFoundException("ODM project");
        }
        if (status != null) {
            existing.setStatus(status);
        }
        OdmProject saved = odmProjectRepository.update(existing);
        log.info("::> [ODM] Project status updated id={}", id);
        return saved;
    }
}
