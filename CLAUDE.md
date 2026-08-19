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

## Otras normas del repositorio

- **Imports, no nombres completos en línea.** Se importa la clase; no se escribe el paquete entero
  dentro del código.
- **Tipos explícitos, nada de `var`.**
- **Constantes en `enum`, no en `Map`.**
- **Los mensajes de error se traducen a los 8 idiomas** desde `ErrorCode`, que es la única fuente de
  verdad. Añadir un error es añadir una constante con sus 8 traducciones.
- **Los secretos van con el prefijo `nexadrop`**; un `@Value` sin él aborta el arranque a propósito.
