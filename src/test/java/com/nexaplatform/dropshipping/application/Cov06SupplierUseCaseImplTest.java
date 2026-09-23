package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.usecase.SupplierUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.SupplierUseCaseImpl;
import com.nexaplatform.dropshipping.domain.model.Supplier;
import com.nexaplatform.dropshipping.domain.repository.SupplierRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierSearchService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierSearchService.IndexedSupplier;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.SupplierEntityMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Alta, edición, baja y listado de proveedores del panel de administración, más los KPIs derivados de
 * la valoración que se pintan en la ficha.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov06SupplierUseCaseImplTest {

    @Mock
    SupplierRepository supplierRepository;
    @Mock
    SupplierIndexer supplierIndexer;
    @Mock
    SupplierSearchService supplierSearchService;
    @Mock
    SupplierEntityMapper supplierEntityMapper;
    @Mock
    com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository jpaSupplierRepository;
    @Mock
    EntityManager em;
    @Mock
    Query conteoQuery;
    @Mock
    TypedQuery<Long> productosQuery;

    @InjectMocks
    SupplierUseCaseImpl useCase;

    @BeforeEach
    void preparar() {
        // El EntityManager entra por @PersistenceContext (inyección de campo): Mockito solo resuelve el
        // constructor, así que hay que ponerlo a mano o quedaría nulo.
        ReflectionTestUtils.setField(useCase, "em", em);
        when(em.createQuery(anyString())).thenReturn(conteoQuery);
        when(conteoQuery.getResultList()).thenReturn(List.of());
        when(em.createQuery(anyString(), eq(Long.class))).thenReturn(productosQuery);
        when(productosQuery.setParameter(anyString(), any())).thenReturn(productosQuery);
    }

    /* ------------------------------ KPIs derivados de la valoración ------------------------------ */

    /** Sin valoración se asume 4,0: los KPIs de la ficha nunca salen vacíos ni revientan por el nulo. */
    @Test
    void unProveedorSinValoracionSeTratacomoCuatroEstrellas() {
        Supplier sinRating = Supplier.builder().id(UUID.randomUUID()).build();
        when(supplierRepository.findAll()).thenReturn(List.of(sinRating));

        Supplier resultado = useCase.findAll().get(0);

        assertThat(resultado.getOnTimePct()).isEqualTo(92);
        assertThat(resultado.getDefectRate()).isEqualTo(0.8);
        assertThat(resultado.getResponseHours()).isEqualTo(24);
        assertThat(resultado.getLeadTimeDays()).isEqualTo(14);
    }

    /** Tramo "excelente" (>= 4,7): los mejores valores de respuesta y plazo de entrega. */
    @Test
    void unProveedorExcelenteRecibeLosMejoresIndicadores() {
        Supplier top = Supplier.builder().id(UUID.randomUUID()).rating(new BigDecimal("5.0")).build();
        when(supplierRepository.findAll()).thenReturn(List.of(top));

        Supplier resultado = useCase.findAll().get(0);

        assertThat(resultado.getOnTimePct()).isEqualTo(100);
        assertThat(resultado.getDefectRate()).isZero();
        assertThat(resultado.getResponseHours()).isEqualTo(4);
        assertThat(resultado.getLeadTimeDays()).isEqualTo(3);
    }

    /** Tramo intermedio "bueno" (>= 4,3 y < 4,7): valores medios, no los de excelente. */
    @Test
    void unProveedorBuenoCaeEnElTramoIntermedio() {
        Supplier bueno = Supplier.builder().id(UUID.randomUUID()).rating(new BigDecimal("4.5")).build();
        when(supplierRepository.findAll()).thenReturn(List.of(bueno));

        Supplier resultado = useCase.findAll().get(0);

        assertThat(resultado.getResponseHours()).isEqualTo(12);
        assertThat(resultado.getLeadTimeDays()).isEqualTo(7);
        assertThat(resultado.getDefectRate()).isEqualTo(0.4);
    }

    /** El número de productos sale de una sola consulta agrupada; el proveedor sin productos va a 0. */
    @Test
    void elNumeroDeProductosSeRellenaPorProveedorYCeroSiNoTiene() {
        UUID conProductos = UUID.randomUUID();
        UUID sinProductos = UUID.randomUUID();
        when(conteoQuery.getResultList()).thenReturn(List.<Object[]>of(new Object[]{conProductos, 12L}));
        when(supplierRepository.findAll()).thenReturn(
                List.of(Supplier.builder().id(conProductos).build(), Supplier.builder().id(sinProductos).build()));

        List<Supplier> resultado = useCase.findAll();

        assertThat(resultado.get(0).getProductCount()).isEqualTo(12);
        assertThat(resultado.get(1).getProductCount()).isZero();
    }

    /* ------------------------------ listado paginado ------------------------------ */

    /** Con el índice disponible la página se sirve de ahí y NO se consulta la base de datos. */
    @Test
    void laPaginaSeSirveDelIndiceCuandoEstaDisponible() {
        IndexedSupplier fila = new IndexedSupplier(UUID.randomUUID(), "ext-1", "Fábrica", "工厂", "CN", "Yiwu",
                new BigDecimal("4.8"), 6, true, false, 33);
        when(supplierSearchService.pageFromIndex(null, null, null, 0, 20))
                .thenReturn(Optional.of(new SupplierSearchService.IndexedPage(List.of(fila), 1)));

        SupplierUseCase.SupplierPage pagina = useCase.pageAdmin(null, null, null, 0, 20);

        assertThat(pagina.total()).isEqualTo(1);
        assertThat(pagina.items().get(0).getProductCount()).isEqualTo(33);
        assertThat(pagina.items().get(0).getResponseHours()).isEqualTo(4); // KPIs también sobre el índice
        verify(jpaSupplierRepository, never()).findAll(any(Pageable.class));
    }

    /**
     * Índice caído: se cae a SQL paginado ordenado por fecha de alta descendente. Una página negativa se
     * normaliza a 0 en vez de propagar el error de {@code PageRequest}.
     */
    @Test
    void sinIndiceSeCaeASqlPaginadoYLaPaginaNegativaSeNormaliza() {
        when(supplierSearchService.pageFromIndex(any(), any(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());
        SupplierEntity entidad = new SupplierEntity();
        UUID id = UUID.randomUUID();
        entidad.setId(id);
        Page<SupplierEntity> pagina = new PageImpl<>(List.of(entidad),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt")), 1);
        when(jpaSupplierRepository.findAll(any(Pageable.class))).thenReturn(pagina);
        when(supplierEntityMapper.toDomain(entidad)).thenReturn(Supplier.builder().id(id).build());
        when(conteoQuery.getResultList()).thenReturn(List.<Object[]>of(new Object[]{id, 4L}));

        SupplierUseCase.SupplierPage resultado = useCase.pageAdmin(null, null, null, -3, 20);

        assertThat(resultado.items()).hasSize(1);
        assertThat(resultado.items().get(0).getProductCount()).isEqualTo(4);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(jpaSupplierRepository).findAll(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    /* ------------------------------ alta ------------------------------ */

    @Test
    void noSePuedeCrearUnProveedorSinNombre() {
        Supplier sinNombre = Supplier.builder().name("  ").build();

        assertThatThrownBy(() -> useCase.create(sinNombre)).isInstanceOf(BusinessException.class);
        verify(supplierRepository, never()).save(any());
    }

    /**
     * Alta manual desde el panel: se rellenan origen, identificador externo y país porque son obligatorios
     * en el esquema, y el id que venga en el cuerpo se ignora para que nunca pise un proveedor existente.
     */
    @Test
    void elAltaManualRellenaLosValoresObligatoriosEIgnoraElIdRecibido() {
        Supplier entrante = Supplier.builder().id(UUID.randomUUID()).name("Proveedor Nuevo").build();
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier creado = useCase.create(entrante);

        assertThat(creado.getId()).isNull();
        assertThat(creado.getSource()).isEqualTo("manual");
        assertThat(creado.getExternalId()).startsWith("manual-");
        assertThat(creado.getCountry()).isEqualTo("CN");
    }

    @Test
    void elAltaRespetaElOrigenYPaisQueLleganInformados() {
        Supplier entrante = Supplier.builder().name("Proveedor").source("1688").externalId("ext-9").country("ES")
                .build();
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier creado = useCase.create(entrante);

        assertThat(creado.getSource()).isEqualTo("1688");
        assertThat(creado.getExternalId()).isEqualTo("ext-9");
        assertThat(creado.getCountry()).isEqualTo("ES");
    }

    /* ------------------------------ edición ------------------------------ */

    @Test
    void editarUnProveedorInexistenteEs404() {
        UUID id = UUID.randomUUID();
        when(supplierRepository.getById(id)).thenReturn(null);
        Supplier cambios = Supplier.builder().name("X").build();

        assertThatThrownBy(() -> useCase.update(id, cambios)).isInstanceOf(NotFoundException.class);
    }

    /**
     * En la edición, un nombre o país vacíos NO borran el valor guardado (el formulario manda cadenas
     * vacías para los campos que no se tocan), pero los interruptores verificado/trustPass sí se pisan
     * siempre: son casillas y desmarcarlas tiene que poder desactivarlas.
     */
    @Test
    void laEdicionIgnoraLosTextosEnBlancoPeroSiempreAplicaLosInterruptores() {
        UUID id = UUID.randomUUID();
        Supplier existente = Supplier.builder().id(id).name("Fábrica").country("CN").verified(true).trustPass(true)
                .build();
        when(supplierRepository.getById(id)).thenReturn(existente);
        when(supplierRepository.update(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier actualizado = useCase.update(id,
                Supplier.builder().name("   ").country("").city("Yiwu").verified(false).trustPass(false).build());

        assertThat(actualizado.getName()).isEqualTo("Fábrica");
        assertThat(actualizado.getCountry()).isEqualTo("CN");
        assertThat(actualizado.getCity()).isEqualTo("Yiwu");
        assertThat(actualizado.isVerified()).isFalse();
        assertThat(actualizado.isTrustPass()).isFalse();
        verify(supplierIndexer).indexSupplier(id);
    }

    /* ------------------------------ baja ------------------------------ */

    /**
     * Un proveedor con productos NO se borra: dejaría el catálogo apuntando a un proveedor inexistente.
     * El mensaje dice cuántos productos lo impiden para que el admin sepa qué mover primero.
     */
    @Test
    void noSeBorraUnProveedorConProductosAsociados() {
        UUID id = UUID.randomUUID();
        when(supplierRepository.getById(id)).thenReturn(Supplier.builder().id(id).build());
        when(productosQuery.getSingleResult()).thenReturn(3L);

        assertThatThrownBy(() -> useCase.delete(id)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("3 productos");
        verify(supplierRepository, never()).delete(any());
    }

    @Test
    void borrarUnProveedorSinProductosLoQuitaTambienDelIndice() {
        UUID id = UUID.randomUUID();
        when(supplierRepository.getById(id)).thenReturn(Supplier.builder().id(id).build());
        when(productosQuery.getSingleResult()).thenReturn(0L);

        useCase.delete(id);

        verify(supplierRepository).delete(id);
        verify(supplierIndexer).deleteFromIndex(id);
    }

    @Test
    void borrarUnProveedorInexistenteEs404() {
        UUID id = UUID.randomUUID();
        when(supplierRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.delete(id)).isInstanceOf(NotFoundException.class);
        verify(supplierIndexer, never()).deleteFromIndex(any());
    }

    /* ------------------------------ verificación ------------------------------ */

    /** Fijar el verificado a un valor concreto (no alternar) y reindexar para que el listado lo refleje. */
    @Test
    void fijarElVerificadoGuardaYReindexa() {
        UUID id = UUID.randomUUID();
        Supplier proveedor = Supplier.builder().id(id).verified(false).build();
        when(supplierRepository.getById(id)).thenReturn(proveedor);
        when(supplierRepository.update(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        Supplier resultado = useCase.setVerified(id, true);

        assertThat(resultado.isVerified()).isTrue();
        verify(supplierIndexer).indexSupplier(id);
    }
}
