package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.OdmProjectUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.OdmProject;
import com.nexaplatform.dropshipping.domain.repository.OdmProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OdmProjectUseCaseImplTest {

    @Mock OdmProjectRepository odmProjectRepository;
    @InjectMocks OdmProjectUseCaseImpl useCase;

    @Test
    void create_derivesSlaFromKindAndSetsIntakeStatus() {
        UUID userId = UUID.randomUUID();
        OdmProject incoming = OdmProject.builder().kind("OEM").title("t").build();
        when(odmProjectRepository.save(incoming)).thenReturn(incoming.withId(UUID.randomUUID()));

        OdmProject saved = useCase.create(userId, incoming);

        ArgumentCaptor<OdmProject> captor = ArgumentCaptor.forClass(OdmProject.class);
        verify(odmProjectRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getSlaDays()).isEqualTo(30);
        assertThat(captor.getValue().getStatus()).isEqualTo("INTAKE");
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void adminList_withoutStatusReturnsFindAll() {
        when(odmProjectRepository.findAll()).thenReturn(List.of(OdmProject.builder().build()));

        assertThat(useCase.adminList(null)).hasSize(1);
        verify(odmProjectRepository).findAll();
    }

    @Test
    void setStatus_updatesAndPersists() {
        UUID id = UUID.randomUUID();
        OdmProject existing = OdmProject.builder().id(id).status("INTAKE").build();
        when(odmProjectRepository.getById(id)).thenReturn(existing);
        when(odmProjectRepository.update(existing)).thenReturn(existing);

        useCase.setStatus(id, "APPROVED");

        assertThat(existing.getStatus()).isEqualTo("APPROVED");
        verify(odmProjectRepository).update(existing);
    }

    @Test
    void setStatus_throwsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(odmProjectRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.setStatus(id, "APPROVED")).isInstanceOf(NotFoundException.class);
    }
}
