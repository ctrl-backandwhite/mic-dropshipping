package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.OdmProject;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link OdmProject}. Implemented by an infrastructure
 * adapter bridging to Spring Data JPA. Distinct from the legacy Spring Data
 * interface in {@code infrastructure.persistence.repository}.
 */
public interface OdmProjectRepository extends BaseRepository<OdmProject, OdmProject, UUID> {

    /** Lists the projects owned by a user, newest first. */
    List<OdmProject> findByUserId(UUID userId);

    /** Lists the projects in a given status, newest first. */
    List<OdmProject> findByStatus(String status);
}
