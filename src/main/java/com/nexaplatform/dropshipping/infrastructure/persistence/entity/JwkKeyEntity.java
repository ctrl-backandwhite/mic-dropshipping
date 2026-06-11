package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "jwk_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JwkKeyEntity extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String kid;

    @Column(name = "public_key", nullable = false, columnDefinition = "TEXT")
    private String publicKey;

    @Column(name = "private_key", nullable = false, columnDefinition = "TEXT")
    private String privateKey;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "rotated_at")
    private Instant rotatedAt;
}
