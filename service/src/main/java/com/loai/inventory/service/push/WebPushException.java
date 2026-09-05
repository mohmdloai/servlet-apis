package com.loai.inventory.service.push;

/** A push-service rejection or transport fault while sending. Mirrors {@code WhatsAppException}. */
public class WebPushException extends RuntimeException {
  private final Integer status;

  public WebPushException(String message) {
    this(message, (Integer) null);
  }

  /**
   * @param status the push service's HTTP status, when one was received
   */
  public WebPushException(String message, Integer status) {
    super(message);
    this.status = status;
  }

  public WebPushException(String message, Throwable cause) {
    super(message, cause);
    this.status = null;
  }

  /** The push service's HTTP status behind this failure, or null for a transport fault. */
  public Integer status() {
    return status;
  }

  /**
   * A rejection that will never succeed on retry — the settle step marks the delivery FAILED at
   * once. {@code gone} is the push service's verdict on the <em>subscription</em> (404/410): the
   * device unsubscribed or the endpoint expired, so the settle step also deletes it.
   */
  public static final class TerminalWebPushException extends WebPushException {
    private final boolean gone;

    public TerminalWebPushException(String message, boolean gone) {
      this(message, gone, null);
    }

    public TerminalWebPushException(String message, boolean gone, Integer status) {
      super(message, status);
      this.gone = gone;
    }

    public boolean isGone() {
      return gone;
    }
  }
}
