package com.loai.inventory.api.dto;

import java.math.BigDecimal;

/**
 * Request body for POST /api/products
 *
 * Jackson deserializes the JSON body into this class.
 * Validation happens in ProductService, not here — this is just a data carrier.
 */
public class CreateProductRequest {

    private String     name;
    private String     description;
    private BigDecimal basePrice;
    private String     sku;

    public CreateProductRequest() {}

    public String     getName()        { return name; }
    public String     getDescription() { return description; }
    public BigDecimal getBasePrice()   { return basePrice; }
    public String     getSku()         { return sku; }

    public void setName(String name)                 { this.name = name; }
    public void setDescription(String description)   { this.description = description; }
    public void setBasePrice(BigDecimal basePrice)   { this.basePrice = basePrice; }
    public void setSku(String sku)                   { this.sku = sku; }
}