package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.PodDesign;
import com.nexaplatform.dropshipping.domain.model.Warehouse;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.WarehouseEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WarehouseJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl.WarehouseRepositoryImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contrato de escritura de los puertos de repositorio.
 *
 * <p>{@link BaseRepository#update} traía un {@code default} que devolvía {@code null} sin escribir nada.
 * Un adaptador que se olvidara de implementarlo no fallaba: la escritura se perdía en silencio y el
 * {@code null} reventaba más adelante, lejos de la causa. Ocurría de verdad en dos sitios —editar un
 * almacén y guardar un diseño POD no persistían y devolvían {@code null} al caso de uso—, así que aquí
 * se fija tanto que esos dos adaptadores escriben como que el olvido vuelve a notarse.
 */
class BaseRepositoryUpdateContractTest {

    @Test
    void actualizarUnAlmacenEscribeDeVerdadYDevuelveLoPersistido() {
        WarehouseEntityMapper mapper = mock(WarehouseEntityMapper.class);
        WarehouseJpaRepositoryAdapter jpa = mock(WarehouseJpaRepositoryAdapter.class);
        WarehouseRepositoryImpl repo = new WarehouseRepositoryImpl(mapper, jpa);

        Warehouse model = new Warehouse();
        model.setId(UUID.randomUUID());
        model.setCode("ES-MAD");
        var entity = new com.nexaplatform.dropshipping.infrastructure.persistence.entity.WarehouseEntity();
        when(mapper.toEntity(model)).thenReturn(entity);
        when(jpa.save(entity)).thenReturn(entity);
        when(mapper.toDomain(entity)).thenReturn(model);

        Warehouse result = repo.update(model);

        verify(jpa).save(any()); // lo que faltaba: antes no se llamaba a JPA en absoluto
        assertThat(result).isSameAs(model); // y el caso de uso recibía null
    }

    @Test
    void losAdaptadoresQueEscribenImplementanUpdate() {
        // Los dos que estaban sin implementar. Si alguien los borra, este test lo dice antes de que la
        // escritura se pierda en producción.
        assertThat(declaraUpdate(WarehouseRepositoryImpl.class, Warehouse.class)).isTrue();
        assertThat(declaraUpdate(
                com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl.PodDesignRepositoryImpl.class,
                PodDesign.class)).isTrue();
    }

    @Test
    void elDefaultDelPuertoFallaEnLugarDeTragarseLaEscritura() {
        // Un adaptador que no implemente update ya no devuelve null en silencio.
        BaseRepository<String, String, UUID> sinImplementar = new BaseRepository<>() {
        };
        assertThatThrownBy(() -> sinImplementar.update("algo")).isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("update()");
    }

    private static boolean declaraUpdate(Class<?> type, Class<?> modelType) {
        for (Method m : type.getDeclaredMethods()) {
            if ("update".equals(m.getName()) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].equals(modelType)) {
                return true;
            }
        }
        return false;
    }
}
