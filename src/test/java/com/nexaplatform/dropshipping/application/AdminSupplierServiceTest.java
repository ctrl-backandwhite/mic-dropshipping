package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.out.AdminSupplierToggleDtoOut;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.AdminSupplierMapper;
import com.nexaplatform.dropshipping.application.service.AdminSupplierService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSupplierServiceTest {

    @Mock SupplierRepository supplierRepository;
    @Mock AdminSupplierMapper adminSupplierMapper;

    @InjectMocks AdminSupplierService service;

    @Test
    void toggleVerified_flipsFlagAndSaves() {
        UUID id = UUID.randomUUID();
        var supplier = SupplierEntity.builder().verified(false).build();
        when(supplierRepository.findById(id)).thenReturn(Optional.of(supplier));
        when(adminSupplierMapper.toVerifiedToggle(supplier))
                .thenReturn(AdminSupplierToggleDtoOut.builder().id(id).verified(true).build());

        AdminSupplierToggleDtoOut result = service.toggleVerified(id);

        assertThat(supplier.isVerified()).isTrue();
        assertThat(result.getVerified()).isTrue();
        verify(supplierRepository).save(supplier);
    }

    @Test
    void toggleTrustPass_flipsFlagAndSaves() {
        UUID id = UUID.randomUUID();
        var supplier = SupplierEntity.builder().trustPass(true).build();
        when(supplierRepository.findById(id)).thenReturn(Optional.of(supplier));
        when(adminSupplierMapper.toTrustPassToggle(supplier))
                .thenReturn(AdminSupplierToggleDtoOut.builder().id(id).trustPass(false).build());

        AdminSupplierToggleDtoOut result = service.toggleTrustPass(id);

        assertThat(supplier.isTrustPass()).isFalse();
        assertThat(result.getTrustPass()).isFalse();
        verify(supplierRepository).save(supplier);
    }

    @Test
    void toggleVerified_throwsWhenSupplierMissing() {
        UUID id = UUID.randomUUID();
        when(supplierRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.toggleVerified(id))
                .isInstanceOf(NotFoundException.class);
    }
}
