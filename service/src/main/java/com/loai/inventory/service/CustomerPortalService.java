package com.loai.inventory.service;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.repository.CustomerRepository;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * The logged-in customer's self-service profile ({@code GET|PATCH /api/portal/me}). Every read and
 * write is scoped to the session's own {@code (orgId, customerId)} — supplied by the {@code
 * CustomerAuthFilter} from the token, never from the URL or body — so a customer can only ever see
 * or change their own record, and never reach another org (epic decision #4). Email is deliberately
 * <em>not</em> patchable here: changing it would need a fresh ownership proof (a later slice). See
 * {@code stories/portal_auth_core.md}.
 */
public class CustomerPortalService {

  private static final int MAX_FIELD_LENGTH = 500;

  private final DSLContext rootDsl;
  private final CustomerRepositoryFactory customerRepositoryFactory;

  public CustomerPortalService(
      DSLContext rootDsl, CustomerRepositoryFactory customerRepositoryFactory) {
    this.rootDsl = rootDsl;
    this.customerRepositoryFactory = customerRepositoryFactory;
  }

  /** The patch body — any {@code null} field is left unchanged (merge). Email is not accepted. */
  public record ProfileUpdate(String name, String phone, String address) {}

  public Customer me(UUID orgId, UUID customerId) {
    return customerRepositoryFactory
        .create(rootDsl)
        .findById(orgId, customerId)
        .orElseThrow(() -> new NotFoundException("Customer", customerId));
  }

  /** Merge-update the caller's own name/phone/address; returns the updated profile. */
  public Customer updateProfile(UUID orgId, UUID customerId, ProfileUpdate update) {
    validate(update);
    return rootDsl.transactionResult(
        cfg -> {
          CustomerRepository repo = customerRepositoryFactory.create(DSL.using(cfg));
          Customer existing =
              repo.findById(orgId, customerId)
                  .orElseThrow(() -> new NotFoundException("Customer", customerId));
          if (update.name() != null) {
            existing.setName(trimToNull(update.name()));
          }
          if (update.phone() != null) {
            existing.setPhone(trimToNull(update.phone()));
          }
          if (update.address() != null) {
            existing.setAddress(trimToNull(update.address()));
          }
          // update() rewrites email too — but we pass the existing email untouched, so it is a
          // no-op
          // for email (the endpoint cannot change it).
          return repo.update(existing);
        });
  }

  private static void validate(ProfileUpdate update) {
    checkLength("name", update.name());
    checkLength("phone", update.phone());
    checkLength("address", update.address());
  }

  private static void checkLength(String field, String value) {
    if (value != null && value.length() > MAX_FIELD_LENGTH) {
      throw new ValidationException(field + " must be at most " + MAX_FIELD_LENGTH + " characters");
    }
  }

  private static String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
