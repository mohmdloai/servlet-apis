package com.loai.inventory.domain.model;

/** The kind of record a ticket says it is about — a frozen pointer, never a foreign key. */
public enum TicketRefType {
  ORDER,
  INVOICE,
  TRANSACTION,
  PRODUCT
}
