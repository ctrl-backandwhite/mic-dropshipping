package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final S3Client s3;

    @Value("${nexadrop.storage.bucket}")
    private String bucket;

    @Value("${nexadrop.storage.public-base-url}")
    private String publicBaseUrl;

    public String putBytes(String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(bytes));
        return publicUrl(key);
    }

    public String publicUrl(String key) {
        if (publicBaseUrl.endsWith("/")) {
            return publicBaseUrl + key;
        }
        return publicBaseUrl + "/" + key;
    }
}
