package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "user_shop_connection")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShopConnectionEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(nullable = false, length = 40)
    private String platform;

    @Column(name = "shop_handle", nullable = false, length = 180)
    private String shopHandle;

    @Column(name = "access_token_enc", length = 2000)
    private String accessTokenEnc;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "CONNECTED";

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    // DROP-693: resultado del último sync — por qué se publicaron 0 productos, o el error de la API.
    @Column(name = "last_sync_error", length = 1000)
    private String lastSyncError;

    @Column(name = "last_sync_message", length = 500)
    private String lastSyncMessage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
