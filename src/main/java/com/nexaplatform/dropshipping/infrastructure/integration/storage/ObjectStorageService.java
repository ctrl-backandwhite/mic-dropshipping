package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

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
                client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket)
                        .config(publicReadPolicy(bucket)).build());
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
    public java.util.Set<String> listKeys() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (client == null) {
            return keys;
        }
        try {
            for (io.minio.Result<io.minio.messages.Item> r : client.listObjects(
                    io.minio.ListObjectsArgs.builder().bucket(bucket).recursive(true).build())) {
                keys.add(r.get().objectName());
            }
        } catch (Exception e) {
            log.warn("No se pudieron listar las claves del bucket {}: {}", bucket, e.getMessage());
        }
        return keys;
    }

    /** Sube los bytes con la clave dada y devuelve la URL pública navegable. */
    public String upload(String key, byte[] data, String contentType) throws Exception {
        client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                .stream(new ByteArrayInputStream(data), data.length, -1)
                .contentType(contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream")
                .build());
        return publicUrl.replaceAll("/+$", "") + "/" + key;
    }

    /** Política de bucket: lectura anónima (GET) de los objetos, para servir las imágenes directo al navegador. */
    private static String publicReadPolicy(String bucket) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                + "\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + bucket + "/*\"]}]}";
    }
}
