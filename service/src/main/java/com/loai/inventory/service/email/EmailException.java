package com.loai.inventory.service.email;

/**
 * A transient or permanent failure to hand a message to the mail provider. The delivery sweeper
 * catches this to decide retry vs. terminal {@code FAILED}; it is unchecked so non-email code paths
 * need not declare it.
 */
public class EmailException extends RuntimeException {
  public EmailException(String message, Throwable cause) {
    super(message, cause);
  }
}
