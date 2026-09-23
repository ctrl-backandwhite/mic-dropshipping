package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.ConflictException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.impl.WarehouseUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.Warehouse;
import com.nexaplatform.dropshipping.domain.model.WarehouseStock;
import com.nexaplatform.dropshipping.domain.repository.ProductWarehouseStockRepository;
import com.nexaplatform.dropshipping.domain.repository.WarehouseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WarehouseUseCaseImpl}. Mockito drives the domain ports
 * ({@link WarehouseRepository}, {@link ProductWarehouseStockRepository}). Covers
 * the CRUD on the {@link Warehouse} model: listing, getById/NotFound, code-unique
 * guard on create/update, the partial-update merge and delete.
 */
@ExtendWith(MockitoExtension.class)
class WarehouseUseCaseImplTest {

    @Mock
    WarehouseRepository warehouseRepository;
    @Mock
    ProductWarehouseStockRepository productWarehouseStockRepository;
    @InjectMocks
    WarehouseUseCaseImpl useCase;

    private static Warehouse warehouse(UUID id, String code) {
        Warehouse w = Warehouse.builder().code(code).name("Main").country("ES").city("Madrid").active(true).build();
        w.setId(id);
        return w;
    }

    @Test
    void listActive_delegatesToRepository() {
        Warehouse w = warehouse(UUID.randomUUID(), "ES1");
        when(warehouseRepository.findActive()).thenReturn(List.of(w));

        assertThat(useCase.listActive()).containsExactly(w);
    }

    @Test
    void listAll_delegatesToRepository() {
        Warehouse w = warehouse(UUID.randomUUID(), "ES1");
        when(warehouseRepository.findAll()).thenReturn(List.of(w));

        assertThat(useCase.listAll()).containsExactly(w);
    }

    @Test
    void stockPerWarehouse_delegatesToStockRepository() {
        UUID productId = UUID.randomUUID();
        WarehouseStock stock = WarehouseStock.builder().warehouseId(UUID.randomUUID()).warehouseCode("ES1")
                .country("ES").stock(7).build();
        when(productWarehouseStockRepository.findByProductId(productId)).thenReturn(List.of(stock));

        assertThat(useCase.stockPerWarehouse(productId)).containsExactly(stock);
    }

    @Test
    void getById_returnsModel() {
        UUID id = UUID.randomUUID();
        Warehouse w = warehouse(id, "ES1");
        when(warehouseRepository.getById(id)).thenReturn(w);

        assertThat(useCase.getById(id)).isSameAs(w);
    }

    @Test
    void getById_throwsNotFoundWhenMissing() {
        UUID id = UUID.randomUUID();
        when(warehouseRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.getById(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_savesWhenCodeFreeAndReturnsModel() {
        Warehouse model = warehouse(null, "ES1");
        when(warehouseRepository.findAll()).thenReturn(List.of());
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));

        Warehouse result = useCase.create(model);

        assertThat(result).isSameAs(model);
        verify(warehouseRepository).save(model);
    }

    @Test
    void create_throwsConflictWhenCodeTaken() {
        Warehouse existing = warehouse(UUID.randomUUID(), "ES1");
        Warehouse model = warehouse(null, "es1"); // mismo código, distinto case
        when(warehouseRepository.findAll()).thenReturn(List.of(existing));

        assertThatThrownBy(() -> useCase.create(model)).isInstanceOf(ConflictException.class);
        verify(warehouseRepository, never()).save(any());
    }

    @Test
    void update_appliesPartialChangesAndReturnsUpdatedModel() {
        UUID id = UUID.randomUUID();
        Warehouse existing = warehouse(id, "ES1");
        when(warehouseRepository.getById(id)).thenReturn(existing);
        when(warehouseRepository.update(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));

        // Sin cambiar el code no se consulta findAll (chequeo de unicidad), por eso no se stubea.
        Warehouse changes = Warehouse.builder().name("Renamed").city("Barcelona").active(false).build();
        Warehouse result = useCase.update(id, changes);

        // Campos no nulos se aplican; code/country se conservan (vienen null en changes).
        assertThat(result).isSameAs(existing);
        assertThat(result.getName()).isEqualTo("Renamed");
        assertThat(result.getCity()).isEqualTo("Barcelona");
        assertThat(result.getCode()).isEqualTo("ES1");
        assertThat(result.getCountry()).isEqualTo("ES");
        assertThat(result.isActive()).isFalse();
        verify(warehouseRepository).update(existing);
    }

    @Test
    void update_throwsNotFoundWhenWarehouseMissing() {
        UUID id = UUID.randomUUID();
        when(warehouseRepository.getById(id)).thenReturn(null);

        Warehouse cambios = Warehouse.builder().build();
        assertThatThrownBy(() -> useCase.update(id, cambios)).isInstanceOf(NotFoundException.class);
        verify(warehouseRepository, never()).update(any());
    }

    @Test
    void update_throwsConflictWhenNewCodeBelongsToAnotherWarehouse() {
        UUID id = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Warehouse existing = warehouse(id, "ES1");
        Warehouse other = warehouse(otherId, "ES2");
        when(warehouseRepository.getById(id)).thenReturn(existing);
        when(warehouseRepository.findAll()).thenReturn(List.of(existing, other));

        Warehouse changes = Warehouse.builder().code("ES2").build();

        assertThatThrownBy(() -> useCase.update(id, changes)).isInstanceOf(ConflictException.class);
        verify(warehouseRepository, never()).update(any());
    }

    @Test
    void update_allowsKeepingOwnCode() {
        UUID id = UUID.randomUUID();
        Warehouse existing = warehouse(id, "ES1");
        when(warehouseRepository.getById(id)).thenReturn(existing);
        when(warehouseRepository.findAll()).thenReturn(List.of(existing));
        when(warehouseRepository.update(any(Warehouse.class))).thenAnswer(inv -> inv.getArgument(0));

        // Mismo código del propio almacén no debe disparar conflicto (excludeId == id).
        Warehouse changes = Warehouse.builder().code("ES1").name("X").build();
        Warehouse result = useCase.update(id, changes);

        assertThat(result.getName()).isEqualTo("X");
        verify(warehouseRepository).update(existing);
    }

    @Test
    void delete_checksExistenceThenDeletes() {
        UUID id = UUID.randomUUID();
        when(warehouseRepository.getById(id)).thenReturn(warehouse(id, "ES1"));

        useCase.delete(id);

        verify(warehouseRepository).delete(id);
    }

    @Test
    void delete_throwsNotFoundAndDoesNotDeleteWhenMissing() {
        UUID id = UUID.randomUUID();
        when(warehouseRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.delete(id)).isInstanceOf(NotFoundException.class);
        verify(warehouseRepository, never()).delete(any());
    }
}
