package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.in.AdminUserEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminUserRoleUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserPageDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AdminUserMapper;
import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Use-case service for the Admin Users panel: listing/filtering plus the
 * inline lock/unlock/role/edit/activate mutations. Holds all logic that
 * previously lived in {@code AdminUsersListController}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserQueryService {

    private final UserRepository userRepository;
    private final AdminUserMapper adminUserMapper;

    @Transactional(readOnly = true)
    public AdminUserPageDtoOut listUsers(String role, String q, String country, int page, int size) {
        String needle = q == null ? "" : q.trim().toLowerCase();
        List<UserEntity> all = userRepository.findAll().stream()
                .filter(u -> role == null || role.isBlank() || u.getRole().name().equalsIgnoreCase(role))
                .filter(u -> country == null || country.isBlank()
                          || (u.getCountry() != null && u.getCountry().equalsIgnoreCase(country)))
                .filter(u -> needle.isEmpty()
                          || (u.getEmail() != null && u.getEmail().toLowerCase().contains(needle))
                          || (u.getDisplayName() != null && u.getDisplayName().toLowerCase().contains(needle))
                          || (u.getCompanyName() != null && u.getCompanyName().toLowerCase().contains(needle)))
                .sorted(Comparator.comparing(UserEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int total = all.size();
        int from = Math.min(page * size, total);
        int to = Math.min(from + size, total);
        List<AdminUserDtoOut> items = adminUserMapper.toDtos(all.subList(from, to));
        return AdminUserPageDtoOut.builder()
                .items(items)
                .totalElements(total)
                .totalPages((int) Math.ceil((double) total / Math.max(1, size)))
                .page(page)
                .size(size)
                .build();
    }

    @Transactional
    public AdminUserDtoOut changeRole(UUID id, AdminUserRoleUpdateDtoIn dto) {
        String role = dto.getRole();
        if (role == null) throw new BusinessException("role required");
        UserEntity u = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        u.setRole(UserRole.valueOf(role.toUpperCase()));
        userRepository.save(u);
        return adminUserMapper.toDto(u);
    }

    /**
     * DROP-586: inline edit of a user's basic fields from the admin panel.
     * Only the fields present (non-null) in the body are changed.
     */
    @Transactional
    public AdminUserDtoOut editUser(UUID id, AdminUserEditDtoIn dto) {
        UserEntity u = userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
        adminUserMapper.updateUserFromDto(dto, u);
        if (dto.getActive() != null) {
            u.setActive(dto.getActive());
        }
        userRepository.save(u);
        return adminUserMapper.toDto(u);
    }

    @Transactional
    public AdminUserDtoOut lock(UUID id, int minutes) {
        UserEntity u = userRepository.findById(id).orElseThrow();
        u.setLockedUntil(Instant.now().plus(minutes, ChronoUnit.MINUTES));
        userRepository.save(u);
        return adminUserMapper.toDto(u);
    }

    @Transactional
    public AdminUserDtoOut unlock(UUID id) {
        UserEntity u = userRepository.findById(id).orElseThrow();
        u.setLockedUntil(null);
        u.setFailedLoginCount(0);
        userRepository.save(u);
        return adminUserMapper.toDto(u);
    }

    @Transactional
    public AdminUserDtoOut forceActivate(UUID id) {
        UserEntity u = userRepository.findById(id).orElseThrow();
        u.setActive(true);
        u.setActivationCode(null);
        u.setActivationCodeExpiresAt(null);
        userRepository.save(u);
        return adminUserMapper.toDto(u);
    }
}
