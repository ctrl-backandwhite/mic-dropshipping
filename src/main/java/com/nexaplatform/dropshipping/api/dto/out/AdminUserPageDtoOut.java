package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Paged response wrapper for the admin users listing. Field names preserve the
 * exact JSON keys the controller previously emitted ({@code items},
 * {@code totalElements}, {@code totalPages}, {@code page}, {@code size}).
 */
@Value
@Builder
public class AdminUserPageDtoOut {

    List<AdminUserDtoOut> items;
    int totalElements;
    int totalPages;
    int page;
    int size;
}
