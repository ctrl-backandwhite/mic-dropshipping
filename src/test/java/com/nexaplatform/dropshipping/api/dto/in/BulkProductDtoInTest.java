package com.nexaplatform.dropshipping.api.dto.in;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica que el alta por JSON acepta las claves de imagen alternativas más comunes, para que un JSON
 * razonable no se rechace por el nombre del campo (causa del falso "no tiene imágenes"). El plegado de
 * {@code imageUrl} sobre {@code imageUrls} y el respaldo de variantes ocurren en el caso de uso.
 */
class BulkProductDtoInTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void accepts_canonical_imageUrls() throws Exception {
        BulkProductDtoIn d = mapper.readValue("{\"imageUrls\":[\"https://x/a.jpg\"]}", BulkProductDtoIn.class);
        assertThat(d.getImageUrls()).containsExactly("https://x/a.jpg");
    }

    @Test
    void accepts_images_alias_as_list() throws Exception {
        BulkProductDtoIn d = mapper.readValue("{\"images\":[\"https://x/a.jpg\",\"https://x/b.jpg\"]}",
                BulkProductDtoIn.class);
        assertThat(d.getImageUrls()).containsExactly("https://x/a.jpg", "https://x/b.jpg");
    }

    @Test
    void accepts_photos_alias_as_list() throws Exception {
        BulkProductDtoIn d = mapper.readValue("{\"photos\":[\"https://x/a.jpg\"]}", BulkProductDtoIn.class);
        assertThat(d.getImageUrls()).containsExactly("https://x/a.jpg");
    }

    @Test
    void accepts_singular_imageUrl_string() throws Exception {
        BulkProductDtoIn d = mapper.readValue("{\"imageUrl\":\"https://x/a.jpg\"}", BulkProductDtoIn.class);
        assertThat(d.getImageUrl()).isEqualTo("https://x/a.jpg");
    }

    @Test
    void accepts_image_alias_as_singular_string() throws Exception {
        BulkProductDtoIn d = mapper.readValue("{\"image\":\"https://x/a.jpg\"}", BulkProductDtoIn.class);
        assertThat(d.getImageUrl()).isEqualTo("https://x/a.jpg");
    }
}
