package com.nexaplatform.dropshipping.config;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;

/**
 * Base de los tests de integración de ENDPOINT (Fase 4): arranca el contexto completo con un servidor
 * real en puerto aleatorio + Postgres de Testcontainers, expone un {@link WebTestClient} apuntando al
 * servidor y un {@link JwtTestUtil} para autenticar por rol. Limpia las tablas antes de cada test.
 *
 * <p>Sin core: {@link TestContainersConfiguration} y {@link JwtTestUtil} se importan desde este repo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestContainersConfiguration.class, JwtTestUtil.class})
public abstract class BaseIntegration {

    @LocalServerPort
    private int port;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected JwtTestUtil jwt;

    /**
     * Sin broker real en los tests, {@code kafkaTemplate.send(...)} bloquea {@code max.block.ms}
     * (60s) buscando metadata y revienta el arranque. Lo mockeamos: los envíos (fire-and-forget,
     * p.ej. product.ingested) quedan en no-op. Si un test necesita verificar publicación, stubea aquí.
     */
    @MockitoBean
    protected KafkaTemplate<String, Object> kafkaTemplate;

    protected WebTestClient client;

    @BeforeEach
    void setUpClientAndCleanDb() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build();
        cleanAllTables();
    }

    /** TRUNCATE de todas las tablas de negocio (deja fuera las de Liquibase). */
    protected void cleanAllTables() {
        // jwk_keys se excluye: la clave RSA activa se genera al arrancar el contexto (una sola vez);
        // si se truncara, JwtTestUtil no podría firmar tokens en los tests siguientes.
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                        + "AND tablename NOT LIKE 'databasechange%' AND tablename <> 'jwk_keys'",
                String.class);
        if (tables.isEmpty()) {
            return;
        }
        String joined = String.join(", ", tables.stream().map(t -> "\"" + t + "\"").toList());
        jdbcTemplate.execute("TRUNCATE TABLE " + joined + " RESTART IDENTITY CASCADE");
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }
}
