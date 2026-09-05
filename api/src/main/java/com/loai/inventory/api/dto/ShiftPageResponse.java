package com.loai.inventory.api.dto;

import com.loai.inventory.service.CashShiftService;
import java.util.List;

/** One page of shifts, newest first, with the total for the pager. */
public class ShiftPageResponse {

  private List<ShiftResponse> items;
  private long total;

  public static ShiftPageResponse from(CashShiftService.ShiftPage page) {
    ShiftPageResponse r = new ShiftPageResponse();
    r.items = page.items().stream().map(ShiftResponse::from).toList();
    r.total = page.total();
    return r;
  }

  public List<ShiftResponse> getItems() {
    return items;
  }

  public long getTotal() {
    return total;
  }
}
