package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Product;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * JSON response for a single product.
 *
 * Constructed from the domain Product — the mapping is a deliberate
 * translation step, not a pass-through. If the domain model changes,
 * you update this mapping, and the API contract is unaffected.
 */
public class ProductResponse {

    private UUID          id;
    private String        name;
    private String        description;
    private BigDecimal    basePrice;
    private String        sku;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    private ProductResponse() {}

    /** Factory method — the only way to create a ProductResponse. */
    public static ProductResponse from(Product p) {
        ProductResponse r = new ProductResponse();
        r.id          = p.getId();
        r.name        = p.getName();
        r.description = p.getDescription();
        r.basePrice   = p.getBasePrice();
        r.sku         = p.getSku();
        r.createdAt   = p.getCreatedAt();
        r.updatedAt   = p.getUpdatedAt();
        return r;
    }

    // Getters — Jackson serializes via getters by default

    public UUID           getId()          { return id; }
    public String         getName()        { return name; }
    public String         getDescription() { return description; }
    public BigDecimal     getBasePrice()   { return basePrice; }
    public String         getSku()         { return sku; }
    public OffsetDateTime getCreatedAt()   { return createdAt; }
    public OffsetDateTime getUpdatedAt()   { return updatedAt; }

    // ── Paged wrapper (inner class — lives with the response it wraps) ──

    /**
     * Envelope for paginated list responses.
     *
     * JSON shape:
     * {
     *   "data":  [ { product }, ... ],
     *   "total": 42,
     *   "page":  0,
     *   "size":  10
     * }
     */
    public static class Page {
        private final List<ProductResponse> data;
        private final long                  total;
        private final int                   page;
        private final int                   size;

        public Page(List<ProductResponse> data, long total, int page, int size) {
            this.data  = data;
            this.total = total;
            this.page  = page;
            this.size  = size;
        }

        public List<ProductResponse> getData()  { return data; }
        public long                  getTotal() { return total; }
        public int                   getPage()  { return page; }
        public int                   getSize()  { return size; }
    }
}