package com.nexaplatform.dropshipping.application.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Relleno del índice tras un cambio de esquema. Si esto no salta, el escaparate queda con la búsqueda
 * degradada al fallback SQL hasta que alguien se acuerde de pulsar "Sincronizar" en el admin.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SearchIndexBootstrapTest {

    @Mock
    ProductIndexer productIndexer;
    @Mock
    ProductRepository productRepository;
    @Mock
    CatalogReindexRunner reindexRunner;

    @InjectMocks
    SearchIndexBootstrap bootstrap;

    private Logger registro;
    private ListAppender<ILoggingEvent> anotador;

    @BeforeEach
    void escuchaElRegistro() {
        registro = (Logger) LoggerFactory.getLogger(SearchIndexBootstrap.class);
        anotador = new ListAppender<>();
        anotador.start();
        registro.addAppender(anotador);
    }

    @AfterEach
    void dejaDeEscuchar() {
        registro.detachAppender(anotador);
    }

    @Test
    void unIndiceReciencreadoSeRellenaEnSegundoPlano() {
        activar(true);
        when(productIndexer.isFreshlyCreated()).thenReturn(true);
        when(reindexRunner.tryAcquire()).thenReturn(true);

        bootstrap.reindexWhenIndexIsNew();

        verify(reindexRunner).runAsync();
    }

    /** Índice que ya existía = ya tiene datos: reindexar en cada arranque sería un barrido inútil. */
    @Test
    void siElIndiceYaExistiaNoSeReindexa() {
        activar(true);
        when(productIndexer.isFreshlyCreated()).thenReturn(false);

        bootstrap.reindexWhenIndexIsNew();

        verify(reindexRunner, never()).runAsync();
    }

    /** Con un reindexado manual ya en curso no se lanza otro encima. */
    @Test
    void noSeSolapaConUnReindexadoEnCurso() {
        activar(true);
        when(productIndexer.isFreshlyCreated()).thenReturn(true);
        when(reindexRunner.tryAcquire()).thenReturn(false);

        bootstrap.reindexWhenIndexIsNew();

        verify(reindexRunner, never()).runAsync();
    }

    /** El interruptor de configuración manda (los tests y los arranques de emergencia lo apagan). */
    @Test
    void desactivadoPorConfiguracionNoHaceNada() {
        activar(false);
        when(productIndexer.isFreshlyCreated()).thenReturn(true);

        bootstrap.reindexWhenIndexIsNew();

        verify(reindexRunner, never()).runAsync();
    }

    /* ── El índice que se quedó a medias ──────────────────────────────────────────────────────── */

    /**
     * El caso real: en preproducción el índice se quedó con 2.499 de 7.646 productos porque la pasada que
     * lo puebla se cortó, y nadie la reintenta jamás. No hubo ni un error en el registro; el síntoma llegó
     * como «el buscador no funciona», meses después, porque dos de cada tres productos no se encontraban
     * aunque el listado —que no pasa por el índice— sí los enseñaba.
     */
    @Test
    void unIndiceAMediasSeAvisaEnElArranque() {
        activar(true);
        when(productIndexer.isFreshlyCreated()).thenReturn(false);
        when(productIndexer.indexName()).thenReturn("products-v6");
        when(productIndexer.documentCount()).thenReturn(2_499L);
        when(productRepository.count()).thenReturn(7_646L);

        bootstrap.reindexWhenIndexIsNew();

        assertThat(avisos()).anyMatch(m -> m.contains("INCOMPLETO"));
        // Avisa, pero no reconstruye: purgaría el índice en todas las réplicas a la vez.
        verify(reindexRunner, never()).runAsync();
    }

    /** Un índice completo no dice nada: una alarma que salta siempre deja de leerse. */
    @Test
    void unIndiceCompletoNoAvisaDeNada() {
        activar(true);
        when(productIndexer.isFreshlyCreated()).thenReturn(false);
        when(productIndexer.documentCount()).thenReturn(7_640L);
        when(productRepository.count()).thenReturn(7_646L);

        bootstrap.reindexWhenIndexIsNew();

        assertThat(avisos()).isEmpty();
    }

    /**
     * Con el buscador caído no se cuenta como índice vacío. Si contara, cada arranque con OpenSearch
     * inaccesible gritaría que el índice está incompleto — justo cuando el aviso no ayuda a nadie.
     */
    @Test
    void conElBuscadorCaidoNoSeAvisaDeNada() {
        activar(true);
        when(productIndexer.isFreshlyCreated()).thenReturn(false);
        when(productIndexer.documentCount()).thenReturn(-1L);

        bootstrap.reindexWhenIndexIsNew();

        assertThat(avisos()).isEmpty();
        verify(productRepository, never()).count();
    }

    /** Un catálogo vacío tampoco: cero de cero está completo, y avisar sería ruido en cada entorno nuevo. */
    @Test
    void unCatalogoVacioNoAvisaDeNada() {
        activar(true);
        when(productIndexer.isFreshlyCreated()).thenReturn(false);
        when(productIndexer.documentCount()).thenReturn(0L);
        when(productRepository.count()).thenReturn(0L);

        bootstrap.reindexWhenIndexIsNew();

        assertThat(avisos()).isEmpty();
    }

    private List<String> avisos() {
        return anotador.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void activar(boolean valor) {
        ReflectionTestUtils.setField(bootstrap, "autoReindex", valor);
    }
}
