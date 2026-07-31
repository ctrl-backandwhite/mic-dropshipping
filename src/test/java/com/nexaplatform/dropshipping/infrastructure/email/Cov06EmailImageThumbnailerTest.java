package com.nexaplatform.dropshipping.infrastructure.email;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Miniaturas de las imágenes que viajan adjuntas en el correo. La regla de fondo es la misma en todos
 * los casos: si algo no se puede procesar se devuelve el original, porque adjuntar una imagen grande es
 * malo pero no mostrar nada en la factura es peor.
 */
class Cov06EmailImageThumbnailerTest {

    /** Lado máximo que produce el reductor (2× los 52 px que pinta la plantilla). */
    private static final int LADO_MAXIMO = 104;

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

    /** Ya cabe en la miniatura: recodificar solo añadiría pérdida de calidad sin ahorrar peso. */
    @Test
    void imagenPequenaNoSeRecodifica() throws IOException {
        byte[] pequena = png(60, 40, Color.RED);

        assertThat(EmailImageThumbnailer.thumbnail(pequena)).isSameAs(pequena);
    }

    /** Se reduce al lado máximo CONSERVANDO la proporción, y sale como JPEG (más ligero para el correo). */
    @Test
    void imagenGrandeSeReduceConservandoLaProporcion() throws IOException {
        byte[] grande = png(400, 200, Color.BLUE);

        byte[] mini = EmailImageThumbnailer.thumbnail(grande);

        BufferedImage leida = ImageIO.read(new ByteArrayInputStream(mini));
        assertThat(leida.getWidth()).isEqualTo(LADO_MAXIMO);
        assertThat(leida.getHeight()).isEqualTo(LADO_MAXIMO / 2);
        assertThat(mini).isNotSameAs(grande);
        // Cabecera SOI de JPEG: confirma que se recodificó y no es el PNG original.
        assertThat(mini[0]).isEqualTo((byte) 0xFF);
        assertThat(mini[1]).isEqualTo((byte) 0xD8);
    }

    /** El lado largo manda: una imagen apaisada al revés se reduce por la altura. */
    @Test
    void laReduccionSeAplicaSobreElLadoMasLargo() throws IOException {
        byte[] alta = png(200, 600, Color.GREEN);

        BufferedImage leida = ImageIO.read(new ByteArrayInputStream(EmailImageThumbnailer.thumbnail(alta)));

        assertThat(leida.getHeight()).isEqualTo(LADO_MAXIMO);
        assertThat(leida.getWidth()).isEqualTo(35); // 200 * (104/600) redondeado
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
