package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

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
    CatalogReindexRunner reindexRunner;

    @InjectMocks
    SearchIndexBootstrap bootstrap;

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

    private void activar(boolean valor) {
        ReflectionTestUtils.setField(bootstrap, "autoReindex", valor);
    }
}
