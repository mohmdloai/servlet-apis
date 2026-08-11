package com.loai.inventory.service.whatsapp;

/** A provider rejection or transport fault while sending. Mirrors {@code EmailException}. */
public class WhatsAppException extends RuntimeException {
  public WhatsAppException(String message) {
    super(message);
  }

  public WhatsAppException(String message, Throwable cause) {
    super(message, cause);
  }
}
