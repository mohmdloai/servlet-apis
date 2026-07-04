package com.loai.inventory.api.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.api.mapper.PaymentTransactionMapper;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PaymentProvider;
import org.junit.jupiter.api.Test;

/**
 * The optional {@code provider} list-filter parser ({@code
 * stories/lookup_transaction_by_reference.md}): enum name or DB literal accepted, absent → no
 * filter, unknown → 400.
 */
class PaymentTransactionMapperTest {

  @Test
  void providerFilter_absentOrBlank_isNoFilter() {
    assertNull(PaymentTransactionMapper.toProviderFilter(null));
    assertNull(PaymentTransactionMapper.toProviderFilter("   "));
  }

  @Test
  void providerFilter_acceptsEnumNameAndDbLiteral() {
    assertEquals(
        PaymentProvider.INSTAPAY_MANUAL,
        PaymentTransactionMapper.toProviderFilter("INSTAPAY_MANUAL"));
    assertEquals(
        PaymentProvider.INSTAPAY_MANUAL,
        PaymentTransactionMapper.toProviderFilter("instapay_manual"));
  }

  @Test
  void providerFilter_unknownValue_is400() {
    assertThrows(
        ValidationException.class, () -> PaymentTransactionMapper.toProviderFilter("BOGUS"));
  }
}
