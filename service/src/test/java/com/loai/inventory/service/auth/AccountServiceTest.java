package com.loai.inventory.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.service.email.EmailGate;
import com.loai.inventory.service.email.MxResolver.MxResult;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link AccountService#register}'s strict email-quality gate (story 87): a throwaway or provably
 * undeliverable address is a cause-naming 400 <em>before</em> any transaction is opened — the
 * collaborators are deliberately null here, proving the rejection happens at the gate.
 */
class AccountServiceTest {

  private static AccountService serviceWith(EmailGate gate) {
    return new AccountService(null, null, null, null, null, null, gate);
  }

  @Test
  void register_rejectsDisposableDomains_causeNaming() {
    AccountService service =
        serviceWith(new EmailGate(Set.of("mailinator.com"), d -> MxResult.DELIVERABLE, true));
    ValidationException e =
        assertThrows(
            ValidationException.class,
            () -> service.register("spam@mailinator.com", "password123", null));
    assertEquals("disposable email addresses are not accepted", e.getMessage());
  }

  @Test
  void register_rejectsUndeliverableDomains_causeNaming() {
    AccountService service =
        serviceWith(new EmailGate(Set.of(), d -> MxResult.UNDELIVERABLE, true));
    ValidationException e =
        assertThrows(
            ValidationException.class,
            () -> service.register("x@nosuchdomain.example", "password123", null));
    assertEquals("email domain cannot receive mail", e.getMessage());
  }

  @Test
  void register_unknownMxAnswer_passesTheGate_failOpen() {
    // UNKNOWN must pass — with null collaborators the flow then NPEs past the gate, which is
    // exactly the proof the gate did not reject.
    AccountService service = serviceWith(new EmailGate(Set.of(), d -> MxResult.UNKNOWN, true));
    assertThrows(
        NullPointerException.class,
        () -> service.register("x@flaky-dns.example", "password123", null));
  }
}
