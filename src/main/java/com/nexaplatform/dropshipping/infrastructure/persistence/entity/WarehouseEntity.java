package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "warehouse")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WarehouseEntity extends BaseEntity {
    @Column(nullable = false, unique = true, length = 20) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 2)   private String country;
    @Column(length = 120) private String city;
    @Column(nullable = false)
    @Builder.Default private boolean active = true;
}
