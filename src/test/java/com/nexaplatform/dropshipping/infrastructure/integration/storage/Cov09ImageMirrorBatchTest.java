package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.VariantValueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Espejado de imágenes: pases en background (import y reindex) y barrido de variantes/valores.
 *
 * <p>Todos los orígenes de estos tests apuntan a {@code 127.0.0.1}, así que el guard anti-SSRF los
 * rechaza ANTES de abrir ninguna conexión: la descarga real nunca ocurre y el test no depende de la red.
 * Lo que se fija aquí es qué pasa cuando una imagen no se puede espejar — que se marque el fallo y que
 * el resto del lote siga— y que los pases en background respeten el interruptor y no se disparen en vacío.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov09ImageMirrorBatchTest {

    @Mock
    ProductImageRepository imageRepository;
    @Mock
    ProductVariantRepository variantRepository;
    @Mock
    VariantValueRepository variantValueRepository;
    @Mock
    ObjectStorageService storage;
    @Mock
    ProductIndexer productIndexer;
    @InjectMocks
    ImageMirrorService service;

    /** URL sintácticamente válida pero interna: el guard anti-SSRF la rechaza sin salir a la red. */
    private static final String ORIGEN_INALCANZABLE = "http://127.0.0.1/foto.jpg";

    private void setField(String name, Object value) throws Exception {
        Field f = ImageMirrorService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private static ProductImageEntity image(UUID id, String sourceUrl) {
        ProductImageEntity img = ProductImageEntity.builder().sourceUrl(sourceUrl)
                .mirrorStatus(MirrorStatus.PENDING).build();
        img.setId(id); // el id lo hereda de BaseEntity, no lo cubre el @Builder
        return img;
    }

    // ---------------------------------------------------------- pase al importar productos

    @Test
    void elPaseAlImportarNoSeEjecutaConElEspejadoApagado() throws Exception {
        setField("mirrorEnabled", false);

        service.mirrorProductsAsync(List.of(UUID.randomUUID()));

        verifyNoInteractions(imageRepository, productIndexer);
    }

    @Test
    void elPaseAlImportarSinProductosNoConsultaNada() throws Exception {
        setField("mirrorEnabled", true);

        service.mirrorProductsAsync(List.of());
        service.mirrorProductsAsync(null);

        verifyNoInteractions(imageRepository, productIndexer);
    }

    @Test
    void elPaseAlImportarSinImagenesPendientesNoReindexaNada() throws Exception {
        // Reindexar sin haber espejado nada solo genera trabajo inútil en OpenSearch.
        setField("mirrorEnabled", true);
        List<UUID> productos = List.of(UUID.randomUUID());
        when(imageRepository.findByProductIdInAndMirrorStatus(productos, MirrorStatus.PENDING))
                .thenReturn(List.of());

        service.mirrorProductsAsync(productos);

        verify(imageRepository, never()).findProductIdsByImageIds(anyList());
        verifyNoInteractions(productIndexer);
    }

    @Test
    void unaImagenQueNoSePuedeDescargarAlImportarQuedaFallidaYNoSeReindexa() throws Exception {
        setField("mirrorEnabled", true);
        List<UUID> productos = List.of(UUID.randomUUID());
        UUID imagenId = UUID.randomUUID();
        when(imageRepository.findByProductIdInAndMirrorStatus(productos, MirrorStatus.PENDING))
                .thenReturn(List.of(image(imagenId, ORIGEN_INALCANZABLE)));

        service.mirrorProductsAsync(productos);

        verify(imageRepository).markStatus(imagenId, MirrorStatus.FAILED);
        verify(imageRepository, never()).markMirrored(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(productIndexer);
    }

    // ---------------------------------------------------------- pase completo tras reindexar

    @Test
    void elPaseCompletoNoSeEjecutaConElEspejadoApagado() throws Exception {
        setField("mirrorEnabled", false);

        service.mirrorAllPendingAsync();

        verify(imageRepository, never()).countByMirrorStatus(any());
    }

    @Test
    void elPaseCompletoSeDetieneEnCuantoNoQuedanPendientes() throws Exception {
        // Sin esta condición de parada el pase daría sus 500 vueltas en vacío en cada reindex del admin.
        setField("mirrorEnabled", true);
        setField("mirrorBatch", 50);
        when(imageRepository.countByMirrorStatus(MirrorStatus.PENDING)).thenReturn(1L, 0L);
        when(imageRepository.findTop100ByMirrorStatusOrderByCreatedAtDesc(MirrorStatus.PENDING))
                .thenReturn(List.of());

        service.mirrorAllPendingAsync();

        verify(imageRepository, times(1)).findTop100ByMirrorStatusOrderByCreatedAtDesc(MirrorStatus.PENDING);
    }

    @Test
    void elPaseCompletoConCeroPendientesNiSiquieraPideUnLote() throws Exception {
        setField("mirrorEnabled", true);
        when(imageRepository.countByMirrorStatus(MirrorStatus.PENDING)).thenReturn(0L);

        service.mirrorAllPendingAsync();

        verify(imageRepository, never()).findTop100ByMirrorStatusOrderByCreatedAtDesc(any());
    }

    // ---------------------------------------------------------- variantes y valores de eje

    @Test
    void unaFotoDeVarianteQueNoSePuedeDescargarSeMarcaFallidaYNoCortaElLote() {
        // El barrido no puede pararse en la primera foto muerta: el resto de colores se quedaría sin espejar.
        when(storage.isReady()).thenReturn(true);
        when(storage.publicUrl()).thenReturn("https://cdn.example.com/");
        UUID varianteA = UUID.randomUUID();
        UUID varianteB = UUID.randomUUID();
        ProductVariantEntity a = ProductVariantEntity.builder().imageSourceUrl(ORIGEN_INALCANZABLE).build();
        a.setId(varianteA);
        ProductVariantEntity b = ProductVariantEntity.builder().imageSourceUrl(ORIGEN_INALCANZABLE).build();
        b.setId(varianteB);
        when(variantRepository.findNeedingImageMirror(eq("https://cdn.example.com%"), any()))
                .thenReturn(List.of(a, b));
        UUID valorId = UUID.randomUUID();
        VariantValueEntity vv = VariantValueEntity.builder().imageSourceUrl(ORIGEN_INALCANZABLE).build();
        vv.setId(valorId);
        when(variantValueRepository.findNeedingImageMirror(eq("https://cdn.example.com%"), any()))
                .thenReturn(List.of(vv));

        service.mirrorVariantImagesBatch(10);

        verify(variantRepository).markImageFailed(eq(varianteA), any(Instant.class));
        verify(variantRepository).markImageFailed(eq(varianteB), any(Instant.class));
        // El fallo de las variantes no impide seguir con los valores de eje (la foto de cada color).
        verify(variantValueRepository).markImageFailed(eq(valorId), any(Instant.class));
        verify(variantRepository, never()).markImageCdn(any(), any());
        verify(variantValueRepository, never()).markImageCdn(any(), any());
    }

    @Test
    void unOrigenNuloDeVarianteNoRompeElBarrido() {
        // Una variante sin URL de origen debe caer en el mismo camino de fallo, no reventar el job.
        when(storage.isReady()).thenReturn(true);
        when(storage.publicUrl()).thenReturn("https://cdn.example.com");
        UUID varianteId = UUID.randomUUID();
        ProductVariantEntity v = ProductVariantEntity.builder().imageSourceUrl(null).build();
        v.setId(varianteId);
        when(variantRepository.findNeedingImageMirror(any(), any())).thenReturn(List.of(v));
        when(variantValueRepository.findNeedingImageMirror(any(), any())).thenReturn(List.of());

        assertThatCode(() -> service.mirrorVariantImagesBatch(5)).doesNotThrowAnyException();

        verify(variantRepository).markImageFailed(eq(varianteId), any(Instant.class));
    }

    // ---------------------------------------------------------- gating y saneo del job programado

    @Test
    void conElStorageCaidoElJobNoTocaLaBaseDeDatos() throws Exception {
        setField("mirrorEnabled", true);
        when(storage.isReady()).thenReturn(false);

        service.mirrorPendingScheduled();

        verifyNoInteractions(imageRepository, variantRepository, variantValueRepository);
    }

    @Test
    void siElSaneoInicialFallaElJobSigueAdelanteYNoLoRepiteEnElSiguienteCiclo() throws Exception {
        // El auto-heal es oportunista: que falle no puede dejar el espejado parado ni repetirse en bucle.
        setField("mirrorEnabled", true);
        setField("mirrorBatch", 50);
        when(storage.isReady()).thenReturn(true);
        when(storage.publicUrl()).thenReturn("https://cdn.example.com");
        when(storage.listKeys()).thenReturn(Set.of());
        when(imageRepository.requeueNotMirrored(any())).thenThrow(new IllegalStateException("BD caída"));
        when(imageRepository.findTop100ByMirrorStatusOrderByCreatedAtDesc(MirrorStatus.PENDING))
                .thenReturn(List.of());
        when(variantRepository.findNeedingImageMirror(any(), any())).thenReturn(List.of());
        when(variantValueRepository.findNeedingImageMirror(any(), any())).thenReturn(List.of());

        service.mirrorPendingScheduled();
        service.mirrorPendingScheduled();

        verify(imageRepository, times(1)).requeueNotMirrored(any());
        verify(imageRepository, times(2)).findTop100ByMirrorStatusOrderByCreatedAtDesc(MirrorStatus.PENDING);
    }
}
