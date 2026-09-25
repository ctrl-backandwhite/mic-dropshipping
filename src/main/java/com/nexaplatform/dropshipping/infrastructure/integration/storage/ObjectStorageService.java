package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Almacenamiento de objetos (MinIO en local/dev, S3-compatible en prod) para el multimedia de producto.
 *
 * <p>Subimos al {@code bucket} y servimos al navegador desde {@code public-url} (el bucket es de lectura
 * pública). Así dejamos de depender del hotlink de alicdn/1688 (que devuelve 403 con Referer). MinIO y AWS
 * S3 comparten API, así que el mismo cliente sirve para ambos cambiando {@code endpoint}/credenciales.
 */
@Slf4j
@Service
public class ObjectStorageService {

    /** Respuesta de "no hay imagen". Un array de longitud cero es inmutable de hecho, así que se comparte. */
    private static final byte[] NO_BYTES = new byte[0];

    @Value("${nexadrop.storage.enabled:true}")
    private boolean enabled;
    @Value("${nexadrop.storage.endpoint:}")
    private String endpoint;
    @Value("${nexadrop.storage.public-url:}")
    private String publicUrl;
    @Value("${nexadrop.storage.bucket:product-images}")
    private String bucket;
    @Value("${nexadrop.storage.access-key:}")
    private String accessKey;
    @Value("${nexadrop.storage.secret-key:}")
    private String secretKey;
    @Value("${nexadrop.storage.region:us-east-1}")
    private String region;

    private MinioClient client;

    @PostConstruct
    void init() {
        if (!enabled || endpoint == null || endpoint.isBlank() || secretKey == null || secretKey.isBlank()) {
            log.warn("Object storage DESACTIVADO (faltan endpoint/secret) — el mirror de imágenes no subirá nada");
            return;
        }
        try {
            client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).region(region).build();
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                client.setBucketPolicy(
                        SetBucketPolicyArgs.builder().bucket(bucket).config(publicReadPolicy(bucket)).build());
                log.info("Bucket '{}' creado y configurado de lectura pública", bucket);
            }
            log.info("Object storage listo: endpoint={} bucket={} publicUrl={}", endpoint, bucket, publicUrl);
        } catch (Exception e) {
            log.error("No se pudo inicializar el object storage ({}): {}", endpoint, e.getMessage());
            client = null;
        }
    }

    /** ¿Cliente operativo? El mirror solo corre si esto es true. */
    public boolean isReady() {
        return client != null;
    }

    public String publicUrl() {
        return publicUrl;
    }

    /** Lista TODAS las claves de objeto del bucket (para verificar qué imágenes existen realmente). */
    public Set<String> listKeys() {
        Set<String> keys = new HashSet<>();
        if (client == null) {
            return keys;
        }
        try {
            for (io.minio.Result<io.minio.messages.Item> r : client
                    .listObjects(io.minio.ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
                keys.add(r.get().objectName());
            }
        } catch (Exception e) {
            log.warn("No se pudieron listar las claves del bucket {}: {}", bucket, e.getMessage());
        }
        return keys;
    }

    /** Sube los bytes con la clave dada y devuelve la URL pública navegable. */
    public String upload(String key, byte[] data, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(
                            contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream")
                    .build());
        } catch (Exception e) {
            throw new ObjectStorageException("No se pudo subir el objeto " + key, e);
        }
        return baseUrl() + "/" + key;
    }

    /**
     * Borra el objeto de esa URL pública. Silencioso si no es nuestro o ya no está.
     *
     * <p>Lo usa la compresión: cuando nace el {@code .webp}, el original deja de tener dueño —ninguna
     * fila lo apunta ya— y quedarse en el cubo es pagar dos veces por la misma foto. Con 121.825
     * imágenes eso eran decenas de GB de ficheros que nadie sirve.
     *
     * <p>No lanza: que no se pueda borrar un objeto huérfano no puede tumbar la compresión, que es lo
     * que sí aporta. Se anota y se sigue.
     */
    public void deleteByPublicUrl(String url) {
        if (client == null || url == null || url.isBlank() || publicUrl == null || publicUrl.isBlank()) {
            return;
        }
        String base = baseUrl() + "/";
        if (!url.startsWith(base)) {
            return; // externa (alicdn) o de otro host: no es nuestra, no se toca
        }
        String key = url.substring(base.length());
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
            log.debug("Objeto borrado del cubo: {}", key);
        } catch (Exception e) {
            log.debug("No se pudo borrar el objeto {}: {}", key, e.getMessage());
        }
    }

    /** Descarga los bytes de un objeto por su clave (usa el endpoint INTERNO, alcanzable por el backend). */
    public byte[] download(String key) {
        try (InputStream is = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new ObjectStorageException("No se pudo descargar el objeto " + key, e);
        }
    }

    /**
     * Bytes de un objeto a partir de su URL pública (la que se guarda en snapshots/cdn). Deriva la clave
     * quitando el prefijo {@code public-url} y descarga por el endpoint interno. Agnóstico del entorno:
     * en local usa {@code minio:9000}; en los entornos, el {@code STORAGE_ENDPOINT} configurado.
     *
     * @return los bytes, o un array VACÍO si el storage no está listo, la URL no es de este bucket o el
     *         objeto no se puede leer. Vacío y no {@code null} porque para quien llama significan lo mismo
     *         ("no hay imagen que incrustar") y así no hay que defenderse del nulo en cada uso.
     */
    public byte[] bytesFromPublicUrl(String url) {
        if (client == null || url == null || url.isBlank() || publicUrl == null || publicUrl.isBlank()) {
            return NO_BYTES;
        }
        String base = baseUrl() + "/";
        if (!url.startsWith(base)) {
            return NO_BYTES; // URL externa (p.ej. alicdn) o de otro host: no está en nuestro bucket
        }
        String key = url.substring(base.length());
        try {
            return download(key);
        } catch (Exception e) {
            log.debug("No se pudieron leer los bytes de {}: {}", url, e.getMessage());
            return NO_BYTES;
        }
    }

    /** URL pública sin barras finales, para poder concatenar la clave sin duplicar el separador. */
    private String baseUrl() {
        int end = publicUrl.length();
        while (end > 0 && publicUrl.charAt(end - 1) == '/') {
            end--;
        }
        return publicUrl.substring(0, end);
    }

    /** Política de bucket: lectura anónima (GET) de los objetos, para servir las imágenes directo al navegador. */
    private static String publicReadPolicy(String bucket) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                + "\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + bucket + "/*\"]}]}";
    }
}
