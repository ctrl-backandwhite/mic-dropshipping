package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Espejado del vídeo de la ficha a nuestro almacenamiento.
 *
 * <p>Por qué existe: hasta el 3-sep-2026 el vídeo NO se espejaba. La ficha llevaba la dirección de
 * {@code cloud.video.taobao.com} tal cual y era el navegador del comprador quien iba a pedírsela a
 * Alibaba. Se comprobó sobre el catálogo real: de 3.686 vídeos, 3.682 seguían vivos y 4 los había
 * tumbado su moderación —{@code 视频审核不通过}— sin que nadie por aquí se enterara. Ese es exactamente
 * el fallo que este servicio evita: que la ficha dependa de que un tercero mantenga el fichero.
 *
 * <p>Las reglas que se fijan aquí son las que costó descubrir midiendo, no detalles de implementación.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VideoMirrorServiceTest {

    @Mock
    ProductRepository productRepository;
    @Mock
    ObjectStorageService storage;

    @InjectMocks
    VideoMirrorService service;

    private static final Instant AHORA = Instant.parse("2026-09-03T12:00:00Z");

    @BeforeEach
    void habilitarElEspejado() throws Exception {
        set("mirrorEnabled", true);
        set("retryBaseMinutes", 15);
        set("retryMaxAttempts", 5);
        set("retryBatch", 50);
        when(storage.isReady()).thenReturn(true);
    }

    // ────────────────────────────────────────────── qué se acepta como vídeo

    /**
     * Un MP4 se reconoce por sus bytes, no por lo que diga el origen en la cabecera. Es la misma razón
     * por la que las imágenes miran sus magic bytes: lo que entra al bucket se sirve luego desde nuestro
     * dominio, y ahí no puede colarse un HTML porque el servidor de enfrente mienta.
     */
    @Test
    void unMp4DeVerdadSeReconocePorSusBytes() {
        byte[] mp4 = new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};

        assertThat(VideoMirrorService.esMp4(mp4)).isTrue();
    }

    @Test
    void unHtmlDisfrazadoDeVideoSeRechaza() {
        byte[] html = "<!DOCTYPE html><html><body>error</body></html>".getBytes(StandardCharsets.UTF_8);

        assertThat(VideoMirrorService.esMp4(html)).isFalse();
    }

    @Test
    void unJsonDeErrorNoEsUnVideo() {
        // Es lo que devuelve Alibaba cuando su moderación tumba un vídeo: {"code":1003,...}. Sin esta
        // comprobación se guardarían 38 bytes de JSON como si fueran el vídeo del producto.
        byte[] json = "{\"code\":1003,\"message\":\"视频审核不通过\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(VideoMirrorService.esMp4(json)).isFalse();
    }

    @Test
    void unContenidoDemasiadoCortoNoRevientaLaComprobacion() {
        assertThat(VideoMirrorService.esMp4(new byte[] {0, 0, 0})).isFalse();
        assertThat(VideoMirrorService.esMp4(new byte[0])).isFalse();
    }

    /**
     * Alibaba responde 490 «acceso ilegal» a quien no se identifica como navegador. No es una
     * preferencia: con la cabecera que usa el espejado de imágenes NO se puede traer ni un vídeo. Se
     * comprobó contra el origen real, y las mismas direcciones que fallaban devolvían el vídeo al
     * mandar una de navegador.
     */
    @Test
    void seIdentificaComoNavegadorPorqueSinEsoElOrigenNoSirveElVideo() {
        assertThat(VideoMirrorService.NAVEGADOR).contains("Mozilla/5.0").contains("Chrome");
    }

    // ────────────────────────────────────────────── reintentos

    @Test
    void reencolaSoloLosQueYaCumplieronSuEspera() {
        // base 15 min: el primer intento espera 15, el segundo 30, el tercero 60...
        ProductEntity listo = fallido(1, AHORA.minus(Duration.ofMinutes(45)));
        ProductEntity aunNo = fallido(1, AHORA.minus(Duration.ofMinutes(5)));
        when(productRepository.findVideosFailedForRetry(anyInt(), anyInt())).thenReturn(List.of(listo, aunNo));

        service.requeueFailedForRetry(AHORA);

        verify(productRepository).requeueVideos(List.of(listo.getId()));
    }

    @Test
    void laEsperaSeDuplicaConCadaIntentoFallido() {
        // Con 3 intentos a la espalda toca esperar 15 x 2^3 = 120 minutos. A los 90 todavía no.
        ProductEntity tresIntentos = fallido(3, AHORA.minus(Duration.ofMinutes(90)));
        when(productRepository.findVideosFailedForRetry(anyInt(), anyInt())).thenReturn(List.of(tresIntentos));

        service.requeueFailedForRetry(AHORA);

        verify(productRepository, never()).requeueVideos(anyList());
    }

    @Test
    void alPasarLaEsperaLargaSiSeReencola() {
        ProductEntity tresIntentos = fallido(3, AHORA.minus(Duration.ofMinutes(121)));
        when(productRepository.findVideosFailedForRetry(anyInt(), anyInt())).thenReturn(List.of(tresIntentos));

        service.requeueFailedForRetry(AHORA);

        verify(productRepository).requeueVideos(List.of(tresIntentos.getId()));
    }

    /**
     * Un vídeo sin intentos previos —la columna llega nula en lo que existía antes de este mecanismo—
     * no puede hacer estallar el cálculo de la espera.
     */
    @Test
    void unVideoSinContadorDeIntentosSeTrataComoCero() {
        ProductEntity sinContador = fallido(1, AHORA.minus(Duration.ofMinutes(60)));
        sinContador.setVideoMirrorAttempts(null);
        when(productRepository.findVideosFailedForRetry(anyInt(), anyInt())).thenReturn(List.of(sinContador));

        service.requeueFailedForRetry(AHORA);

        verify(productRepository).requeueVideos(List.of(sinContador.getId()));
    }

    @Test
    void noPideLosQueYaAgotaronSusIntentos() {
        when(productRepository.findVideosFailedForRetry(anyInt(), anyInt())).thenReturn(List.of());

        service.requeueFailedForRetry(AHORA);

        verify(productRepository).findVideosFailedForRetry(5, 50);
    }

    @Test
    void sinCandidatosNoLlamaAlReencolado() {
        when(productRepository.findVideosFailedForRetry(anyInt(), anyInt())).thenReturn(List.of());

        service.requeueFailedForRetry(AHORA);

        verify(productRepository, never()).requeueVideos(anyList());
    }

    // ────────────────────────────────────────────── interruptores

    /** Con el espejado apagado no se toca la base: el interruptor manda sobre todo lo demás. */
    @Test
    void conElEspejadoApagadoNoHaceNada() throws Exception {
        set("mirrorEnabled", false);

        service.requeueFailedForRetry(AHORA);
        service.mirrorPendingScheduled();

        verify(productRepository, never()).findVideosFailedForRetry(anyInt(), anyInt());
        verify(productRepository, never()).requeueVideos(anyList());
    }

    /** Sin almacenamiento listo tampoco: subir a un bucket que no está solo dejaría fallos falsos. */
    @Test
    void sinAlmacenamientoListoNoIntentaEspejar() {
        when(storage.isReady()).thenReturn(false);

        service.requeueFailedForRetry(AHORA);
        service.mirrorPendingScheduled();

        verify(productRepository, never()).findVideosFailedForRetry(anyInt(), anyInt());
    }

    /** Una lista vacía de productos no puede acabar en una consulta a la base. */
    @Test
    void sinProductosNoConsultaNada() {
        service.mirrorProductsAsync(List.of());
        service.mirrorProductsAsync(null);

        verify(productRepository, never()).findByIdInAndVideoMirrorStatus(anyList(), org.mockito.ArgumentMatchers
                .any(MirrorStatus.class));
    }

    // ────────────────────────────────────────────── la cola al guardar el producto

    /**
     * Cambiar la dirección del vídeo lo pone en cola y tira lo que se hubiera espejado del anterior:
     * pertenece a OTRO vídeo, y servirlo sería enseñar en la ficha algo que ya no es de este producto.
     */
    @Test
    void cambiarElVideoLoPoneEnColaYDescartaLoEspejadoDelAnterior() {
        ProductEntity p = new ProductEntity();
        p.setVideoUrl("https://cloud.video.taobao.com/play/u/1/viejo.mp4");
        p.setVideoCdnUrl("https://img.nx036.com/video/aa/aa.mp4");
        p.setVideoHash("aa");
        p.setVideoBytes(1000L);
        p.setVideoMirroredAt(AHORA);
        p.setVideoMirrorStatus(MirrorStatus.MIRRORED);
        p.setVideoMirrorAttempts(4);

        p.cambiarVideoUrl("https://cloud.video.taobao.com/play/u/1/nuevo.mp4");

        assertThat(p.getVideoUrl()).endsWith("nuevo.mp4");
        assertThat(p.getVideoCdnUrl()).isNull();
        assertThat(p.getVideoHash()).isNull();
        assertThat(p.getVideoBytes()).isNull();
        assertThat(p.getVideoMirroredAt()).isNull();
        assertThat(p.getVideoMirrorStatus()).isEqualTo(MirrorStatus.PENDING);
        // El contador vuelve a cero: la dirección nueva merece sus oportunidades aunque la vieja las
        // hubiera agotado.
        assertThat(p.getVideoMirrorAttempts()).isZero();
    }

    /**
     * Guardar el producto sin tocar el vídeo NO lo reencola. Si lo hiciera, cada edición de precio o de
     * título volvería a descargarse los 3.674 vídeos del catálogo.
     */
    @Test
    void guardarConLaMismaDireccionNoReencolaNada() {
        ProductEntity p = new ProductEntity();
        p.setVideoUrl("https://cloud.video.taobao.com/play/u/1/mismo.mp4");
        p.setVideoCdnUrl("https://img.nx036.com/video/aa/aa.mp4");
        p.setVideoMirrorStatus(MirrorStatus.MIRRORED);

        p.cambiarVideoUrl("https://cloud.video.taobao.com/play/u/1/mismo.mp4");

        assertThat(p.getVideoCdnUrl()).isEqualTo("https://img.nx036.com/video/aa/aa.mp4");
        assertThat(p.getVideoMirrorStatus()).isEqualTo(MirrorStatus.MIRRORED);
    }

    /** Quitar el vídeo desde el panel lo saca de la cola: no hay nada que espejar. */
    @Test
    void quitarElVideoLoSacaDeLaCola() {
        ProductEntity p = new ProductEntity();
        p.setVideoUrl("https://cloud.video.taobao.com/play/u/1/viejo.mp4");
        p.setVideoMirrorStatus(MirrorStatus.MIRRORED);
        p.setVideoCdnUrl("https://img.nx036.com/video/aa/aa.mp4");

        p.cambiarVideoUrl(null);

        assertThat(p.getVideoUrl()).isNull();
        assertThat(p.getVideoMirrorStatus()).isNull();
        assertThat(p.getVideoCdnUrl()).isNull();
    }

    private static ProductEntity fallido(int intentos, Instant ultimoIntento) {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        p.setVideoMirrorStatus(MirrorStatus.FAILED);
        p.setVideoMirrorAttempts(intentos);
        p.setUpdatedAt(ultimoIntento);
        return p;
    }

    private void set(String campo, Object valor) throws Exception {
        Field f = VideoMirrorService.class.getDeclaredField(campo);
        f.setAccessible(true);
        f.set(service, valor);
    }
}
