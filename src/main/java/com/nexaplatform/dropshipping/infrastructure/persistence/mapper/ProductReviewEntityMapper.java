package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.ProductReview;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link ProductReview} domain model and
 * the JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 * The {@code product} relation is flattened to {@code productId} on the domain
 * side and re-resolved by the repository adapter (which owns the managed entity),
 * so it is ignored on {@code toEntity}. {@code tags} are parsed from / collapsed
 * to the comma-separated column.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface ProductReviewEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "productId", expression = "java(entity.getProduct() != null ? entity.getProduct().getId() : null)")
    @Mapping(target = "authorName", source = "authorName")
    @Mapping(target = "authorCountry", source = "authorCountry")
    @Mapping(target = "rating", source = "rating")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "tags", source = "tags", qualifiedByName = "splitTags")
    @Mapping(target = "helpfulCount", source = "helpfulCount")
    @Mapping(target = "verifiedPurchase", source = "verifiedPurchase")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "approved", source = "approved")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    ProductReview toDomain(ProductReviewEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "authorName", source = "authorName")
    @Mapping(target = "authorCountry", source = "authorCountry")
    @Mapping(target = "rating", source = "rating")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "body", source = "body")
    @Mapping(target = "tags", source = "tags", qualifiedByName = "joinTags")
    @Mapping(target = "helpfulCount", source = "helpfulCount")
    @Mapping(target = "verifiedPurchase", source = "verifiedPurchase")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "approved", source = "approved")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    ProductReviewEntity toEntity(ProductReview model);

    List<ProductReview> toDomainList(List<ProductReviewEntity> entities);

    /** Splits the comma-separated tags column into a list (empty if blank). */
    @Named("splitTags")
    default List<String> splitTags(String tags) {
        return tags == null || tags.isBlank() ? List.of() : List.of(tags.split(","));
    }

    /** Collapses the tag list back into the comma-separated column (null if empty). */
    @Named("joinTags")
    default String joinTags(List<String> tags) {
        return tags == null || tags.isEmpty() ? null : String.join(",", tags);
    }
}
