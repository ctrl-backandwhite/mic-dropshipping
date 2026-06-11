package com.nexaplatform.dropshipping.application.usecase.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.UserAddressUpdateMapper;
import com.nexaplatform.dropshipping.application.usecase.UserAddressUseCase;
import com.nexaplatform.dropshipping.domain.model.UserAddress;
import com.nexaplatform.dropshipping.domain.repository.UserAddressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Use case for the authenticated user's addresses. Operates on the
 * {@link UserAddress} model and delegates persistence to the domain port. Holds
 * all logic previously living in {@code MeAddressService}: listing, creation,
 * update (with single-default handling) and deletion, every operation scoped to
 * the owning user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAddressUseCaseImpl implements UserAddressUseCase {

    private final UserAddressRepository userAddressRepository;
    private final UserAddressUpdateMapper userAddressUpdateMapper;

    /** List the user's addresses, default first then newest. */
    @Override
    @Transactional(readOnly = true)
    public List<UserAddress> findAll(UUID userId) {
        return userAddressRepository.findByUserId(userId);
    }

    /** Create a new address; if marked default, demote the previous default. */
    @Override
    @Transactional
    public UserAddress save(UUID userId, UserAddress model) {
        if (model.isDefault()) {
            clearExistingDefault(userId);
        }
        model.setUserId(userId);
        UserAddress saved = userAddressRepository.save(model);
        log.info("::> [ADDRESS] Address created id={} userId={}", saved.getId(), userId);
        return saved;
    }

    /** Update an existing address owned by the user. */
    @Override
    @Transactional
    public UserAddress update(UUID userId, UUID id, UserAddress model) {
        UserAddress existing = require(userId, id);
        userAddressUpdateMapper.updateFromModel(model, existing);
        if (model.isDefault() && !existing.isDefault()) {
            clearExistingDefault(existing.getUserId());
            existing.setDefault(true);
        }
        UserAddress saved = userAddressRepository.update(existing);
        log.info("::> [ADDRESS] Address updated id={} userId={}", id, userId);
        return saved;
    }

    /** Delete an address owned by the user. */
    @Override
    @Transactional
    public void delete(UUID userId, UUID id) {
        require(userId, id);
        userAddressRepository.delete(id);
        log.info("::> [ADDRESS] Address deleted id={} userId={}", id, userId);
    }

    private void clearExistingDefault(UUID userId) {
        userAddressRepository.findByUserId(userId)
                .forEach(a -> {
                    if (a.isDefault()) {
                        a.setDefault(false);
                        userAddressRepository.save(a);
                    }
                });
    }

    private UserAddress require(UUID userId, UUID id) {
        UserAddress a = userAddressRepository.getById(id);
        if (Objects.isNull(a) || !a.getUserId().equals(userId)) {
            throw new NotFoundException("Address not found");
        }
        return a;
    }
}
