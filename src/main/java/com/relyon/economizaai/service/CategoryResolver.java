package com.relyon.economizaai.service;

import com.relyon.economizaai.model.Category;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Resolves the new two-level {@link Category} tree. Categories are a static seed,
 * so the enum→node mapping is cached in memory. The cache is warmed at startup
 * (and refreshable) rather than lazily — the hot path is a per-product persist
 * hook ({@link com.relyon.economizaai.model.listener.ProductCategoryNodeListener})
 * where issuing a query mid-flush would be reentrant and unsafe. {@link #idForLegacyEnum}
 * therefore only reads the in-memory map; it never queries.
 */
@Service
@RequiredArgsConstructor
public class CategoryResolver {

    private final CategoryRepository categoryRepository;

    private volatile Map<ProductCategory, UUID> idByLegacyEnum = new ConcurrentHashMap<>();
    private volatile Map<String, UUID> idBySlug = new ConcurrentHashMap<>();
    private volatile Map<UUID, Category> byId = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        var byEnum = new ConcurrentHashMap<ProductCategory, UUID>();
        var bySlug = new ConcurrentHashMap<String, UUID>();
        var byIdMap = new ConcurrentHashMap<UUID, Category>();
        for (var category : categoryRepository.findAll()) {
            bySlug.put(category.getSlug(), category.getId());
            byIdMap.put(category.getId(), category);
            if (category.getLegacyEnum() != null) {
                byEnum.put(category.getLegacyEnum(), category.getId());
            }
        }
        idByLegacyEnum = byEnum;
        idBySlug = bySlug;
        byId = byIdMap;
    }

    /** Localized name of a category node (pt by default, en when locale is English), or null. Never queries. */
    public String labelFor(UUID categoryId, Locale locale) {
        if (categoryId == null) return null;
        var category = byId.get(categoryId);
        if (category == null) return null;
        return locale != null && "en".equals(locale.getLanguage()) ? category.getNameEn() : category.getNamePt();
    }

    /** Slug of a category node (stable key for FE icon/color mapping), or null. Never queries. */
    public String slugForId(UUID categoryId) {
        if (categoryId == null) return null;
        var category = byId.get(categoryId);
        return category == null ? null : category.getSlug();
    }

    /**
     * The node for a slug PLUS its direct child subcategories — so filtering by a GROUP
     * slug (e.g. "carnes") matches products on the group or any of its subcategories. The
     * taxonomy is two levels, so one hop covers every descendant. Empty when the slug is
     * unknown. Never queries.
     */
    public Set<UUID> nodeAndDescendantIds(String slug) {
        var rootId = idBySlug.get(slug);
        if (rootId == null) return Set.of();
        var ids = new HashSet<UUID>();
        ids.add(rootId);
        for (var category : byId.values()) {
            if (category.getParent() != null && rootId.equals(category.getParent().getId())) {
                ids.add(category.getId());
            }
        }
        return ids;
    }

    /** Category id the given legacy enum maps to by default, or null. Never queries. */
    public UUID idForLegacyEnum(ProductCategory legacyEnum) {
        return legacyEnum == null ? null : idByLegacyEnum.get(legacyEnum);
    }

    /** The top-level group a node belongs to (itself when it already is a group), or null if unknown. Never queries. */
    public UUID groupIdOf(UUID categoryId) {
        if (categoryId == null) return null;
        var category = byId.get(categoryId);
        if (category == null) return null;
        return category.getParent() == null ? category.getId() : category.getParent().getId();
    }

    /** All top-level group node ids (the categories a product sits on when it has no finer subcategory). Never queries. */
    public Set<UUID> groupIds() {
        return byId.values().stream()
                .filter(category -> category.getParent() == null)
                .map(Category::getId)
                .collect(Collectors.toSet());
    }

    /** Categorias SEM subcategoria (Pet, Limpeza, Higiene, Congelados, Outros) — não há folha pra refinar. Never queries. */
    public Set<UUID> groupIdsWithoutSubcategories() {
        var parentsWithChildren = byId.values().stream()
                .filter(category -> category.getParent() != null)
                .map(category -> category.getParent().getId())
                .collect(Collectors.toSet());
        return byId.values().stream()
                .filter(category -> category.getParent() == null)
                .map(Category::getId)
                .filter(groupId -> !parentsWithChildren.contains(groupId))
                .collect(Collectors.toSet());
    }

    /** The legacy enum of the group a node belongs to (so approving a leaf keeps enum↔node consistent), or null. Never queries. */
    public ProductCategory legacyEnumForNode(UUID categoryId) {
        var groupId = groupIdOf(categoryId);
        if (groupId == null) return null;
        var group = byId.get(groupId);
        return group == null ? null : group.getLegacyEnum();
    }

    /** True when the node is a leaf (subcategory, i.e. has a parent group). False for a group or unknown id. Never queries. */
    public boolean isLeafNode(UUID categoryId) {
        if (categoryId == null) return false;
        var category = byId.get(categoryId);
        return category != null && category.getParent() != null;
    }

    /**
     * CONVERSOR DE BORDA pros escritores LEGADOS (que recebem o enum como intenção): dado o enum
     * novo e o nó atual, devolve o nó que acompanha a mudança:
     * <ul>
     *   <li>null enum → null node (reset de categorização);</li>
     *   <li>no current node → the enum's group node (the floor);</li>
     *   <li>current node is a subcategory under the RIGHT group → kept (preserves the finer leaf);</li>
     *   <li>current node is stale (a leaf under another group) → collapsed to the enum's group.</li>
     * </ul>
     * FASE 2: o listener agora é node-first ({@link #deriveCoarseEnum}); quem escreve o ENUM como
     * intenção (admin coarse, LLM enrich, consenso, farmácia) DEVE chamar isto pra mover o nó
     * junto — senão o nó antigo vence e a escrita de enum é desfeita. Never queries.
     */
    public UUID reconcileNode(ProductCategory legacyEnum, UUID currentNodeId) {
        if (legacyEnum == null) return null;
        // Finer groups that have NO legacy enum (laticínios e ovos, doces e snacks, congelados,
        // bebidas alcoólicas, casa e descartáveis) can't be the target of any enum, so the
        // enum↔node invariant can't anchor them. Keep the explicitly-assigned fine node instead
        // of collapsing it — otherwise those subcategorias could never hold a product.
        if (currentNodeId != null) {
            var nodeGroup = byId.get(groupIdOf(currentNodeId));
            if (nodeGroup != null && nodeGroup.getLegacyEnum() == null) return currentNodeId;
        }
        var groupId = idByLegacyEnum.get(legacyEnum);
        if (groupId == null) return currentNodeId; // enum with no mapped node — leave untouched
        if (currentNodeId == null) return groupId;
        return groupId.equals(groupIdOf(currentNodeId)) ? currentNodeId : groupId;
    }

    /**
     * FASE 2 (nó é a verdade): o enum que um produto deve carregar DERIVADO do seu nó — a
     * projeção legada, não mais a origem. Regras:
     * <ul>
     *   <li>categoria do nó tem legacy_enum → esse enum (o nó manda);</li>
     *   <li>categoria sem enum legado (laticínios, doces, congelados, alcoólicas, casa) → mantém o
     *       enum atual (aproximação legada, ex.: queijos continua MEAT_DAIRY), senão OTHER (âncora:
     *       produto com nó nunca fica com enum nulo);</li>
     *   <li>nó desconhecido pro cache → mantém o enum atual (não inventa).</li>
     * </ul>
     */
    public ProductCategory deriveCoarseEnum(UUID nodeId, ProductCategory currentEnum) {
        var groupId = groupIdOf(nodeId);
        if (groupId == null) return currentEnum;
        var group = byId.get(groupId);
        var derived = group == null ? null : group.getLegacyEnum();
        if (derived != null) return derived;
        return currentEnum != null ? currentEnum : ProductCategory.OTHER;
    }

    /**
     * Escritor de NÓ (update de produto): seta o nó e DERIVA o enum na hora. NÃO confie no
     * listener pra isso — no Hibernate 7, mudança de campo cruzado em @PreUpdate/@PrePersist
     * não é persistida.
     */
    public void applyNode(Product product, UUID nodeId) {
        product.setCategoryId(nodeId);
        product.setCategory(deriveCoarseEnum(nodeId, product.getCategory()));
    }

    /**
     * Alinha enum↔nó num produto recém-construído (ANTES do primeiro save): com nó, deriva o
     * enum; com só o enum, dá o piso (nó-grupo). Chame em todo site de criação de Product —
     * o listener NÃO persiste campo cruzado no Hibernate 7 (@PrePersist incluso).
     */
    public void alignNewProduct(Product product) {
        if (product.getCategoryId() != null) {
            product.setCategory(deriveCoarseEnum(product.getCategoryId(), product.getCategory()));
        } else if (product.getCategory() != null) {
            product.setCategoryId(idForLegacyEnum(product.getCategory()));
        }
    }

    /** Category id for a taxonomy slug (group or leaf), or null. Never queries. */
    public UUID idForSlug(String slug) {
        return slug == null ? null : idBySlug.get(slug);
    }

    public Optional<Category> findById(UUID id) {
        return id == null ? Optional.empty() : categoryRepository.findById(id);
    }

    public Optional<Category> findBySlug(String slug) {
        return categoryRepository.findBySlug(slug);
    }
}
