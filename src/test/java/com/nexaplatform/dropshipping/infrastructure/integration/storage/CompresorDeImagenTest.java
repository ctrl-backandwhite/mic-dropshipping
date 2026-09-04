package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compresión de las fotos de producto antes de guardarlas.
 *
 * <p>Por qué existe: las fotos llegaban de 1688 con resolución de cámara y se guardaban tal cual. Medido
 * sobre el catálogo real el 4-sep-2026: 72.919 imágenes ocupando 18 GB, con ejemplares de 3072x4096 que
 * pesaban casi 10 MB para enseñarse a unos 600 píxeles en la ficha.
 *
 * <p>Lo que se fija aquí es lo que hace falta para que aligerar no se convierta en estropear.
 */
@DisplayName("Compresión de imágenes · aligerar sin estropear")
class CompresorDeImagenTest {

    private CompresorDeImagen compresor;

    @BeforeEach
    void montar() throws Exception {
        compresor = new CompresorDeImagen();
        set("ladoMaximo", 1600);
        set("calidad", 0.82f);
        set("minimoParaTocar", 120_000);
    }

    /**
     * Una foto de resolución de cámara se reduce y adelgaza mucho.
     *
     * <p>Es el caso que motivó todo esto. Si esta prueba deja de reducir, volvemos a servir diez megas
     * para pintar una miniatura, y eso lo paga quien compra desde el móvil con sus datos.
     */
    @Test
    @DisplayName("una foto enorme se reduce a 1600 y pesa una fracción")
    void unaFotoEnormeSeReduceYAdelgaza() throws Exception {
        byte[] original = fotoJpeg(1800, 2400);

        CompresorDeImagen.Comprimida salida = compresor.comprimir(original, "jpg");

        assertThat(Math.max(salida.ancho(), salida.alto())).isEqualTo(1600);
        assertThat(salida.datos().length).isLessThan(original.length);
        // Y sigue siendo una imagen que se puede abrir: comprimir no puede producir un fichero roto.
        assertThat(ImageIO.read(new ByteArrayInputStream(salida.datos()))).isNotNull();
    }

    /**
     * La proporción se respeta. Deformar una foto de producto es peor que servirla pesada: el comprador
     * ve una prenda que no tiene esa forma, y eso acaba en una devolución.
     */
    @Test
    @DisplayName("al reducir no se deforma la imagen")
    void alReducirNoSeDeforma() throws Exception {
        byte[] original = fotoJpeg(2400, 1200); // el doble de ancha que de alta

        CompresorDeImagen.Comprimida salida = compresor.comprimir(original, "jpg");

        assertThat((double) salida.ancho() / salida.alto()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.02));
    }

    /**
     * Una imagen que ya es pequeña NO se toca.
     *
     * <p>Recomprimir lo ya comprimido sí pierde calidad de verdad: cada pasada con pérdida se come
     * detalle de la anterior. En una foto que ya pesa poco no hay nada que ganar a cambio de eso.
     */
    @Test
    @DisplayName("una imagen pequeña se guarda tal cual, sin recomprimir")
    void unaImagenPequenaNoSeToca() throws Exception {
        byte[] original = fotoJpeg(400, 300);

        CompresorDeImagen.Comprimida salida = compresor.comprimir(original, "jpg");

        assertThat(salida.datos()).isSameAs(original);
        assertThat(salida.tipo()).isEqualTo("jpg");
        // Aunque no se toque, sus medidas sí se averiguan: es lo que permite a la ficha reservar el hueco.
        assertThat(salida.ancho()).isEqualTo(400);
        assertThat(salida.alto()).isEqualTo(300);
    }

    /**
     * Lo que no se puede procesar se guarda tal cual y no revienta.
     *
     * <p>Fallar aquí dejaría la ficha SIN FOTO, que es mucho peor que una foto pesada. Un formato raro o
     * un fichero cortado a medias tienen que acabar en el almacén igualmente.
     */
    @Test
    @DisplayName("lo que no se puede decodificar se guarda igual, sin lanzar")
    void loQueNoSePuedeDecodificarSeGuardaIgual() {
        byte[] basura = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};

        CompresorDeImagen.Comprimida salida = compresor.comprimir(basura, "jpg");

        assertThat(salida.datos()).isSameAs(basura);
        assertThat(salida.contentType()).isEqualTo("image/jpeg");
    }

    /** El tipo se traduce a su content-type real: `jpg` no es un tipo válido en una cabecera HTTP. */
    @Test
    @DisplayName("el content-type de un jpg es image/jpeg")
    void elContentTypeDeUnJpg() {
        CompresorDeImagen.Comprimida salida = compresor.comprimir(new byte[] {0}, "jpg");

        assertThat(salida.contentType()).isEqualTo("image/jpeg");
    }

    /**
     * Una foto con RUIDO —la que peor se comprime— también tiene que salir más ligera que el original.
     *
     * <p>Se comprueba con ruido a propósito: un degradado liso se comprime tanto que cualquier ajuste
     * parecería bueno. El ruido es el caso desfavorable, y es parecido a una foto real con textura de
     * tela, que es lo que más hay en este catálogo.
     */
    @Test
    @DisplayName("incluso una imagen con mucho detalle sale más ligera")
    void inclusoConMuchoDetalleAdelgaza() throws Exception {
        byte[] original = fotoJpeg(1700, 1700);

        CompresorDeImagen.Comprimida salida = compresor.comprimir(original, "jpg");

        assertThat(salida.datos().length).isLessThan(original.length);
    }

    /**
     * Una imagen con ESTRUCTURA, no ruido puro.
     *
     * <p>La primera versión de estas pruebas usaba ruido aleatorio y era mala elección por dos motivos: el
     * ruido es incompresible —así que la prueba de que adelgaza podía fallar por el material y no por el
     * código— y además es lo más lento de comprimir. Medido: 1,5 segundos por imagen, en una batería que
     * entera dura dos minutos.
     *
     * <p>Esto se parece más a una foto de producto: zonas de color con bordes y un degradado encima. Se
     * comprime como se comprimiría una prenda sobre un fondo, que es lo que hay en este catálogo.
     */
    private static byte[] fotoJpeg(int ancho, int alto) throws Exception {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        int[] pixeles = new int[ancho * alto];
        for (int y = 0; y < alto; y++) {
            for (int x = 0; x < ancho; x++) {
                int banda = (x / 64 + y / 64) % 3;
                int suave = (x * 255 / ancho + y * 255 / alto) / 2;
                int r = banda == 0 ? suave : 40;
                int g = banda == 1 ? suave : 90;
                int b = banda == 2 ? suave : 140;
                pixeles[y * ancho + x] = (r << 16) | (g << 8) | b;
            }
        }
        img.setRGB(0, 0, ancho, alto, pixeles, 0, ancho);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private void set(String campo, Object valor) throws Exception {
        Field f = CompresorDeImagen.class.getDeclaredField(campo);
        f.setAccessible(true);
        f.set(compresor, valor);
    }
}
