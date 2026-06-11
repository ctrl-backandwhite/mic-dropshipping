package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.in.AddressDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AddressDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.MeAddressDtoMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserAddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserAddressRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Use-case service for the authenticated user's addresses. Holds all logic
 * previously living in {@code MeAddressController}: listing, creation, update
 * (with single-default handling) and deletion.
 */
@Service
@RequiredArgsConstructor
public class MeAddressService {

    private final UserAddressRepository repo;
    private final UserRepository userRepository;
    private final MeAddressDtoMapper mapper;

    /** List the user's addresses, default first then newest. */
    @Transactional(readOnly = true)
    public List<AddressDtoOut> list(UUID userId) {
        return mapper.toDtoOutList(repo.findByUser_IdOrderByIsDefaultDescCreatedAtDesc(userId));
    }

    /** Create a new address; if marked default, demote the previous default. */
    @Transactional
    public AddressDtoOut create(UUID userId, AddressDtoIn req) {
        UserEntity user = userRepository.findById(userId).orElseThrow();
        if (Boolean.TRUE.equals(req.getIsDefault())) {
            clearExistingDefault(user.getId());
        }
        UserAddressEntity entity = new UserAddressEntity();
        entity.setUser(user);
        mapper.applyToEntity(req, entity);
        entity.setDefault(Boolean.TRUE.equals(req.getIsDefault()));
        return mapper.toDtoOut(repo.save(entity));
    }

    /** Update an existing address owned by the user. */
    @Transactional
    public AddressDtoOut update(UUID userId, UUID id, AddressDtoIn req) {
        UserAddressEntity entity = require(userId, id);
        mapper.applyToEntity(req, entity);
        if (Boolean.TRUE.equals(req.getIsDefault()) && !entity.isDefault()) {
            clearExistingDefault(entity.getUser().getId());
            entity.setDefault(true);
        }
        return mapper.toDtoOut(repo.save(entity));
    }

    /** Delete an address owned by the user. */
    @Transactional
    public void delete(UUID userId, UUID id) {
        repo.delete(require(userId, id));
    }

    private void clearExistingDefault(UUID userId) {
        repo.findByUser_IdOrderByIsDefaultDescCreatedAtDesc(userId)
                .forEach(a -> {
                    if (a.isDefault()) {
                        a.setDefault(false);
                        repo.save(a);
                    }
                });
    }

    private UserAddressEntity require(UUID userId, UUID id) {
        UserAddressEntity a = repo.findById(id).orElseThrow(() -> new NotFoundException("Address not found"));
        if (!a.getUser().getId().equals(userId)) {
            throw new NotFoundException("Address not found");
        }
        return a;
    }
}
