package com.loai.inventory.service;

import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.domain.repository.FulfillmentRepository;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The order's two forward roll-ups, in one place: FULFILLING → FULFILLED once every line is
 * delivered, and FULFILLED → CLOSED once every live invoice is PAID.
 *
 * <p>They used to live inside the delivery path alone, which made <em>delivery</em> the only event
 * that could close an order. It is not: an invoice reissue re-runs {@code issueForFulfillment},
 * which can re-allocate prepayment and flip the replacement invoice PAID — the last thing an
 * already-FULFILLED order was waiting for. Without a roll-up on that path the order sat FULFILLED
 * forever with nothing left to deliver. Both callers run this inside their own transaction, so the
 * status they persist is the one the invoices in that transaction justify.
 *
 * <p>Every method persists only when it actually moved the order.
 */
final class OrderRollUp {

  private OrderRollUp() {}

  /**
   * The delivery path's roll-up: complete the order if this delivery was its last, then close it if
   * the money is fully settled. A partial delivery stays FULFILLING and nothing is written.
   */
  static void afterDelivery(
      DSLContext txDsl,
      UUID orgId,
      SalesOrder order,
      Map<UUID, SalesOrderLine> orderLines,
      FulfillmentRepository fulfillmentRepo,
      SalesOrderRepository orderRepo,
      InvoiceService invoiceService,
      OffsetDateTime now) {
    if (order.getStatus() != OrderStatus.FULFILLING) {
      return; // e.g. already terminal; nothing to roll up
    }
    Map<UUID, Integer> delivered = fulfillmentRepo.sumDeliveredQtyByOrderLine(order.getId());
    for (SalesOrderLine ol : orderLines.values()) {
      if (delivered.getOrDefault(ol.getId(), 0) < ol.getQuantity()) {
        return; // partial delivery: stays FULFILLING
      }
    }

    // FULFILLED at minimum, CLOSED as well when the money is already settled — one write either
    // way, since both transitions land on the same row.
    order.markFulfilled(now);
    closeIfFullyPaid(txDsl, orgId, order, invoiceService, now);
    orderRepo.updateFulfillmentState(order);
  }

  /**
   * The money-side roll-up on its own: a FULFILLED order whose live invoices are all PAID is
   * CLOSED. Callable by any path that can make an invoice PAID after the goods have shipped — today
   * the delivery path (above) and an invoice reissue. Returns whether the order moved; the caller
   * persists (the delivery path already does).
   */
  static boolean closeIfFullyPaid(
      DSLContext txDsl,
      UUID orgId,
      SalesOrder order,
      InvoiceService invoiceService,
      OffsetDateTime now) {
    if (order.getStatus() != OrderStatus.FULFILLED) {
      return false;
    }
    if (!invoiceService.allLiveInvoicesPaid(txDsl, orgId, order.getId())) {
      return false;
    }
    order.close(now);
    return true;
  }
}
