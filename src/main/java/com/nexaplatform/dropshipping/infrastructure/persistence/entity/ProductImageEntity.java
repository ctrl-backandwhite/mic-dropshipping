package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "product_image")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductImageEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;

    @Column(nullable = false)
    private int position;

    @Column(length = 30)
    private String role;

    @Column(name = "source_url", nullable = false, length = 800)
    private String sourceUrl;

    @Column(name = "cdn_url", length = 800)
    private String cdnUrl;

    private Integer width;
    private Integer height;
    private Long bytes;

    @Column(length = 80)
    private String hash;

    @Enumerated(EnumType.STRING)
    @Column(name = "mirror_status", length = 20)
    private MirrorStatus mirrorStatus;

    @Column(name = "mirrored_at")
    private Instant mirroredAt;
}
