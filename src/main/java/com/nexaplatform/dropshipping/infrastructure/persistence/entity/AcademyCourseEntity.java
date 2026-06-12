package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "academy_course")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcademyCourseEntity extends BaseEntity {
    @Column(nullable = false, unique = true, length = 220)
    private String slug;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(columnDefinition = "TEXT")
    private String description;
    @Column(length = 120)
    private String instructor;
    @Column(name = "duration_minutes")
    private Integer durationMinutes;
    @Column(name = "cover_url", length = 800)
    private String coverUrl;
    @Column(name = "video_url", length = 800)
    private String videoUrl;
    @Column(nullable = false, length = 8)
    @Builder.Default
    private String locale = "es";
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String level = "BEGINNER";
    @Column(nullable = false)
    @Builder.Default
    private boolean published = true;
}
