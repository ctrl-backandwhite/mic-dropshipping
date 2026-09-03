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
 * <p>Enviar el original de ~100 KB por foto haría correos de varios MB, que además de tardar en abrirse
 * penalizan la entregabilidad. Se reduce y se recodifica como JPEG.
 *
 * <p><b>Y se recorta a CUADRADO, que es lo que arregla el aspecto.</b> Antes se reducía manteniendo la
 * proporción a un lado máximo de 104 px —calculado para los 52 px de la factura— y esa misma miniatura
 * se usaba en el correo de productos vistos, que las pinta a 180 px: llegaban borrosas por ampliar una
 * imagen de 104, y además deformadas, porque la plantilla fijaba {@code height:168px} confiando en
 * {@code object-fit:cover}, que Gmail IGNORA. Con la foto ya cuadrada desde el servidor no hace falta
 * ninguna propiedad que el cliente pueda no soportar: se pinta tal cual y se ve bien en todos.
 *
 * <p>Si el formato no es legible por {@link ImageIO} (WEBP sin plugin) o falla el proceso, se devuelven
 * los bytes originales: mejor adjuntar la imagen grande que no mostrar nada.
 */
@Slf4j
public final class EmailImageThumbnailer {

    /**
     * Lado de la miniatura cuadrada: 2× los 180 px de la tarjeta más grande (la del correo de productos
     * vistos), para que se vea nítida en pantallas de alta densidad. La factura la pinta a 52 px y
     * reducir de 360 a 52 en el cliente se ve perfecto; al revés —ampliar de 104 a 180— no.
     */
    private static final int LADO = 360;

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

            // Recorte centrado al cuadrado más grande que quepa: se queda la parte de en medio, que es
            // donde está el producto. Recortar y no encajar con bandas, porque una banda blanca en una
            // tarjeta con fondo claro no se ve, pero la foto encogida sí.
            int lado = Math.min(w, h);
            int x = (w - lado) / 2;
            int y = (h - lado) / 2;
            BufferedImage recortada = src.getSubimage(x, y, lado, lado);

            int destino = Math.min(LADO, lado);

            // TYPE_INT_RGB descarta el canal alfa: el JPEG no lo soporta y, sin este paso, los PNG con
            // transparencia salen con el fondo en negro.
            BufferedImage out = new BufferedImage(destino, destino, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, destino, destino);
                g.drawImage(recortada, 0, 0, destino, destino, null);
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
