package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.MentorProfileUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.MentorProfile;
import com.nexaplatform.dropshipping.domain.repository.MentorProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MentorProfileUseCaseImplTest {

    @Mock
    MentorProfileRepository mentorProfileRepository;
    @InjectMocks
    MentorProfileUseCaseImpl useCase;

    @Test
    void listActive_filtersSeededFakeAccounts() {
        MentorProfile real = MentorProfile.builder().displayName("Jane Doe").email("jane@real.com").build();
        MentorProfile seedName = MentorProfile.builder().displayName("NX036 Admin").email("x@real.com").build();
        MentorProfile seedEmail = MentorProfile.builder().displayName("Ops").email("operator@nx036.local").build();
        MentorProfile seedPartner = MentorProfile.builder().displayName("Demo").email("d@partners.nx036.local").build();
        when(mentorProfileRepository.findActive()).thenReturn(List.of(real, seedName, seedEmail, seedPartner));

        List<MentorProfile> result = useCase.listActive();

        assertThat(result).containsExactly(real);
    }

    @Test
    void getById_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(mentorProfileRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.getById(id)).isInstanceOf(NotFoundException.class);
    }
}
