package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "pod_design")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PodDesignEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;

    @Column(nullable = false, length = 200)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canvas_json", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> canvasJson = new HashMap<>();

    @Column(name = "mockup_url", length = 800)
    private String mockupUrl;
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";
    @Column(name = "ai_prompt", length = 1000)
    private String aiPrompt;
}
