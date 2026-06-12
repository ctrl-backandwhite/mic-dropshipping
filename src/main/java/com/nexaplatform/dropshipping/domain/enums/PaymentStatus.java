package com.nexaplatform.dropshipping.domain.enums;

public enum PaymentStatus {
    PENDING, PROCESSING, REQUIRES_ACTION, // 3DS challenge or PayPal approval
    SUCCEEDED, FAILED, CANCELLED, EXPIRED, REFUNDED
}
