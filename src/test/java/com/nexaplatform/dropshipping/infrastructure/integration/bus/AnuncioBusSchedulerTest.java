package com.nexaplatform.dropshipping.infrastructure.integration.bus;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.domain.enums.BusAnuncioEstado;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Anuncio diferido de productos al bus del catálogo.
 *
 * <p>Por qué existe: hasta el 4-sep-2026 la petición que marcaba un producto como certificado
 * construía la ficha ENTERA del evento antes de responder —varias consultas más el mapeo de los ocho
 * idiomas, las variantes, las imágenes y las reseñas, serializado a JSON—. Marcar tardaba segundos, y
 * aplicar un recargo a un lote tardaba eso multiplicado por el número de productos: el admin tenía que
 * recargar la página para ver su propio cambio.
 *
 * <p>Ahora la petición solo deja la marca y este barrido hace el trabajo. Lo que se fija aquí es lo
 * que hace falta para que el cambio no rompa la garantía anterior: que nada se pierda, que el fallo se
 * VEA y que el planificador no se quede bloqueado.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnuncioBusSchedulerTest {

    @Mock
    ProductRepository productRepository;
    @Mock
    CatalogoBusService bus;
    @Mock
    CatalogUseCase catalogUseCase;
    @Mock
    ObjectProvider<CatalogoBusService> proveedorDelBus;
    @Mock
    PlatformTransactionManager gestorDeTransacciones;

    AnuncioBusScheduler scheduler;

    @BeforeEach
    void montarElBarrido() throws Exception {
        when(proveedorDelBus.getIfAvailable()).thenReturn(bus);
        TransactionStatus estado = new SimpleTransactionStatus();
        when(gestorDeTransacciones.getTransaction(any())).thenReturn(estado);
        scheduler = new AnuncioBusScheduler(productRepository, proveedorDelBus, catalogUseCase, gestorDeTransacciones);
        set("habilitado", true);
        set("maxIntentos", 5);
        // El lote se ejecuta en el hilo de la prueba: así se comprueba el resultado sin esperas ni relojes.
        scheduler.orquestador = Runnable::run;
    }

    // ────────────────────────────────────────────── lo que se publica

    /**
     * Un producto certificado sale con su categoría DELANTE y con la ficha completa.
     *
     * <p>La categoría no es un extra: el entorno de destino puede no haberla visto nunca, y un producto
     * que llega antes que su categoría se queda huérfano y no aparece en el escaparate. Pasó de verdad
     * —producción tenía cero categorías frente a las 1.963 de preproducción— y el importador rechazaba
     * el producto una y otra vez.
     */
    @Test
    void unProductoCertificadoSaleConSuCategoriaDelante() {
        ProductEntity p = pendiente(true);
        CategoryEntity categoria = new CategoryEntity();
        categoria.setSlug("moda-camisetas");
        p.setCategory(categoria);
        cuandoSeBusquenPendientes(p);
        BulkProductDtoIn ficha = new BulkProductDtoIn();
        when(catalogUseCase.exportProduct(p.getId())).thenReturn(ficha);

        assertThat(scheduler.anunciarLote()).isEqualTo(1);

        verify(bus).publicarCategoriaConAncestros(categoria);
        verify(bus).publicarCertificado(ficha);
        verify(productRepository).marcarBusAnunciado(eq(p.getId()), any(Instant.class));
    }

    /**
     * Descertificar también viaja. Si solo se anunciara el alta, un producto retirado aquí seguiría a la
     * venta en el entorno de destino: por eso la marca se pone igual al desmarcar.
     */
    @Test
    void unProductoDescertificadoSeAnunciaComoRetirado() {
        ProductEntity p = pendiente(false);
        cuandoSeBusquenPendientes(p);

        assertThat(scheduler.anunciarLote()).isEqualTo(1);

        verify(bus).publicarRetirado(eq(p), anyString());
        verify(bus, never()).publicarCertificado(any());
        verify(productRepository).marcarBusAnunciado(eq(p.getId()), any(Instant.class));
    }

    /**
     * Qué evento sale se decide por cómo está el producto AHORA, no por cómo estaba al marcarlo. Si el
     * admin certifica y descertifica seguido, al destino tiene que llegar el estado final: publicar lo
     * que se leyó en el barrido dejaría el destino contando lo contrario de lo que el catálogo dice.
     */
    @Test
    void elEventoSaleConElEstadoDelMomentoDePublicar() {
        ProductEntity enLaCola = pendiente(true);
        ProductEntity yaDescertificado = pendiente(false);
        yaDescertificado.setId(enLaCola.getId());
        when(productRepository.findTop50ByBusEstadoOrderByUpdatedAtAsc(BusAnuncioEstado.PENDIENTE))
                .thenReturn(List.of(enLaCola));
        when(productRepository.findById(enLaCola.getId())).thenReturn(Optional.of(yaDescertificado));

        scheduler.anunciarLote();

        verify(bus).publicarRetirado(eq(yaDescertificado), anyString());
        verify(bus, never()).publicarCertificado(any());
    }

    /** Lo borraron entre el barrido y la publicación: no hay nada que contar y tampoco es un fallo. */
    @Test
    void unProductoBorradoEntreMediasNoRevientaElLote() {
        ProductEntity p = pendiente(true);
        when(productRepository.findTop50ByBusEstadoOrderByUpdatedAtAsc(BusAnuncioEstado.PENDIENTE))
                .thenReturn(List.of(p));
        when(productRepository.findById(p.getId())).thenReturn(Optional.empty());

        assertThat(scheduler.anunciarLote()).isEqualTo(1);

        verify(bus, never()).publicarCertificado(any());
        verify(productRepository, never()).anotarFalloDeAnuncio(any(), anyString());
    }

    // ────────────────────────────────────────────── qué pasa cuando falla

    /**
     * El fallo se ANOTA con su motivo. Es lo único que hace visible un producto que no llegó a destino:
     * la petición ya respondió 200 hace rato, así que sin esta anotación el admin se quedaría creyendo
     * que su producto está en producción.
     */
    @Test
    void unFalloDejaElMotivoEscritoYElProductoEnLaCola() {
        ProductEntity p = pendiente(true);
        cuandoSeBusquenPendientes(p);
        when(catalogUseCase.exportProduct(p.getId())).thenThrow(new IllegalStateException("el bus no responde"));

        assertThat(scheduler.anunciarLote()).isZero();

        verify(productRepository).anotarFalloDeAnuncio(eq(p.getId()), anyString());
        // Le quedan intentos: sigue PENDIENTE, no se da por perdido todavía.
        verify(productRepository, never()).darAnuncioPorPerdido(p.getId());
        verify(productRepository, never()).marcarBusAnunciado(any(), any());
    }

    /**
     * Agotados los intentos se da por perdido. No es rendirse: es sacarlo de la cola para que un producto
     * roto no consuma el lote de todos los ciclos y frene a los que sí pueden publicarse. Queda a la
     * vista en el panel con su motivo.
     */
    @Test
    void alAgotarLosIntentosElProductoSeDaPorPerdido() {
        ProductEntity p = pendiente(true);
        p.setBusIntentos(4); // con este intento llega a 5, el tope
        cuandoSeBusquenPendientes(p);
        when(catalogUseCase.exportProduct(p.getId())).thenThrow(new IllegalStateException("categoría inexistente"));

        scheduler.anunciarLote();

        verify(productRepository).darAnuncioPorPerdido(p.getId());
    }

    /** Un producto que falla no puede llevarse por delante a los demás del lote. */
    @Test
    void unProductoQueFallaNoDetieneAlResto() {
        ProductEntity malo = pendiente(true);
        ProductEntity bueno = pendiente(true);
        when(productRepository.findTop50ByBusEstadoOrderByUpdatedAtAsc(BusAnuncioEstado.PENDIENTE))
                .thenReturn(List.of(malo, bueno));
        when(productRepository.findById(malo.getId())).thenReturn(Optional.of(malo));
        when(productRepository.findById(bueno.getId())).thenReturn(Optional.of(bueno));
        when(catalogUseCase.exportProduct(malo.getId())).thenThrow(new IllegalStateException("roto"));
        when(catalogUseCase.exportProduct(bueno.getId())).thenReturn(new BulkProductDtoIn());

        assertThat(scheduler.anunciarLote()).isEqualTo(1);

        verify(productRepository).marcarBusAnunciado(eq(bueno.getId()), any(Instant.class));
        verify(productRepository).anotarFalloDeAnuncio(eq(malo.getId()), anyString());
    }

    /** Si la base tampoco deja anotar el fallo, el barrido aguanta: el producto sigue en la cola. */
    @Test
    void siNiSiquieraSePuedeAnotarElFalloElBarridoSigueEnPie() {
        ProductEntity p = pendiente(true);
        cuandoSeBusquenPendientes(p);
        when(catalogUseCase.exportProduct(p.getId())).thenThrow(new IllegalStateException("roto"));
        doThrow(new IllegalStateException("base caída")).when(productRepository).anotarFalloDeAnuncio(any(),
                anyString());

        assertThat(scheduler.anunciarLote()).isZero();
    }

    /** El motivo se guarda para enseñarlo en el panel: una traza entera no cabe ahí. */
    @Test
    void elMotivoSeRecortaParaQueQuepaEnElPanel() {
        ProductEntity p = pendiente(true);
        cuandoSeBusquenPendientes(p);
        when(catalogUseCase.exportProduct(p.getId())).thenThrow(new IllegalStateException("x".repeat(2000)));

        scheduler.anunciarLote();

        verify(productRepository).anotarFalloDeAnuncio(eq(p.getId()),
                org.mockito.ArgumentMatchers.argThat(m -> m.length() <= 500));
    }

    // ────────────────────────────────────────────── el planificador no se bloquea

    /**
     * El barrido ENCARGA el lote y vuelve; no lo ejecuta en el hilo del planificador.
     *
     * <p>No es un detalle de estilo. Con hilos virtuales {@code spring.task.scheduling.pool.size} se
     * ignora y solo hay UN hilo para todas las tareas programadas: en septiembre de 2026 el espejado de
     * vídeo se quedó cuatro horas en ese hilo y paró la bandeja de salida entera, así que los productos
     * dejaron de llegar a producción por una tarea que no tenía nada que ver.
     */
    @Test
    void elLoteNoSeEjecutaEnElHiloDelPlanificador() {
        AtomicInteger encargos = new AtomicInteger();
        scheduler.orquestador = tarea -> encargos.incrementAndGet(); // se encarga y NO se ejecuta
        cuandoSeBusquenPendientes(pendiente(true));

        scheduler.anunciarPendientes();

        assertThat(encargos.get()).isEqualTo(1);
        verify(productRepository, never()).findTop50ByBusEstadoOrderByUpdatedAtAsc(any());
    }

    /**
     * Dos lotes no se solapan. El barrido salta cada pocos segundos y un lote puede tardar más: sin el
     * candado, cada ciclo apilaría otro lote sobre los mismos productos y el destino recibiría el mismo
     * evento repetido tantas veces como ciclos cupieran.
     */
    @Test
    void nadieEmpiezaUnLoteMientrasElAnteriorSigue() {
        AtomicInteger encargos = new AtomicInteger();
        scheduler.orquestador = tarea -> encargos.incrementAndGet(); // el lote queda «en marcha» para siempre

        scheduler.anunciarPendientes();
        scheduler.anunciarPendientes();

        assertThat(encargos.get()).isEqualTo(1);
    }

    /** Apagado el anuncio, el barrido no toca nada: es la vía para pararlo en un entorno sin bus. */
    @Test
    void conElAnuncioApagadoElBarridoNoHaceNada() throws Exception {
        set("habilitado", false);
        AtomicInteger encargos = new AtomicInteger();
        scheduler.orquestador = tarea -> encargos.incrementAndGet();

        scheduler.anunciarPendientes();

        assertThat(encargos.get()).isZero();
    }

    /** Sin bus configurado no hay nada que anunciar, y tampoco hay que marcar nada como fallido. */
    @Test
    void sinBusConfiguradoNoSeAnunciaNiSeAnotanFallos() {
        when(proveedorDelBus.getIfAvailable()).thenReturn(null);

        assertThat(scheduler.anunciarLote()).isZero();

        verify(productRepository, never()).findTop50ByBusEstadoOrderByUpdatedAtAsc(any());
        verify(productRepository, never()).anotarFalloDeAnuncio(any(), anyString());
    }

    /** Sin pendientes no se consulta el contador: el barrido salta cada pocos segundos todo el día. */
    @Test
    void sinPendientesElBarridoNoConsultaDeMas() {
        when(productRepository.findTop50ByBusEstadoOrderByUpdatedAtAsc(BusAnuncioEstado.PENDIENTE))
                .thenReturn(List.of());

        assertThat(scheduler.anunciarLote()).isZero();

        verify(productRepository, never()).countByBusEstado(any());
    }

    private void cuandoSeBusquenPendientes(ProductEntity p) {
        when(productRepository.findTop50ByBusEstadoOrderByUpdatedAtAsc(BusAnuncioEstado.PENDIENTE))
                .thenReturn(List.of(p));
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
    }

    private static ProductEntity pendiente(boolean certificado) {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        p.setExternalId("1688-" + UUID.randomUUID());
        p.setSlug("un-producto");
        p.setVerified(certificado);
        p.setBusEstado(BusAnuncioEstado.PENDIENTE);
        p.setBusIntentos(0);
        return p;
    }

    private void set(String campo, Object valor) throws Exception {
        Field f = AnuncioBusScheduler.class.getDeclaredField(campo);
        f.setAccessible(true);
        f.set(scheduler, valor);
    }
}
