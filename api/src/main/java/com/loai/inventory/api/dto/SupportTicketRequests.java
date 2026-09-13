package com.loai.inventory.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * The write bodies of {@code /support-tickets} and {@code /api/admin/tickets} ({@code
 * stories/support_tickets.md}).
 */
public final class SupportTicketRequests {

  private SupportTicketRequests() {}

  /** {@code POST /support-tickets}. */
  public static class Open {
    private String category;
    private String subject;
    private String body;
    private Boolean blocking;
    private Ref ref;
    private List<Attachment> attachments;

    public String getCategory() {
      return category;
    }

    public void setCategory(String category) {
      this.category = category;
    }

    public String getSubject() {
      return subject;
    }

    public void setSubject(String subject) {
      this.subject = subject;
    }

    public String getBody() {
      return body;
    }

    public void setBody(String body) {
      this.body = body;
    }

    public Boolean getBlocking() {
      return blocking;
    }

    public void setBlocking(Boolean blocking) {
      this.blocking = blocking;
    }

    public Ref getRef() {
      return ref;
    }

    public void setRef(Ref ref) {
      this.ref = ref;
    }

    public List<Attachment> getAttachments() {
      return attachments;
    }

    public void setAttachments(List<Attachment> attachments) {
      this.attachments = attachments;
    }
  }

  /** {@code POST /{id}/messages} on both planes; {@code resolve} is read only by the desk. */
  public static class Message {
    private String body;
    private List<Attachment> attachments;
    private Boolean resolve;

    public String getBody() {
      return body;
    }

    public void setBody(String body) {
      this.body = body;
    }

    public List<Attachment> getAttachments() {
      return attachments;
    }

    public void setAttachments(List<Attachment> attachments) {
      this.attachments = attachments;
    }

    public Boolean getResolve() {
      return resolve;
    }

    public void setResolve(Boolean resolve) {
      this.resolve = resolve;
    }
  }

  /** {@code POST …/attachments/presign}. */
  public static class Presign {
    private String filename;
    private String contentType;

    public String getFilename() {
      return filename;
    }

    public void setFilename(String filename) {
      this.filename = filename;
    }

    public String getContentType() {
      return contentType;
    }

    public void setContentType(String contentType) {
      this.contentType = contentType;
    }
  }

  /** A screenshot the client already PUT to storage. */
  public static class Attachment {
    private String objectKey;
    private String contentType;
    private String fileName;

    public String getObjectKey() {
      return objectKey;
    }

    public void setObjectKey(String objectKey) {
      this.objectKey = objectKey;
    }

    public String getContentType() {
      return contentType;
    }

    public void setContentType(String contentType) {
      this.contentType = contentType;
    }

    public String getFileName() {
      return fileName;
    }

    public void setFileName(String fileName) {
      this.fileName = fileName;
    }
  }

  /** "About: SO-2026-00042" — a frozen pointer at a record. */
  public static class Ref {
    private String type;
    private UUID id;
    private String label;

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public UUID getId() {
      return id;
    }

    public void setId(UUID id) {
      this.id = id;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }
  }
}
