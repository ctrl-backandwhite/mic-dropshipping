package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.VariantValueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Flujo de negocio mockeable del mirror de imágenes. El guard anti-SSRF y la verificación de magic
 * bytes ({@link ImageMirrorService#assertPublicHttpUrl}/{@link ImageMirrorService#sniffRasterImage})
 * los cubre {@code ImageMirrorSecurityTest}; aquí se prueba el resto: gating por
 * enabled/isReady, auto-heal de cdn_url no vigentes + objetos inexistentes, marcado de FAILED y
 * selección de URL de origen. La descarga HTTP usa un {@code HttpClient} interno NO inyectable, así
 * que las ramas de subida correcta a S3 quedan fuera (no se pueden estimular sin red real).
 */
@ExtendWith(MockitoExtension.class)
class ImageMirrorServiceTest {

    @Mock
    ProductImageRepository imageRepository;
    @Mock
    ProductVariantRepository variantRepository;
    @Mock
    VariantValueRepository variantValueRepository;
    @Mock
    ObjectStorageService storage;
    @Mock
    com.nexaplatform.dropshipping.infrastructure.persistence.repository.ImagenOrigenEspejadaRepository origenesEspejados;
    /**
     * El limitador va de verdad, no simulado: es una clase sin dependencias y lo que hace —dar turno y
     * devolverlo— tiene que ocurrir para que la descarga se intente. Un simulacro devolvería nulo y la
     * prueba mediría otra cosa.
     */
    @org.mockito.Spy
    LimitadorDeDescargasPorOrigen limitador = new LimitadorDeDescargasPorOrigen(6, 120);
    @InjectMocks
    ImageMirrorService service;

    /**
     * El barrido encarga el lote a otro hilo para no dejar bloqueado al planificador —que es uno solo
     * para las 18 tareas programadas—. En las pruebas se sustituye por uno que ejecuta en el hilo que
     * llama, y así se comprueba el resultado del lote sin esperas ni relojes.
     */
    @BeforeEach
    void ejecutarElLoteEnEsteMismoHilo() {
        service.orquestador = Runnable::run;
    }

    private static ProductImageEntity image(UUID id, String sourceUrl) {
        ProductImageEntity img = ProductImageEntity.builder()
                .sourceUrl(sourceUrl)
                .mirrorStatus(MirrorStatus.PENDING)
                .build();
        img.setId(id); // id heredado de BaseEntity, no lo cubre el @Builder
        return img;
    }

    // ---- Gating del job programado ----

    @Test
    void scheduled_doesNothing_whenMirrorDisabled() {
        // mirrorEnabled por defecto true vía @Value, pero sin Spring el campo queda false → corto-circuito.
        service.mirrorPendingScheduled();
        verifyNoInteractions(imageRepository, variantRepository, variantValueRepository);
        verify(storage, never()).publicUrl();
    }

    @Test
    void variantBatch_returnsEarly_whenStorageNotReady() {
        when(storage.isReady()).thenReturn(false);

        service.mirrorVariantImagesBatch(10);

        verifyNoInteractions(variantRepository, variantValueRepository);
        verify(storage, never()).publicUrl();
    }

    // ---- Lote de imágenes PENDING ----

    @Test
    void pendingBatch_returnsZero_whenNothingPending() {
        when(imageRepository.findTop100ByMirrorStatusOrderByCreatedAtDesc(MirrorStatus.PENDING))
                .thenReturn(List.of());

        int mirrored = service.mirrorPendingBatch(50);

        assertThat(mirrored).isZero();
        verify(imageRepository, never()).markMirrored(any(), any(), any(), any(), any(), any(), any(), any());
        verify(imageRepository, never()).markFailedAndCountAttempt(any());
    }

    @Test
    void pendingBatch_marksFailed_whenSourceUrlIsBlank() {
        UUID id = UUID.randomUUID();
        when(imageRepository.findTop100ByMirrorStatusOrderByCreatedAtDesc(MirrorStatus.PENDING))
                .thenReturn(List.of(image(id, "   ")));
        lenient().when(imageRepository.countByMirrorStatus(MirrorStatus.PENDING)).thenReturn(0L);

        int mirrored = service.mirrorPendingBatch(50);

        // Sin URL de origen no se descarga nada: directo a FAILED, 0 espejadas.
        assertThat(mirrored).isZero();
        // Desde el 26-ago-2026 el fallo no solo marca estado: suma un intento, que es lo que espacia
        // el siguiente y evita repetir la avalancha que dejó 415 productos fuera del escaparate.
        verify(imageRepository).markFailedAndCountAttempt(id);
        verify(imageRepository, never()).markMirrored(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void pendingBatch_marksFailed_whenSourceUrlIsNull() {
        UUID id = UUID.randomUUID();
        when(imageRepository.findTop100ByMirrorStatusOrderByCreatedAtDesc(MirrorStatus.PENDING))
                .thenReturn(List.of(image(id, null)));
        lenient().when(imageRepository.countByMirrorStatus(MirrorStatus.PENDING)).thenReturn(0L);

        service.mirrorPendingBatch(50);

        // Desde el 26-ago-2026 el fallo no solo marca estado: suma un intento, que es lo que espacia
        // el siguiente y evita repetir la avalancha que dejó 415 productos fuera del escaparate.
        verify(imageRepository).markFailedAndCountAttempt(id);
        verify(imageRepository, never()).markMirrored(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void pendingBatch_respectsLimit_processingOnlyTheFirstN() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        when(imageRepository.findTop100ByMirrorStatusOrderByCreatedAtDesc(MirrorStatus.PENDING))
                .thenReturn(List.of(image(id1, null), image(id2, null), image(id3, null)));
        lenient().when(imageRepository.countByMirrorStatus(MirrorStatus.PENDING)).thenReturn(3L);

        service.mirrorPendingBatch(2);

        // Solo las dos primeras (limit=2) se procesan → solo dos FAILED; la tercera ni se toca.
        verify(imageRepository).markFailedAndCountAttempt(id1);
        verify(imageRepository).markFailedAndCountAttempt(id2);
        verify(imageRepository, never()).markFailedAndCountAttempt(eq(id3));
    }

    // ---- Auto-heal (una vez por arranque) dentro del job programado ----

    @Test
    void scheduled_runsAutoHeal_reenqueuesStaleAndMissingObjects() throws Exception {
        // Habilita el job (campos @Value no inicializados por Spring en el test unitario).
        setField("mirrorEnabled", true);
        setField("mirrorBatch", 50);

        when(storage.isReady()).thenReturn(true);
        when(storage.publicUrl()).thenReturn("https://cdn.example.com");
        when(imageRepository.requeueNotMirrored("https://cdn.example.com%")).thenReturn(2);

        // El objeto media/ab/<hash>.jpg sigue existiendo; el otro ya no → se reencola.
        when(storage.listKeys()).thenReturn(Set.of("media/ab/keep.jpg"));
        UUID present = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        ProductImageEntity kept = image(present, "https://o/keep.jpg");
        kept.setCdnUrl("https://cdn.example.com/media/ab/keep.jpg");
        kept.setMirrorStatus(MirrorStatus.MIRRORED);
        ProductImageEntity gone = image(missing, "https://o/gone.jpg");
        gone.setCdnUrl("https://cdn.example.com/media/cd/gone.jpg");
        gone.setMirrorStatus(MirrorStatus.MIRRORED);
        when(imageRepository.findByMirrorStatusAndCdnUrlStartingWith(
                MirrorStatus.MIRRORED, "https://cdn.example.com/"))
                .thenReturn(List.of(kept, gone));

        // No hay PENDING tras el heal: el resto del lote es no-op controlable.
        when(imageRepository.findTop100ByMirrorStatusOrderByCreatedAtDesc(MirrorStatus.PENDING))
                .thenReturn(List.of());
        // El barrido de variantes/valores no aporta nada (listas vacías).
        when(variantRepository.findNeedingImageMirror(any(), any())).thenReturn(List.of());
        when(variantValueRepository.findNeedingImageMirror(any(), any())).thenReturn(List.of());

        service.mirrorPendingScheduled();

        verify(imageRepository).requeueNotMirrored("https://cdn.example.com%");
        // Solo el objeto inexistente se reencola a PENDING; el presente se respeta.
        verify(imageRepository).markStatus(missing, MirrorStatus.PENDING);
        verify(imageRepository, never()).markStatus(eq(present), any());
    }

    @Test
    void scheduled_autoHealRunsOnlyOnce_acrossInvocations() throws Exception {
        setField("mirrorEnabled", true);
        setField("mirrorBatch", 50);

        when(storage.isReady()).thenReturn(true);
        when(storage.publicUrl()).thenReturn("https://cdn.example.com");
        when(imageRepository.requeueNotMirrored(any())).thenReturn(0);
        when(storage.listKeys()).thenReturn(Set.of()); // vacío → se salta la verificación de objetos
        when(imageRepository.findTop100ByMirrorStatusOrderByCreatedAtDesc(MirrorStatus.PENDING))
                .thenReturn(List.of());
        when(variantRepository.findNeedingImageMirror(any(), any())).thenReturn(List.of());
        when(variantValueRepository.findNeedingImageMirror(any(), any())).thenReturn(List.of());

        service.mirrorPendingScheduled();
        service.mirrorPendingScheduled();

        // El heal (requeueNotMirrored) corre una sola vez pese a dos ejecuciones del job.
        verify(imageRepository, times(1)).requeueNotMirrored(any());
    }

    private void setField(String name, Object value) throws Exception {
        java.lang.reflect.Field f = ImageMirrorService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }
}
