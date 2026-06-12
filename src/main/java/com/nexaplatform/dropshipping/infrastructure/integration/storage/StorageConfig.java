package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
public class StorageConfig {

    @Bean
    public S3Client s3Client(@Value("${nexadrop.storage.endpoint}") String endpoint,
            @Value("${nexadrop.storage.region}") String region,
            @Value("${nexadrop.storage.access-key}") String accessKey,
            @Value("${nexadrop.storage.secret-key}") String secretKey,
            @Value("${nexadrop.storage.path-style-access}") boolean pathStyle) {
        return S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build()).build();
    }
}
