package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin view of a user row. Built from the JPA entity by MapStruct.
 * Field names preserve the exact JSON keys the controller previously emitted.
 */
@Value
@Builder
public class AdminUserDtoOut {

    UUID id;
    String email;
    String role;
    boolean active;
    String displayName;
    String companyName;
    String country;
    String language;
    Instant lockedUntil;
    int failedLoginCount;
    Instant lastLogin;
    Instant createdAt;
}
