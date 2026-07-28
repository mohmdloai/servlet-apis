package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Customer;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The org plane's customer record. Until {@code stories/org_customer_reads.md} this carried an
 * email and two timestamps while the model had {@code name}, {@code phone}, {@code address} and
 * {@code email_verified_at} — anonymous checkout wrote them, the portal's {@code PATCH
 * /api/portal/me} edited them, the reviews worklist displayed customer names from its own join, and
 * the customer's own record answered with none of it. A directory built on that DTO would have been
 * a list of email addresses.
 *
 * <p><b>This is the org plane, and that is why the enrichment is fine.</b> Staff already see
 * customer name and email on the reviews worklist, the comments worklist and every invoice's frozen
 * {@code customer_name}; the CRM record is theirs.
 *
 * <p><b>It is emphatically not a precedent for the cross-org DTO.</b> {@code PlatformSearchResult}
 * still carries no customer name, phone or address at all — pinned by {@code
 * PlatformSearchIT.results_carryNoCustomerPii} and re-asserted from this branch by {@code
 * CustomerReadsIT.platformSearchStillCarriesNoCustomerPii} — because that one is <em>cross-org</em>
 * and read by platform operators. Two different planes, two different rules. If you are here
 * because you noticed one customer DTO carrying an address and another refusing to, that
 * inconsistency is the design; please do not "fix" it by sharing a mapper.
 *
 * <p>Nulls are omitted, as everywhere: a customer created by {@code POST /customers} has only an
 * email, and its absent name must read as absent rather than as an empty string.
 */
public class CustomerResponse {
  private UUID id;
  private UUID orgId;
  private String email;
  private String name;
  private String phone;
  private String address;
  private OffsetDateTime emailVerifiedAt;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;

  private CustomerResponse() {}

  public static CustomerResponse from(Customer c) {
    CustomerResponse r = new CustomerResponse();
    r.id = c.getId();
    r.orgId = c.getOrgId();
    r.email = c.getEmail();
    r.name = c.getName();
    r.phone = c.getPhone();
    r.address = c.getAddress();
    r.emailVerifiedAt = c.getEmailVerifiedAt();
    r.createdAt = c.getCreatedAt();
    r.updatedAt = c.getUpdatedAt();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public String getEmail() {
    return email;
  }

  public String getName() {
    return name;
  }

  public String getPhone() {
    return phone;
  }

  public String getAddress() {
    return address;
  }

  public OffsetDateTime getEmailVerifiedAt() {
    return emailVerifiedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
