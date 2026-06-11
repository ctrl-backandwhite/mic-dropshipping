package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_address")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserAddressEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(length = 80)
    private String label;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(length = 40) private String phone;
    @Column(nullable = false, length = 300) private String line1;
    @Column(length = 300) private String line2;
    @Column(nullable = false, length = 200) private String city;
    @Column(length = 200) private String state;
    @Column(name = "postal_code", length = 40) private String postalCode;
    @Column(nullable = false, length = 60) private String country;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;
}
