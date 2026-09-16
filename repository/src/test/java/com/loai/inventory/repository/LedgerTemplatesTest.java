package com.loai.inventory.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.ledger.LedgerChart;
import com.loai.inventory.repository.LedgerRepositoryImpl.Template;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The posting templates against the chart, without a database (stories/general_ledger.md §Tests):
 * every account a template names exists; every account on the chart is named by some template (an
 * account nothing posts to is a promise the ledger cannot keep); every template has both sides; and
 * the SQL rail-to-account {@code CASE} agrees with {@link LedgerChart#cashAccountFor} for every
 * provider, so a new rail cannot land in Java and not in SQL.
 */
class LedgerTemplatesTest {

  private static final Pattern CODE = Pattern.compile("'([0-9]{4})'");

  private static Set<String> codesIn(String sql) {
    Set<String> codes = new HashSet<>();
    Matcher m = CODE.matcher(sql);
    while (m.find()) {
      codes.add(m.group(1));
    }
    return codes;
  }

  @Test
  void everyCodeATemplateNamesIsOnTheChart() {
    for (Template t : LedgerRepositoryImpl.TEMPLATES) {
      for (String code : codesIn(t.legs() + " " + t.select())) {
        assertNotNull(LedgerChart.byCode(code), t.key() + " names unknown account " + code);
      }
    }
  }

  @Test
  void everyChartAccountIsNamedBySomeTemplate() {
    Set<String> named = new HashSet<>();
    for (Template t : LedgerRepositoryImpl.TEMPLATES) {
      named.addAll(codesIn(t.legs() + " " + t.select()));
    }
    for (LedgerChart.Account a : LedgerChart.ACCOUNTS) {
      assertTrue(named.contains(a.code()), "chart account " + a.code() + " is never posted to");
    }
  }

  @Test
  void everyTemplateHasADebitAndACreditLeg() {
    for (Template t : LedgerRepositoryImpl.TEMPLATES) {
      assertTrue(t.legs().contains("'DR'"), t.key() + " has no debit leg");
      assertTrue(t.legs().contains("'CR'"), t.key() + " has no credit leg");
      assertFalse(t.kind().isBlank() || t.event().isBlank(), t.key());
    }
  }

  /**
   * V102's sign-aware RESTOCK arm ({@code stories/supplier_goods_receipt.md}). The template takes
   * {@code abs(stock_delta)}, so a negative RESTOCK row — which a goods-receipt void writes, and
   * nothing could write before it — must swap the two accounts rather than book an increase:
   *
   * <pre>
   *   delta &gt; 0 → DR 1200 / CR 2000        delta &lt; 0 → DR 2000 / CR 1200
   * </pre>
   */
  @Test
  void restockArmIsSignAware_andBothDirectionsNameTheSameTwoAccounts() {
    Template stock =
        LedgerRepositoryImpl.TEMPLATES.stream()
            .filter(t -> t.key().equals("STOCK/MOVED"))
            .findFirst()
            .orElseThrow();
    assertTrue(
        stock
            .select()
            .contains(
                "WHEN 'RESTOCK' THEN CASE WHEN s.stock_delta < 0 THEN '2000'" + " ELSE '1200' END"),
        "the debit arm is not sign-aware");
    assertTrue(
        stock
            .select()
            .contains(
                "WHEN 'RESTOCK' THEN CASE WHEN s.stock_delta < 0 THEN '1200'" + " ELSE '2000' END"),
        "the credit arm is not sign-aware");
    assertNotNull(LedgerChart.byCode("1200"));
    assertNotNull(LedgerChart.byCode("2000"));
  }

  @Test
  void sqlRailMappingAgreesWithTheJavaChart_forEveryProvider() {
    String sql = LedgerRepositoryImpl.CASH_ACCOUNT_BY_PROVIDER;
    for (PaymentProvider p : PaymentProvider.values()) {
      String expected = LedgerChart.cashAccountFor(p);
      Pattern arm = Pattern.compile("WHEN '" + p.dbLiteral() + "' THEN '([0-9]{4})'");
      Matcher m = arm.matcher(sql);
      assertTrue(m.find(), "SQL CASE has no arm for provider " + p.dbLiteral());
      assertEquals(expected, m.group(1), "rail " + p + " lands on a different account in SQL");
      assertTrue(
          LedgerRepositoryImpl.KNOWN_PROVIDERS.contains("'" + p.dbLiteral() + "'"),
          "KNOWN_PROVIDERS is missing " + p.dbLiteral());
    }
  }

  @Test
  void templateKeysAreUnique() {
    Set<String> keys = new HashSet<>();
    for (Template t : LedgerRepositoryImpl.TEMPLATES) {
      assertTrue(keys.add(t.key()), "duplicate template " + t.key());
    }
  }
}
