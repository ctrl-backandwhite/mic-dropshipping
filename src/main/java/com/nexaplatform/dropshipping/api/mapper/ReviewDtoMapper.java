package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.ReviewItemDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/**
 * MapStruct mapper for the public product reviews API boundary.
 * Translates the JPA entity into the transport DtoOut, preserving the exact
 * JSON field names of the previous {@code ReviewView} record.
 */
@Mapper(componentModel = "spring")
public interface ReviewDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "authorName", source = "authorName")
    @Mapping(target = "authorCountry", source = "authorCountry")
    @Mapping(target = "rating", source = "rating")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "tags", source = "tags", qualifiedByName = "splitTags")
    @Mapping(target = "helpfulCount", source = "helpfulCount")
    @Mapping(target = "verifiedPurchase", source = "verifiedPurchase")
    @Mapping(target = "createdAt", source = "createdAt")
    ReviewItemDtoOut toItem(ProductReviewEntity entity);

    List<ReviewItemDtoOut> toItemList(List<ProductReviewEntity> entities);

    /** Splits the comma-separated tags column into a list (empty if blank). */
    @Named("splitTags")
    default List<String> splitTags(String tags) {
        return tags == null || tags.isBlank() ? List.of() : List.of(tags.split(","));
    }
}
