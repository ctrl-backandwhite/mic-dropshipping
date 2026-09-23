package com.nexaplatform.dropshipping.infrastructure.email;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Miniaturas de las imágenes que viajan adjuntas en el correo.
 *
 * <p>La regla de fondo es la misma en todos los casos: si algo no se puede procesar se devuelve el
 * original, porque adjuntar una imagen grande es malo pero no mostrar nada en la factura es peor.
 *
 * <p><b>Y salen CUADRADAS, recortadas por el centro.</b> Antes se reducían conservando la proporción a
 * 104 px de lado —el tamaño calculado para los 52 px de la factura— y esa misma miniatura se usaba en
 * el correo de productos vistos, que las pinta a 180: llegaban borrosas por ampliar desde 104, y encima
 * deformadas, porque la plantilla fijaba {@code height:168px} confiando en {@code object-fit:cover},
 * que Gmail ignora. Las pruebas de este fichero fijan el comportamiento nuevo: quien lo cambie a
 * «conservar proporción» vuelve a romper el correo, y no se notará hasta que alguien lo abra.
 */
class Cov06EmailImageThumbnailerTest {

    /** Lado de la miniatura cuadrada: 2× los 180 px de la tarjeta más grande, para pantallas HiDPI. */
    private static final int LADO = 360;

    @Test
    void sinBytesDevuelveLoQueLeDieron() {
        assertThat(EmailImageThumbnailer.thumbnail(null)).isNull();
        byte[] vacio = new byte[0];
        assertThat(EmailImageThumbnailer.thumbnail(vacio)).isSameAs(vacio);
    }

    /**
     * Formato que {@link ImageIO} no sabe leer (el caso real es WEBP sin plugin): se adjunta el original
     * en lugar de dejar la línea de la factura sin foto.
     */
    @Test
    void formatoIlegibleDevuelveElOriginal() {
        byte[] noEsImagen = "esto no es una imagen".getBytes(StandardCharsets.UTF_8);

        assertThat(EmailImageThumbnailer.thumbnail(noEsImagen)).isSameAs(noEsImagen);
    }

    /**
     * Una foto grande sale CUADRADA y como JPEG. Que sea cuadrada es lo que permite a la plantilla
     * pintarla con alto y ancho fijos sin deformarla, sin depender de object-fit.
     */
    @Test
    void unaFotoGrandeSaleCuadradaYComoJpeg() throws IOException {
        byte[] grande = png(800, 800, Color.BLUE);

        byte[] mini = EmailImageThumbnailer.thumbnail(grande);

        BufferedImage leida = ImageIO.read(new ByteArrayInputStream(mini));
        assertThat(leida.getWidth()).isEqualTo(LADO);
        assertThat(leida.getHeight()).isEqualTo(LADO);
        assertThat(mini).isNotSameAs(grande);
        // Cabecera SOI de JPEG: confirma que se recodificó y no es el PNG original.
        assertThat(mini[0]).isEqualTo((byte) 0xFF);
        assertThat(mini[1]).isEqualTo((byte) 0xD8);
    }

    /** Una foto apaisada se recorta por los lados, no se encoge: el resultado sigue siendo cuadrado. */
    @Test
    void unaFotoApaisadaSeRecortaACuadrado() throws IOException {
        BufferedImage leida = ImageIO
                .read(new ByteArrayInputStream(EmailImageThumbnailer.thumbnail(png(1200, 400, Color.BLUE))));

        assertThat(leida.getWidth()).isEqualTo(leida.getHeight());
    }

    /** Y una vertical, por arriba y por abajo. Es el caso de la ropa de 694x925. */
    @Test
    void unaFotoVerticalTambienSaleCuadrada() throws IOException {
        BufferedImage leida = ImageIO
                .read(new ByteArrayInputStream(EmailImageThumbnailer.thumbnail(png(694, 925, Color.GREEN))));

        assertThat(leida.getWidth()).isEqualTo(leida.getHeight());
    }

    /**
     * Una foto más pequeña que la miniatura no se AMPLÍA: se recorta a cuadrado con el lado que tiene.
     * Ampliar de 104 a 180 es justo lo que se veía borroso en el correo.
     */
    @Test
    void unaFotoPequenaNoSeAmplia() throws IOException {
        BufferedImage leida = ImageIO
                .read(new ByteArrayInputStream(EmailImageThumbnailer.thumbnail(png(120, 90, Color.RED))));

        assertThat(leida.getWidth()).isEqualTo(90);
        assertThat(leida.getHeight()).isEqualTo(90);
    }

    /**
     * PNG con transparencia: el JPEG no tiene canal alfa y, sin pintar el fondo antes, las zonas
     * transparentes salían NEGRAS en el correo. El fondo tiene que quedar blanco.
     */
    @Test
    void laTransparenciaSeRellenaEnBlancoYNoEnNegro() throws IOException {
        BufferedImage conAlfa = new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(conAlfa, "png", buf);

        BufferedImage mini = ImageIO.read(new ByteArrayInputStream(EmailImageThumbnailer.thumbnail(buf.toByteArray())));

        Color esquina = new Color(mini.getRGB(0, 0));
        assertThat(esquina.getRed()).isGreaterThan(240);
        assertThat(esquina.getGreen()).isGreaterThan(240);
        assertThat(esquina.getBlue()).isGreaterThan(240);
    }

    /** PNG opaco de un color plano, del tamaño pedido. */
    private static byte[] png(int ancho, int alto, Color color) throws IOException {
        BufferedImage img = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, ancho, alto);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(img, "png", buf);
        return buf.toByteArray();
    }
}
