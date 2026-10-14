package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.EanCatalogSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(name = "ean_catalog")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class EanCatalogEntry extends BaseEntity {

    @Column(nullable = false, unique = true, length = 14)
    private String ean;

    @Column(name = "generic_name", length = 100)
    private String genericName;

    @Column(length = 100)
    private String brand;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ProductCategory category;

    /** New-taxonomy node (categories.id) mapped from the raw OFF tags. */
    @Column(name = "category_id")
    private UUID categoryId;

    /** Raw OFF {@code categories_tags} kept verbatim so we can re-map to a finer
     *  taxonomy later WITHOUT re-fetching the (rate-limited) OFF API. */
    @Column(name = "categories_raw", columnDefinition = "text")
    private String categoriesRaw;

    /** Useful OFF metadata captured on the same fetch (avoid re-fetching later):
     *  product image, pack quantity string, labels (organic/gluten-free/…),
     *  Nutri-Score grade and NOVA ultra-processing group. */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(length = 50)
    private String quantity;

    @Column(name = "labels_tags", columnDefinition = "text")
    private String labelsTags;

    @Column(length = 1)
    private String nutriscore;

    @Column(name = "nova_group")
    private Integer novaGroup;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @lombok.Builder.Default
    private EanCatalogSource source = EanCatalogSource.OPEN_FOOD_FACTS;
}
