package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.OdmProject;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the ODM/OEM aggregate (DROP-7). Operates on the
 * {@link OdmProject} domain model; user operations are scoped to the owning
 * {@code userId}.
 */
public interface OdmProjectUseCase {

    /** Creates a project for the user, deriving the SLA from its kind. */
    OdmProject create(UUID userId, OdmProject model);

    /** Lists the user's projects, newest first. */
    List<OdmProject> myProjects(UUID userId);

    /** Lists all projects (admin), optionally filtered by status. */
    List<OdmProject> adminList(String status);

    /** Updates the status of a project (admin). */
    OdmProject setStatus(UUID id, String status);
}
