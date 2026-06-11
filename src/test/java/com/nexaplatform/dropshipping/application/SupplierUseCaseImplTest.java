package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.SupplierUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.Supplier;
import com.nexaplatform.dropshipping.domain.repository.SupplierRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class SupplierUseCaseImplTest {

    @Mock SupplierRepository supplierRepository;

    @InjectMocks SupplierUseCaseImpl useCase;

    @Test
    void toggleVerified_flipsFlagAndPersists() {
        UUID id = UUID.randomUUID();
        Supplier supplier = Supplier.builder().id(id).verified(false).build();
        when(supplierRepository.getById(id)).thenReturn(supplier);
        when(supplierRepository.update(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier result = useCase.toggleVerified(id);

        assertThat(result.isVerified()).isTrue();
        verify(supplierRepository).update(supplier);
    }

    @Test
    void toggleTrustPass_flipsFlagAndPersists() {
        UUID id = UUID.randomUUID();
        Supplier supplier = Supplier.builder().id(id).trustPass(true).build();
        when(supplierRepository.getById(id)).thenReturn(supplier);
        when(supplierRepository.update(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier result = useCase.toggleTrustPass(id);

        assertThat(result.isTrustPass()).isFalse();
        verify(supplierRepository).update(supplier);
    }

    @Test
    void toggleVerified_throwsWhenSupplierMissing() {
        UUID id = UUID.randomUUID();
        when(supplierRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.toggleVerified(id))
                .isInstanceOf(NotFoundException.class);
    }
}
