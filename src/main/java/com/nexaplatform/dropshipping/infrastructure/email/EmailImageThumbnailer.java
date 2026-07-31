package com.nexaplatform.dropshipping.infrastructure.email;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Reduce las imágenes que se adjuntan a un correo a tamaño de miniatura.
 *
 * <p>La factura las muestra a 52 px; enviar el original de ~100 KB por línea haría correos de varios MB,
 * que además de tardar en abrirse penalizan la entregabilidad. Se reduce al doble del tamaño mostrado
 * (para pantallas de alta densidad) y se recodifica como JPEG.
 *
 * <p>Si el formato no es legible por {@link ImageIO} (WEBP sin plugin) o falla el proceso, se devuelven
 * los bytes originales: mejor adjuntar la imagen grande que no mostrar nada.
 */
@Slf4j
public final class EmailImageThumbnailer {

    /** Lado máximo de la miniatura: 2× los 52 px que pinta la plantilla, para pantallas HiDPI. */
    private static final int MAX_SIDE = 104;

    private EmailImageThumbnailer() {
    }

    /**
     * Devuelve la miniatura en JPEG, o los {@code original} si no se puede procesar.
     *
     * @param original bytes de la imagen tal cual salen del storage
     */
    public static byte[] thumbnail(byte[] original) {
        if (original == null || original.length == 0) {
            return original;
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(original));
            if (src == null) {
                return original; // formato no soportado por ImageIO (p.ej. WEBP)
            }
            int w = src.getWidth();
            int h = src.getHeight();
            if (w <= MAX_SIDE && h <= MAX_SIDE) {
                return original; // ya es pequeña: recodificar solo añadiría pérdida
            }
            double scale = (double) MAX_SIDE / Math.max(w, h);
            int tw = Math.max(1, (int) Math.round(w * scale));
            int th = Math.max(1, (int) Math.round(h * scale));

            // TYPE_INT_RGB descarta el canal alfa: el JPEG no lo soporta y, sin este paso, los PNG con
            // transparencia salen con el fondo en negro.
            BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, tw, th);
                g.drawImage(src, 0, 0, tw, th, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (!ImageIO.write(out, "jpg", buf) || buf.size() == 0) {
                return original;
            }
            return buf.toByteArray();
        } catch (Exception e) {
            log.debug("No se pudo generar la miniatura para el email: {}", e.getMessage());
            return original;
        }
    }
}
