package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.mapper.UserAddressUpdateMapper;
import com.nexaplatform.dropshipping.application.usecase.impl.UserAddressUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.UserAddress;
import com.nexaplatform.dropshipping.domain.repository.UserAddressRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAddressUseCaseImplTest {

    @Mock
    UserAddressRepository userAddressRepository;
    @Mock
    UserAddressUpdateMapper userAddressUpdateMapper;
    @InjectMocks
    UserAddressUseCaseImpl useCase;

    @Test
    void save_stampsOwnerAndPersists() {
        UUID userId = UUID.randomUUID();
        UserAddress model = UserAddress.builder().fullName("Ada").build();
        when(userAddressRepository.save(model)).thenReturn(model.withId(UUID.randomUUID()));

        UserAddress saved = useCase.save(userId, model);

        assertThat(saved.getId()).isNotNull();
        assertThat(model.getUserId()).isEqualTo(userId);
        verify(userAddressRepository).save(model);
    }

    @Test
    void save_default_demotesPreviousDefault() {
        UUID userId = UUID.randomUUID();
        UserAddress previous = UserAddress.builder().id(UUID.randomUUID()).userId(userId).isDefault(true).build();
        UserAddress incoming = UserAddress.builder().fullName("Ada").isDefault(true).build();
        when(userAddressRepository.findByUserId(userId)).thenReturn(List.of(previous));
        lenient().when(userAddressRepository.save(incoming)).thenReturn(incoming.withId(UUID.randomUUID()));

        useCase.save(userId, incoming);

        assertThat(previous.isDefault()).isFalse();
        verify(userAddressRepository).save(previous);
        verify(userAddressRepository).save(incoming);
    }

    @Test
    void update_throwsWhenNotOwned() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UserAddress other = UserAddress.builder().id(id).userId(UUID.randomUUID()).build();
        when(userAddressRepository.getById(id)).thenReturn(other);

        UserAddress cambios = UserAddress.builder().build();
        assertThatThrownBy(() -> useCase.update(userId, id, cambios))
                .isInstanceOf(NotFoundException.class);
        verify(userAddressRepository, never()).update(other);
    }

    @Test
    void update_appliesPartialUpdateAndPersists() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UserAddress existing = UserAddress.builder().id(id).userId(userId).city("London").build();
        UserAddress incoming = UserAddress.builder().city("Paris").build();
        when(userAddressRepository.getById(id)).thenReturn(existing);
        when(userAddressRepository.update(existing)).thenReturn(existing);

        useCase.update(userId, id, incoming);

        verify(userAddressUpdateMapper).updateFromModel(incoming, existing);
        verify(userAddressRepository).update(existing);
    }

    @Test
    void delete_throwsWhenMissing() {
        UUID userId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(userAddressRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.delete(userId, id)).isInstanceOf(NotFoundException.class);
        verify(userAddressRepository, never()).delete(id);
    }
}
