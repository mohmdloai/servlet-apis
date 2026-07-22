package com.loai.inventory.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.repository.CustomerRepository;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.service.email.EmailGate;
import com.loai.inventory.service.email.EmailMessage;
import com.loai.inventory.service.email.EmailSender;
import com.loai.inventory.service.email.MxResolver.MxResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code requestCode}'s story-87 behaviour: the MX-only silent skip (an undeliverable domain saves
 * the synchronous SMTP send with no observable change), and the deliberate blocklist bypass (a
 * disposable-email customer minted by lenient checkout must still receive a login code). Redis and
 * DB stay mocked — the OTP mechanism itself is covered by {@code PortalAuthIT}.
 */
class CustomerAuthServiceRequestCodeTest {

  private static final UUID ORG_ID = UUID.randomUUID();

  /** Captures sends; the service treats it as the raw SMTP boundary. */
  private static final class CapturingEmailSender implements EmailSender {
    final List<EmailMessage> captured = new ArrayList<>();

    @Override
    public void send(EmailMessage message) {
      captured.add(message);
    }
  }

  private final CustomerOtpStore otpStore = mock(CustomerOtpStore.class);
  private final OrgRepository orgRepository = mock(OrgRepository.class);
  private final CustomerRepository customerRepository = mock(CustomerRepository.class);
  private final CapturingEmailSender emailSender = new CapturingEmailSender();

  private CustomerAuthService serviceWith(EmailGate gate) {
    Org org = new Org();
    org.setId(ORG_ID);
    org.setActive(true);
    when(orgRepository.findBySlug("acme")).thenReturn(Optional.of(org));
    return new CustomerAuthService(
        null,
        ctx -> customerRepository,
        ctx -> orgRepository,
        otpStore,
        /* sessionStore= */ null,
        /* customerJwtUtil= */ null,
        emailSender,
        gate,
        /* perEmailSendLimit= */ 5,
        /* perEmailWindowSeconds= */ 60);
  }

  @Test
  void undeliverableDomain_skipsTheSendSilently() {
    CustomerAuthService service =
        serviceWith(new EmailGate(Set.of(), d -> MxResult.UNDELIVERABLE, true));

    service.requestCode("acme", "ghost@nosuchdomain.example");

    // Silent no-op: no lookup, no challenge, no SMTP — indistinguishable from the unknown-email
    // path, so the servlet's uniform {sent:true} stays oracle-free.
    verifyNoInteractions(customerRepository);
    verifyNoInteractions(otpStore);
    assertEquals(0, emailSender.captured.size());
  }

  @Test
  void disposableButDeliverableDomain_stillGetsItsCode() {
    // The blocklist is deliberately NOT consulted on the OTP path — this customer exists (lenient
    // checkout accepted them) and locking them out of their own orders would be a regression.
    CustomerAuthService service =
        serviceWith(new EmailGate(Set.of("mailinator.com"), d -> MxResult.DELIVERABLE, true));
    Customer customer = new Customer();
    customer.setId(UUID.randomUUID());
    customer.setEmail("shopper@mailinator.com");
    when(customerRepository.findByEmail(ORG_ID, "shopper@mailinator.com"))
        .thenReturn(Optional.of(customer));
    when(otpStore.allowSend(any(), anyString(), anyInt(), anyInt())).thenReturn(true);
    when(otpStore.issueCode(any(), anyString(), anyLong())).thenReturn("123456");

    service.requestCode("acme", "shopper@mailinator.com");

    assertEquals(1, emailSender.captured.size(), "the login code was sent");
  }
}
