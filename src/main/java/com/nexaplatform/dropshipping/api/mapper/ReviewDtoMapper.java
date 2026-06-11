package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.ReviewItemDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.ReviewListDtoOut;
import com.nexaplatform.dropshipping.domain.model.ProductReview;
import com.nexaplatform.dropshipping.domain.model.ProductReviewPage;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the public product reviews boundary: translates the
 * {@link ProductReview} domain model into the transport DtoOut and the
 * {@link ProductReviewPage} read model into {@link ReviewListDtoOut}. Injected in
 * the controller. DtoOut field names preserve the exact JSON keys the storefront
 * already consumes (id, productId, authorName, authorCountry, rating, title, body,
 * tags, helpfulCount, verifiedPurchase, createdAt; items, page, size,
 * totalElements, totalPages, distribution, averageRating).
 */
@Mapper(componentModel = "spring")
public interface ReviewDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "authorName", source = "authorName")
    @Mapping(target = "authorCountry", source = "authorCountry")
    @Mapping(target = "rating", source = "rating")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "tags", source = "tags")
    @Mapping(target = "helpfulCount", source = "helpfulCount")
    @Mapping(target = "verifiedPurchase", source = "verifiedPurchase")
    @Mapping(target = "createdAt", source = "createdAt")
    ReviewItemDtoOut toItem(ProductReview model);

    List<ReviewItemDtoOut> toItemList(List<ProductReview> models);

    @Mapping(target = "items", source = "items")
    @Mapping(target = "page", source = "page")
    @Mapping(target = "size", source = "size")
    @Mapping(target = "totalElements", source = "totalElements")
    @Mapping(target = "totalPages", source = "totalPages")
    @Mapping(target = "distribution", source = "distribution")
    @Mapping(target = "averageRating", source = "averageRating")
    ReviewListDtoOut toListDtoOut(ProductReviewPage model);
}
