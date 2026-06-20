package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryGroupMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Pertenencia de categorías a grupos. Alimenta la resolución de reglas CATEGORY_GROUP en MarginService. */
public interface CategoryGroupMemberRepository
        extends JpaRepository<CategoryGroupMemberEntity, CategoryGroupMemberEntity.Id> {

    /** Grupos a los que pertenece una categoría — usado por MarginService para resolver CATEGORY_GROUP. */
    @Query("SELECT m.id.groupId FROM CategoryGroupMemberEntity m WHERE m.id.categoryId = :categoryId")
    List<UUID> findGroupIdsByCategoryId(@Param("categoryId") UUID categoryId);

    /** Categorías de un grupo (listado admin). */
    @Query("SELECT m.id.categoryId FROM CategoryGroupMemberEntity m WHERE m.id.groupId = :groupId")
    List<UUID> findCategoryIdsByGroupId(@Param("groupId") UUID groupId);
}
