package com.loai.inventory.api.dto;

import java.util.List;

/**
 * Generic paginated envelope for list endpoints.
 *
 * <p>JSON shape: { "data": [ ... ], "total": 42, "page": 0, "size": 10 }
 */
public class PageResponse<T> {

  private final List<T> data;
  private final long total;
  private final int page;
  private final int size;

  public PageResponse(List<T> data, long total, int page, int size) {
    this.data = data;
    this.total = total;
    this.page = page;
    this.size = size;
  }

  public List<T> getData() {
    return data;
  }

  public long getTotal() {
    return total;
  }

  public int getPage() {
    return page;
  }

  public int getSize() {
    return size;
  }
}
