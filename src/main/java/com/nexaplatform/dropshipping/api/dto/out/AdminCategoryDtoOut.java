package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.util.Map;
import java.util.UUID;

/**
 * Admin view of a catalog category. Field names preserve the exact JSON keys
 * previously emitted by the controller's ad-hoc {@code Map<String,Object>}.
 */
@Value
@Builder
public class AdminCategoryDtoOut {

    UUID id;
    String slug;
    String nameZh;
    Map<String, String> names;
    String icon;
    int position;
    boolean active;
    UUID parentId;
    long productCount;
}
