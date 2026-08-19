# Normas del backend

## NORMA OBLIGATORIA: todo desarrollo lleva sus pruebas

**Ningún cambio de código se da por terminado sin las pruebas que lo cubren.** No es una
recomendación ni algo que se deje para una tarea posterior: las pruebas se escriben en el mismo
trabajo que el código, y el trabajo no está hecho hasta que existen y pasan.

Aplica a todo: funcionalidad nueva, corrección de fallos, refactorizaciones y cambios de
configuración que alteren comportamiento.

### Cómo se aplica

- **Fallo corregido → prueba que lo reproduce.** Primero en rojo, comprobando que falla por el
  motivo esperado; después el arreglo; después en verde. Una prueba escrita después del arreglo
  pasa a la primera y no demuestra nada.
- **Código nuevo → prueba de cada conducta observable**, incluidos los bordes: nulos, cero,
  negativos, decimales, desbordamiento, colecciones vacías y el camino de error. El caso feliz
  solo no basta.
- **La cobertura no baja del 80 %.** Es el umbral del quality gate de SonarCloud sobre código
  nuevo, y cruzarlo hacia abajo rompe la construcción.

### Comandos

```bash
mvn test                 # batería unitaria (surefire)
mvn verify               # incluye los *IT con Testcontainers (failsafe) y genera JaCoCo
```

Los tests de integración terminan en `*IT` y **solo corren con `mvn verify`**, nunca con
`-Dtest=`. Para lanzar uno suelto:

```bash
mvn verify -Dit.test=MiClaseIT -Dtest=ZZZSinTests -Dsurefire.failIfNoSpecifiedTests=false
```

Ojo: no existe `./mvnw` en este repositorio. Se usa `mvn` del sistema.

### Qué hace buena a una prueba aquí

- El nombre describe **una conducta observable**, no un detalle de implementación. Mal:
  «testCalculaPrecio». Bien: «con MOQ mayor que uno el margen se aplica a la mitad».
- Se afirma sobre el resultado que ve quien usa el sistema, no sobre el simulacro. Comprobar el
  cuerpo de una petición sí vale cuando el dato enviado es lo que importa.
- Los casos que no son evidentes llevan un comentario explicando **qué se rompería en producción**
  si esa prueba fallara. Varias de las pruebas de este repositorio documentan así fallos reales que
  costaron dinero; ese comentario es lo que impide que se vuelvan a introducir.

### Que la batería siga siendo rápida

La suite tarda **unos 2 minutos para 3.694 pruebas**. Ese número hay que defenderlo a medida que se
añaden pruebas, y se defiende escribiéndolas bien, no con trucos de configuración.

**Lo que hay que evitar al escribir una prueba nueva:**

- **No regenerar material criptográfico en cada prueba.** Un par RSA de 2048 bits cuesta unos 0,7 s.
  Generarlo en `@BeforeEach` lo multiplica por el número de pruebas de la clase; en agosto de 2026
  dos clases se llevaban así ~10 s de la batería. Una clave de prueba es dato inmutable: va en un
  `static final` que se genera una vez. Lo mismo aplica a BCrypt y a cualquier derivación de clave.
- **Nada de `Thread.sleep` ni esperas por reloj.** Hoy no hay ni una en toda la batería y así debe
  seguir. Si hay que esperar a algo, se espera a la condición, no al tiempo.
- **`@SpringBootTest` solo en los `*IT`.** Levantar el contexto de Spring en una prueba unitaria
  cuesta segundos; hoy solo hay una unitaria que lo haga. Una prueba unitaria construye su clase con
  `new` y simulacros.
- **Vigila el coste por prueba.** Si una clase supera el segundo por prueba, casi siempre es que
  monta algo caro que podría compartirse.

**Lo que NO hay que hacer: paralelizar surefire.** Se midió el 19-ago-2026 y **empeora**:

| Configuración | Tiempo |
|---|---|
| `forkCount=1` (la actual) | **2:37** |
| `forkCount=4` | 3:33 |
| `forkCount=1C` (8 forks) | 3:28 |

Con 335 clases pequeñas, el arranque de cada JVM extra —carga de clases más el agente de ByteBuddy
de Mockito— cuesta más de lo que se gana solapando. Y paralelizar **por hilos** dentro de un mismo
JVM está directamente descartado: 5 clases usan `mockStatic`, que sustituye estáticos de forma global
al proceso, y 13 manipulan `SecurityContextHolder`, `PricingCountryHolder` o `LocaleHolder`. Con
hilos compartiendo JVM aparecerían fallos intermitentes imposibles de reproducir.

Ojo también con `-T1C`: este proyecto es **de un solo módulo**, así que esa bandera no hace nada.

## Otras normas del repositorio

- **Imports, no nombres completos en línea.** Se importa la clase; no se escribe el paquete entero
  dentro del código.
- **Tipos explícitos, nada de `var`.**
- **Constantes en `enum`, no en `Map`.**
- **Los mensajes de error se traducen a los 8 idiomas** desde `ErrorCode`, que es la única fuente de
  verdad. Añadir un error es añadir una constante con sus 8 traducciones.
- **Los secretos van con el prefijo `nexadrop`**; un `@Value` sin él aborta el arranque a propósito.

**Lo que NO hay que hacer: compartir un solo Postgres entre contextos.** Se probó el 19-ago-2026 y
**rompe la batería de integración**: 23 pruebas en rojo. La idea era tentadora —siete clases `*IT`
fuerzan su propio contexto de Spring con `@MockitoBean` o `@TestPropertySource`, y cada contexto
levanta su Postgres y repite las 154 migraciones de Liquibase—, pero convertir el contenedor de
`TestContainersConfiguration` en un `static final` compartido falla por dos motivos:

- **Se pierden los datos de referencia.** `BaseIntegration` vacía las tablas antes de cada prueba, y
  eso se lleva también las filas que siembran las migraciones. Con un contenedor por contexto no se
  notaba, porque el contexto siguiente volvía a migrar; con la base compartida, Liquibase encuentra su
  tabla de cambios poblada y no repone nada. Síntoma: `CoberturaFiscalDestinosIT` con
  `expected: 90 but was: 0`.
- **Los datos cifrados dejan de leerse.** Los JWK guardados en la base se cifran con la clave maestra
  del contexto que los escribió, y otro contexto no puede descifrarlos. Síntoma:
  `AEADBadTagException: Tag mismatch` dentro de `JwkKeyService.toRsaKey`.

Si algún día se retoma, la vía es aislar por **esquema o base distinta por contexto** dentro del mismo
contenedor, nunca compartir la misma.
