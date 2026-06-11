# Integration tests scaffold

Los tests de integración (Testcontainers Postgres + Redis + Kafka) están
**pendientes de cableado final** porque Spring Boot 4 reubicó algunos
helpers (`TestRestTemplate`, `@AutoConfigureMockMvc`) en paquetes nuevos
que el classpath actual no expone.

## Cómo activarlos cuando docker compose esté arriba

1. Asegúrate de que `docker compose up postgres redis kafka` está corriendo.
2. Añade al `pom.xml`:
   ```xml
   <dependency>
     <groupId>org.springframework.boot</groupId>
     <artifactId>spring-boot-test</artifactId>
     <scope>test</scope>
   </dependency>
   ```
3. Restaura `AbstractIntegrationTest.java`, `OutboxIntegrationTest.java` y
   `CatalogPerfIntegrationTest.java` desde la rama `feat/perf-300k`.

## Tests que sí pasan ahora

* `MoneyTest` — 32 tests unitarios del value object (DDD). Pasa con
  `mvn -Dtest=MoneyTest test`.

Los IT bajo `Testcontainers` se reactivarán en el siguiente sprint con el
ajuste de imports a Spring Boot 4.x.
