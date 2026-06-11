package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.MentorBooking;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.MentorBookingEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link MentorBooking} domain model and
 * the JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}. The
 * {@code mentor} and {@code learner} relations are resolved by the repository
 * adapter (which owns the managed entity), so they are ignored on
 * {@code toEntity}; the domain side carries the flattened {@code mentorId}/
 * {@code learnerId} and the read-only {@code mentorName} (resolved from the
 * mentor's user, falling back to the email).
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface MentorBookingEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "mentorId", expression = "java(entity.getMentor() != null ? entity.getMentor().getId() : null)")
    @Mapping(target = "mentorName", expression = "java(mentorName(entity))")
    @Mapping(target = "learnerId", expression = "java(entity.getLearner() != null ? entity.getLearner().getId() : null)")
    @Mapping(target = "startsAt", source = "startsAt")
    @Mapping(target = "durationMin", source = "durationMin")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "topic", source = "topic")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    MentorBooking toDomain(MentorBookingEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "mentor", ignore = true)
    @Mapping(target = "learner", ignore = true)
    @Mapping(target = "startsAt", source = "startsAt")
    @Mapping(target = "durationMin", source = "durationMin")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "topic", source = "topic")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    MentorBookingEntity toEntity(MentorBooking model);

    List<MentorBooking> toDomainList(List<MentorBookingEntity> entities);

    /** Resolves the mentor's display name (falling back to the email) from the booking. */
    default String mentorName(MentorBookingEntity entity) {
        if (entity.getMentor() == null || entity.getMentor().getUser() == null) {
            return null;
        }
        var mu = entity.getMentor().getUser();
        return mu.getDisplayName() != null ? mu.getDisplayName() : mu.getEmail();
    }
}
