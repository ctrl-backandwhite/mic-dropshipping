# NX036 — Backend (`mic-dropshipping`)

La API y toda la lógica de negocio: catálogo, precios, aduana, pedidos, cobro y envío.

**Spring Boot 4.1.1 · Java 25 · PostgreSQL · Liquibase**

> Las **normas para escribir aquí** están en [`CLAUDE.md`](CLAUDE.md), y mandan sobre cualquier
> costumbre: pruebas obligatorias, convenciones de Java, errores en ocho idiomas. Léelo antes de
> tocar código. Este fichero explica qué es el servicio y cómo se arranca; aquel, cómo se escribe.

---

## Arrancar en local

El servicio no se levanta suelto: necesita su base de datos, su caché y su buscador. Todo eso está
en el compose de la raíz del proyecto.

```bash
cd ../infra/docker && docker compose up -d     # postgres, redis, opensearch, redpanda, mongo
```

La API queda en **`localhost:18082`**. Liquibase aplica las migraciones al arrancar.

**Tras cada cambio de código hay que reconstruir**, porque el contenedor monta un jar:

```bash
mvn package -DskipTests
cp target/dropshipping-backend-*.jar ../infra/docker/jar/app.jar
docker restart nexadrop-backend
```

Saltarse esto es la causa más frecuente de perseguir en el navegador un fallo que solo era un jar
viejo.

## Pruebas

```bash
mvn test      # unitarias (surefire) — 4.156 a 25-sep-2026, ~2,5 min
mvn verify    # añade los *IT, que levantan Postgres con Testcontainers
```

Los tests de integración terminan en `*IT` y **solo corren con `mvn verify`**, nunca con `-Dtest=`.

## Formato

```bash
mvn formatter:format impsort:sort      # formatea Y ordena imports
```

Son dos plugins y hacen falta los dos: el primero recoloca código pero no toca los imports.
**Aviso:** el repositorio no está formateado del todo, así que una pasada global mueve cientos de
ficheros ajenos a tu cambio. Formatea solo lo tuyo.

---

## Qué hace este servicio

### Precio

Es lo más delicado del sistema y **se calcula aquí, nunca en el cliente**. La cadena canónica:

```
precio del proveedor (CNY)  →  coste en USD  →  + margen  →  precio mostrado en la moneda del cliente
```

Sobre esa base se aplican, en este orden: el **IVA chino**, el **porte del proveedor**, un
**recargo fijo** por producto o por tramo de cantidad, y las dos **bolsas de subvención** (envío y
arancel). `PricingService` es el único sitio donde esto se compone; si necesitas un precio, pásale
por ahí.

Tres reglas que ya costaron dinero cuando no se respetaron:

- El **margen se decide por el país donde el comprador se registró**, nunca por el de envío.
- Los **tramos por cantidad se cobran de verdad** desde el 23-sep-2026, aplicados como proporción
  sobre el precio de la variante.
- El **redondeo se hace una sola vez, al final**. Multiplicar un unitario ya redondeado llegó a
  cobrar un 1,45 % de más.

### Aduana

Derecho de la Unión de 3 EUR **por línea de declaración**, no por producto. Los productos que
comparten partida, material y uso se agrupan en una sola línea, y esa agrupación la aprueba una
persona a mano en `/admin/declaration-groups`: aprobar es firmar lo que se declara ante 27 aduanas.

### Envío

**YunExpress** es el único transportista, detrás del puerto `FulfillmentProvider`. El proveedor
chino entrega en el almacén **CNCHASHAN** (Dongguan, Guangdong), cliente `CNHC459832`.

Cambiarlo sería añadir un adaptador, no reescribir el pedido. Cainiao y CJ Dropshipping se probaron
y se descartaron: no queda nada suyo vivo.

**Prohibido el simulacro en preproducción y producción.** Si el transportista falla, el pedido va a
la bandeja de incidencias; nunca se inventa una guía.

### Catálogo

Los productos entran por el **importador masivo**: `POST /api/admin/catalog/products/bulk`. La
extracción desde 1688 **ya no vive en este proyecto** —se movió a su propio repositorio el
25-sep-2026—, así que este servicio solo recibe.

Ojo con el importador: es un *upsert* que **reconstruye**. Borra y recrea variantes, imágenes,
atributos y tramos, y deja a nulo los escalares que no mandes. Para cambiar cuatro columnas de un
producto ya cargado, SQL directo; para cambiar la ficha, se reenvía entera.

---

## Documentación

| Qué | Dónde |
|---|---|
| Normas para escribir código aquí | [`CLAUDE.md`](CLAUDE.md) |
| Contrato de la API para socios | [`../docs/api/INTEGRATION.md`](../docs/api/INTEGRATION.md) |
| Seguridad y pentest | [`certificacion/`](certificacion/) |
| Por qué se decidió algo | `../docs/superpowers/specs/` |
| Averías con post mortem | [`docs/rendimiento/`](docs/rendimiento/) |

## Configuración

Los secretos van con el prefijo **`nexadrop`**; un `@Value` sin él aborta el arranque a propósito.
En local se leen de `../infra/docker/.env`; en el clúster, de External Secrets.
