package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.MentorBookingUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.MentorBooking;
import com.nexaplatform.dropshipping.domain.repository.MentorBookingRepository;
import com.nexaplatform.dropshipping.domain.repository.MentorProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MentorBookingUseCaseImplTest {

    @Mock MentorBookingRepository mentorBookingRepository;
    @Mock MentorProfileRepository mentorProfileRepository;
    @InjectMocks MentorBookingUseCaseImpl useCase;

    @Test
    void book_stampsLearnerAndRequestedStatusAndPersists() {
        UUID learnerId = UUID.randomUUID();
        UUID mentorId = UUID.randomUUID();
        MentorBooking incoming = MentorBooking.builder().mentorId(mentorId).durationMin(90).build();
        when(mentorProfileRepository.existsById(mentorId)).thenReturn(true);
        when(mentorBookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        useCase.book(learnerId, incoming);

        ArgumentCaptor<MentorBooking> captor = ArgumentCaptor.forClass(MentorBooking.class);
        verify(mentorBookingRepository).save(captor.capture());
        MentorBooking saved = captor.getValue();
        assertThat(saved.getLearnerId()).isEqualTo(learnerId);
        assertThat(saved.getStatus()).isEqualTo("REQUESTED");
        assertThat(saved.getMentorId()).isEqualTo(mentorId);
        assertThat(saved.getDurationMin()).isEqualTo(90);
    }

    @Test
    void book_throwsWhenMentorMissing() {
        UUID learnerId = UUID.randomUUID();
        UUID mentorId = UUID.randomUUID();
        MentorBooking incoming = MentorBooking.builder().mentorId(mentorId).build();
        when(mentorProfileRepository.existsById(mentorId)).thenReturn(false);

        assertThatThrownBy(() -> useCase.book(learnerId, incoming)).isInstanceOf(NotFoundException.class);
    }
}
