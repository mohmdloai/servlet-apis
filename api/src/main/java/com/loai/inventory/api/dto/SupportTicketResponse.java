package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.DeskTicketRow;
import com.loai.inventory.domain.model.PlatformQueueOrg;
import com.loai.inventory.domain.model.SupportTicket;
import com.loai.inventory.domain.model.TicketRef;
import com.loai.inventory.service.SupportTicketService;
import com.loai.inventory.service.platform.SupportDeskService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A support ticket on the wire ({@code stories/support_tickets.md}). One class, four shapes, with
 * Jackson's null omission doing the narrowing:
 *
 * <ul>
 *   <li>the org-plane <em>summary</em> (list rows) — no {@code messages}, no {@code org}, no email;
 *   <li>the org-plane <em>view</em> — {@code messages} present, {@code NOTE} rows never;
 *   <li>the desk <em>row</em> — the summary plus {@code org};
 *   <li>the desk <em>view</em> — everything, {@code opened_by.email} included.
 * </ul>
 */
public class SupportTicketResponse {

  private UUID id;
  private Long number;
  private String status;
  private String category;
  private boolean blocking;
  private String subject;
  private Ref ref;
  private Person openedBy;
  private OffsetDateTime openedAt;
  private OffsetDateTime statusSince;
  private OffsetDateTime lastActivityAt;
  private int attachmentCount;
  private String lastMessagePreview;
  private OffsetDateTime resolvedAt;
  private OffsetDateTime closedAt;
  private String closedReason;
  private List<Message> messages;
  private Org org;

  public static SupportTicketResponse summary(SupportTicketService.TicketSummaryView v) {
    SupportTicketResponse r = base(v.ticket());
    r.openedBy = Person.from(v.openedBy(), false);
    r.attachmentCount = v.attachmentCount();
    r.lastMessagePreview = v.lastMessagePreview();
    return r;
  }

  public static SupportTicketResponse view(SupportTicketService.TicketView v) {
    return view(v, false);
  }

  private static SupportTicketResponse view(SupportTicketService.TicketView v, boolean withEmail) {
    SupportTicketResponse r = base(v.ticket());
    r.openedBy = Person.from(v.openedBy(), withEmail);
    r.attachmentCount = v.attachmentCount();
    r.lastMessagePreview = v.lastMessagePreview();
    r.messages = v.messages().stream().map(Message::from).toList();
    return r;
  }

  public static SupportTicketResponse deskRow(DeskTicketRow row) {
    SupportTicketResponse r = new SupportTicketResponse();
    r.id = row.id();
    r.number = row.number();
    r.status = row.status().name();
    r.category = row.category().name();
    r.blocking = row.blocking();
    r.subject = row.subject();
    r.ref = Ref.from(row.ref());
    r.openedBy = new Person(row.openedById(), row.openedByName(), null);
    r.openedAt = row.openedAt();
    r.statusSince = row.statusSince();
    r.lastActivityAt = row.lastActivityAt();
    r.attachmentCount = row.attachmentCount();
    r.lastMessagePreview = row.lastMessagePreview();
    r.org = Org.from(row.org());
    return r;
  }

  public static SupportTicketResponse deskView(SupportDeskService.DeskView d) {
    SupportTicketResponse r = view(d.view(), true);
    r.org = Org.from(d.org());
    return r;
  }

  private static SupportTicketResponse base(SupportTicket t) {
    SupportTicketResponse r = new SupportTicketResponse();
    r.id = t.getId();
    r.number = t.getNumber();
    r.status = t.getStatus().name();
    r.category = t.getCategory().name();
    r.blocking = t.isBlocking();
    r.subject = t.getSubject();
    r.ref = Ref.from(t.getRef());
    r.openedAt = t.getOpenedAt();
    r.statusSince = t.getStatusSince();
    r.lastActivityAt = t.getLastActivityAt();
    r.resolvedAt = t.getResolvedAt();
    r.closedAt = t.getClosedAt();
    r.closedReason = t.getClosedReason() == null ? null : t.getClosedReason().name();
    return r;
  }

  public record Person(UUID id, String displayName, String email) {
    static Person from(SupportTicketService.Person p, boolean withEmail) {
      return p == null ? null : new Person(p.id(), p.displayName(), withEmail ? p.email() : null);
    }
  }

  public record Ref(String type, UUID id, String label) {
    static Ref from(TicketRef ref) {
      return ref == null ? null : new Ref(ref.type().name(), ref.id(), ref.label());
    }
  }

  public record Org(UUID id, String name, String slug, String status) {
    static Org from(PlatformQueueOrg o) {
      return o == null ? null : new Org(o.id(), o.name(), o.slug(), o.status().wire());
    }
  }

  public record Attachment(UUID id, String fileName, String contentType, String url) {
    static Attachment from(SupportTicketService.AttachmentView a) {
      return new Attachment(
          a.attachment().id(), a.attachment().fileName(), a.attachment().contentType(), a.url());
    }
  }

  public record Message(
      UUID id,
      String kind,
      String side,
      Person author,
      String body,
      String statusTo,
      List<Attachment> attachments,
      OffsetDateTime createdAt) {
    static Message from(SupportTicketService.MessageView m) {
      return new Message(
          m.message().id(),
          m.message().kind().name(),
          m.message().side().name(),
          Person.from(m.author(), false),
          m.message().body(),
          m.message().statusTo() == null ? null : m.message().statusTo().name(),
          m.attachments().stream().map(Attachment::from).toList(),
          m.message().createdAt());
    }
  }

  public UUID getId() {
    return id;
  }

  public Long getNumber() {
    return number;
  }

  public String getStatus() {
    return status;
  }

  public String getCategory() {
    return category;
  }

  public boolean isBlocking() {
    return blocking;
  }

  public String getSubject() {
    return subject;
  }

  public Ref getRef() {
    return ref;
  }

  public Person getOpenedBy() {
    return openedBy;
  }

  public OffsetDateTime getOpenedAt() {
    return openedAt;
  }

  public OffsetDateTime getStatusSince() {
    return statusSince;
  }

  public OffsetDateTime getLastActivityAt() {
    return lastActivityAt;
  }

  public int getAttachmentCount() {
    return attachmentCount;
  }

  public String getLastMessagePreview() {
    return lastMessagePreview;
  }

  public OffsetDateTime getResolvedAt() {
    return resolvedAt;
  }

  public OffsetDateTime getClosedAt() {
    return closedAt;
  }

  public String getClosedReason() {
    return closedReason;
  }

  public List<Message> getMessages() {
    return messages;
  }

  public Org getOrg() {
    return org;
  }
}
