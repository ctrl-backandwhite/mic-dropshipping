package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.OdmProject;
import com.nexaplatform.dropshipping.domain.repository.OdmProjectRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OdmProjectEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.OdmProjectEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OdmProjectJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link OdmProjectRepository} domain
 * port on top of Spring Data JPA. Owns the persistence-only concern of resolving
 * the {@code user} relation from the flattened {@code userId} on insert, and
 * re-applying the mutable fields onto the managed entity on update.
 * {@code findAll} preserves the legacy admin-list ordering (newest first).
 */
@Repository
@RequiredArgsConstructor
public class OdmProjectRepositoryImpl implements OdmProjectRepository {

    private final OdmProjectEntityMapper odmProjectEntityMapper;
    private final OdmProjectJpaRepositoryAdapter odmProjectJpaRepositoryAdapter;
    private final UserRepository userRepository;

    @Override
    public OdmProject save(OdmProject model) {
        OdmProjectEntity entity = resolveEntity(model);
        applyModel(entity, model);
        OdmProjectEntity saved = odmProjectJpaRepositoryAdapter.save(entity);
        return odmProjectEntityMapper.toDomain(saved);
    }

    @Override
    public OdmProject update(OdmProject model) {
        return this.save(model);
    }

    @Override
    public List<OdmProject> findAll() {
        return odmProjectEntityMapper.toDomainList(odmProjectJpaRepositoryAdapter.findAll());
    }

    @Override
    public List<OdmProject> findByUserId(UUID userId) {
        return odmProjectEntityMapper
                .toDomainList(odmProjectJpaRepositoryAdapter.findByUser_IdOrderByCreatedAtDesc(userId));
    }

    @Override
    public List<OdmProject> findByStatus(String status) {
        return odmProjectEntityMapper
                .toDomainList(odmProjectJpaRepositoryAdapter.findByStatusOrderByCreatedAtDesc(status));
    }

    @Override
    public OdmProject getById(UUID id) {
        return odmProjectJpaRepositoryAdapter.findById(id).map(odmProjectEntityMapper::toDomain).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        odmProjectJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return odmProjectJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private OdmProjectEntity resolveEntity(OdmProject model) {
        if (model.getId() != null) {
            return odmProjectJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("ODM project"));
        }
        return new OdmProjectEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving the owning user. */
    private void applyModel(OdmProjectEntity entity, OdmProject model) {
        // Campos escalares vía MapStruct; la relación gestionada (user) y el status condicional se
        // resuelven abajo porque requieren un lookup de repositorio / preservar el valor previo.
        odmProjectEntityMapper.updateEntity(entity, model);

        if (entity.getUser() == null) {
            entity.setUser(userRepository.findById(model.getUserId()).orElseThrow(() -> new NotFoundException("User")));
        }
        if (model.getStatus() != null) {
            entity.setStatus(model.getStatus());
        }
    }
}
