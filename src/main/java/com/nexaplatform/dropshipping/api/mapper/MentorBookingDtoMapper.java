package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.BookingDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BookingDtoOut;
import com.nexaplatform.dropshipping.domain.model.MentorBooking;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for mentor bookings: translates between the transport DTOs and
 * the {@link MentorBooking} domain model. Injected in the controller. The default
 * duration (60 min) is applied on {@code toDomain}; learner/status are stamped by
 * the use case. DtoOut field names preserve the exact JSON keys the frontend
 * already consumes (mirroring the legacy {@code BookingView} record).
 */
@Mapper(componentModel = "spring")
public interface MentorBookingDtoMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "mentorName", ignore = true)
    @Mapping(target = "learnerId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "mentorId", source = "mentorId")
    @Mapping(target = "startsAt", source = "startsAt")
    @Mapping(target = "durationMin", expression = "java(dtoIn.getDurationMin() == null ? 60 : dtoIn.getDurationMin())")
    @Mapping(target = "topic", source = "topic")
    MentorBooking toDomain(BookingDtoIn dtoIn);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "mentorId", source = "mentorId")
    @Mapping(target = "mentorName", source = "mentorName")
    @Mapping(target = "startsAt", source = "startsAt")
    @Mapping(target = "durationMin", source = "durationMin")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "topic", source = "topic")
    BookingDtoOut toDtoOut(MentorBooking model);

    List<BookingDtoOut> toDtoOutList(List<MentorBooking> models);
}
