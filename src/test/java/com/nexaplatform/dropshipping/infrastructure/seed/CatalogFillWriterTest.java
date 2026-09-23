package com.nexaplatform.dropshipping.infrastructure.seed;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.infrastructure.integration.bus.CatalogoBusService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Cubre dos cosas del importador masivo que se deciden en el escritor: la reconstrucción del slug y en
 * qué idioma se guarda cada texto.
 *
 * <p>El slug lo genera upsertProduct a partir del título ZH, y Slugify descarta los caracteres CJK, así
 * que con un título chino real quedaba en "-&lt;externalId&gt;". Aquí sí está disponible el título ES,
 * que es el que debe mandar.
 *
 * <p>Los textos, porque el importador toma como canónico el título de cualquier idioma cuando no hay
 * español —lo necesita para validar la fila y derivar el identificador externo—, y un volcado de 1688
 * que venía solo en chino acababa con el mismo título chino guardado en es, en, pt y zh.
 */
@ExtendWith(MockitoExtension.class)
class CatalogFillWriterTest {

    @Mock
    private CatalogUseCase catalogService;

    @Mock
    private ProductRepository productRepository;

    /**
     * El bus de integración está apagado en las pruebas: sin él, publicar no forma parte de lo que
     * se está comprobando aquí. Hace falta declararlo igualmente porque Mockito solo inyecta los
     * colaboradores que se le nombran, y el que falta llega como null.
     */
    @Mock
    private ObjectProvider<CatalogoBusService> busCatalogo;

    @InjectMocks
    private CatalogFillWriter writer;

    private static IngestProductRequest req(String externalId, String titleZh) {
        return new IngestProductRequest("1688", externalId, titleZh, null, null, null, 1, new BigDecimal("19.90"),
                "CNY", 500, 100, null, new BigDecimal("4.8"), 30,
                "https://detail.1688.com/offer/" + externalId + ".html", null, null, List.of(), null, null, null);
    }

    private ProductEntity stubProduct(String externalId, String slug) {
        ProductEntity p = ProductEntity.builder().source("1688").externalId(externalId).slug(slug).moq(1)
                .monthlySales(100).reviewCount(30).build();
        p.setId(UUID.randomUUID());
        when(catalogService.upsertProduct(any(IngestProductRequest.class))).thenReturn(p);
        when(productRepository.findById(p.getId())).thenReturn(Optional.of(p));
        when(productRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        return p;
    }

    @Test
    void write_rebuildsSlugFromSpanishTitle_whenChineseTitleLeftItDegraded() {
        ProductEntity p = stubProduct("954159512484", "-954159512484");

        writer.write(req("954159512484", "厚底女鞋增高休闲板鞋"), "Zapatillas retro de mujer con plataforma",
                "Retro platform sneakers", "Ténis retrô", "厚底女鞋增高休闲板鞋", "desc es", "desc en", "desc pt", "desc zh",
                null);

        assertThat(p.getSlug()).isEqualTo("zapatillas-retro-de-mujer-con-plataforma-954159512484");
    }

    @Test
    void write_keepsExistingReadableSlug_soReimportDoesNotChangeTheUrl() {
        ProductEntity p = stubProduct("954159512484", "slug-bueno-de-antes-954159512484");

        writer.write(req("954159512484", "厚底女鞋增高休闲板鞋"), "Otro título distinto", "Another title", "Outro título",
                "厚底女鞋增高休闲板鞋", "desc es", "desc en", "desc pt", "desc zh", null);

        assertThat(p.getSlug()).isEqualTo("slug-bueno-de-antes-954159512484");
    }

    @Test
    void write_leavesSlugAlone_whenSpanishTitleHasNothingToSlugify() {
        ProductEntity p = stubProduct("954159512484", "-954159512484");

        writer.write(req("954159512484", "厚底女鞋"), "厚底女鞋", "厚底女鞋", "厚底女鞋", "厚底女鞋", "desc", "desc", "desc", "desc", null);

        assertThat(p.getSlug()).isEqualTo("-954159512484");
    }

    /* ============ en qué idioma se guarda cada texto ============ */

    /** El título tal cual lo manda el proveedor: ideogramas y cifras, ni una letra latina. */
    private static final String TITULO_CHINO = "破洞牛仔裤男春季2025浅色修身弹力九分裤";

    private static String titulo(ProductEntity p, String lang) {
        return p.getTranslations().stream().filter(t -> lang.equals(t.getLanguage()))
                .map(ProductTranslationEntity::getTitle).findFirst().orElse(null);
    }

    @Test
    void write_guardaElTituloChinoSoloEnLaTraduccionChina_cuandoLaFilaVieneUnicamenteEnChino() {
        // La fila que destapó el fallo: el JSON traía translations {"zh": {...}} y nada más. El importador
        // asciende ese título a canónico para poder validar la fila, así que llega aquí en los cuatro
        // huecos; si el escritor los guardara todos, el catálogo mostraría el título chino también a quien
        // navega en español, inglés o portugués.
        ProductEntity p = stubProduct("954159512484", "-954159512484");

        writer.write(req("954159512484", TITULO_CHINO), TITULO_CHINO, TITULO_CHINO, TITULO_CHINO, TITULO_CHINO,
                TITULO_CHINO, TITULO_CHINO, TITULO_CHINO, TITULO_CHINO, null);

        assertThat(p.getTranslations()).extracting(ProductTranslationEntity::getLanguage).containsExactly("zh");
        assertThat(titulo(p, "zh")).isEqualTo(TITULO_CHINO);
    }

    @Test
    void write_dejaCadaIdiomaConSuTexto_cuandoLaFilaTraeEspanolYChino() {
        ProductEntity p = stubProduct("954159512484", "-954159512484");

        writer.write(req("954159512484", TITULO_CHINO), "Vaqueros rotos de hombre", "Ripped jeans for men",
                "Calças rasgadas de homem", TITULO_CHINO, "desc es", "desc en", "desc pt", "desc zh", null);

        assertThat(titulo(p, "es")).isEqualTo("Vaqueros rotos de hombre");
        assertThat(titulo(p, "en")).isEqualTo("Ripped jeans for men");
        assertThat(titulo(p, "pt")).isEqualTo("Calças rasgadas de homem");
        assertThat(titulo(p, "zh")).isEqualTo(TITULO_CHINO);
    }

    @Test
    void write_reflejaElEspanolEnLosDemasIdiomas_cuandoLaFilaSoloTraeEspanol() {
        // Con español y sin más idiomas, el importador manda el mismo texto en los cuatro huecos y el
        // escritor lo guarda tal cual: un título latino es un apaño legible mientras llega la traducción,
        // y es justo lo que el arreglo del chino NO debe romper.
        ProductEntity p = stubProduct("954159512484", "-954159512484");

        writer.write(req("954159512484", "Vaqueros rotos de hombre"), "Vaqueros rotos de hombre",
                "Vaqueros rotos de hombre", "Vaqueros rotos de hombre", "Vaqueros rotos de hombre", "desc es",
                "desc es", "desc es", "desc es", null);

        assertThat(p.getTranslations()).extracting(ProductTranslationEntity::getLanguage)
                .containsExactlyInAnyOrder("es", "en", "pt", "zh");
        assertThat(titulo(p, "es")).isEqualTo("Vaqueros rotos de hombre");
        assertThat(titulo(p, "en")).isEqualTo("Vaqueros rotos de hombre");
        assertThat(titulo(p, "pt")).isEqualTo("Vaqueros rotos de hombre");
    }

    @Test
    void write_conservaLaTraduccionEspanolaYaEscrita_alReimportarUnaFilaSoloEnChino() {
        // Reimportar es la operación normal (el UPSERT por externalId conserva favoritos y pedidos). Si al
        // descartar el chino se vaciara la fila del español, una traducción escrita a mano en el panel se
        // perdería en la siguiente carga del mismo producto.
        ProductEntity p = stubProduct("954159512484", "vaqueros-rotos-de-hombre-954159512484");
        p.getTranslations().add(ProductTranslationEntity.builder().product(p).language("es")
                .title("Vaqueros rotos de hombre").description("Escrito a mano").build());

        writer.write(req("954159512484", TITULO_CHINO), TITULO_CHINO, TITULO_CHINO, TITULO_CHINO, TITULO_CHINO,
                TITULO_CHINO, TITULO_CHINO, TITULO_CHINO, TITULO_CHINO, null);

        assertThat(titulo(p, "es")).isEqualTo("Vaqueros rotos de hombre");
        assertThat(titulo(p, "zh")).isEqualTo(TITULO_CHINO);
    }

    @Test
    void write_guardaEnEspanolUnTituloTraducidoConAlgunIdeogramaSuelto() {
        // Los volcados de 1688 cuelan ideogramas dentro de textos ya traducidos. Ese título es español y
        // tiene que guardarse como tal: descartarlo por un carácter dejaría al producto sin nombre.
        ProductEntity p = stubProduct("954159512484", "-954159512484");

        writer.write(req("954159512484", TITULO_CHINO), "Sandalias de tacón 露趾", "Open toe sandals",
                "Sandálias de salto", TITULO_CHINO, "desc es", "desc en", "desc pt", "desc zh", null);

        assertThat(titulo(p, "es")).isEqualTo("Sandalias de tacón 露趾");
    }
}
