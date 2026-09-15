package com.loai.inventory.domain.model.ledger;

import com.loai.inventory.domain.model.PaymentProvider;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The standard chart of accounts (stories/general_ledger.md §Chart) — the ONE list every org's
 * {@code ledger_account} rows are materialised from and every posting template names its legs by.
 * Codes are stable identifiers (the frontend localises the name by code); {@code name} is the
 * English fallback stored on the row.
 *
 * <p>Deliberately small: every account here is one a posting template writes to today. An account
 * nothing posts to is a promise the ledger cannot keep, so none is listed.
 */
public final class LedgerChart {

  /** One standard account. {@code normalSide} is the side a positive balance sits on. */
  public record Account(String code, String name, AccountType type, Side normalSide) {}

  public static final String CASH = "1000";
  public static final String BANK = "1010";
  public static final String CARD_RECEIVABLE = "1020";
  public static final String ACCOUNTS_RECEIVABLE = "1100";
  public static final String INVENTORY = "1200";
  public static final String PURCHASES_UNBILLED = "2000";
  public static final String CUSTOMER_DEPOSITS = "2100";
  public static final String VAT_PAYABLE = "2200";
  public static final String OWNER_CONTRIBUTIONS = "3000";
  public static final String OWNER_DRAWINGS = "3100";
  public static final String SALES_REVENUE = "4000";
  public static final String SALES_DISCOUNTS = "4050";
  public static final String SHIPPING_REVENUE = "4100";
  public static final String SALES_RETURNS = "4200";
  public static final String COST_OF_GOODS_SOLD = "5000";
  public static final String INVENTORY_ADJUSTMENTS = "5100";
  public static final String CASH_OVER_SHORT = "5200";

  public static final List<Account> ACCOUNTS =
      List.of(
          new Account(CASH, "Cash on hand", AccountType.ASSET, Side.DR),
          new Account(BANK, "Bank & InstaPay", AccountType.ASSET, Side.DR),
          new Account(CARD_RECEIVABLE, "Card processor receivable", AccountType.ASSET, Side.DR),
          new Account(ACCOUNTS_RECEIVABLE, "Accounts receivable", AccountType.ASSET, Side.DR),
          new Account(INVENTORY, "Inventory", AccountType.ASSET, Side.DR),
          new Account(
              PURCHASES_UNBILLED, "Supplier purchases (unbilled)", AccountType.LIABILITY, Side.CR),
          new Account(CUSTOMER_DEPOSITS, "Customer deposits", AccountType.LIABILITY, Side.CR),
          new Account(VAT_PAYABLE, "VAT payable", AccountType.LIABILITY, Side.CR),
          new Account(OWNER_CONTRIBUTIONS, "Owner contributions", AccountType.EQUITY, Side.CR),
          new Account(
              OWNER_DRAWINGS, "Owner drawings & till pay-outs", AccountType.EQUITY, Side.DR),
          new Account(SALES_REVENUE, "Sales revenue", AccountType.REVENUE, Side.CR),
          new Account(SALES_DISCOUNTS, "Sales discounts", AccountType.REVENUE, Side.DR),
          new Account(SHIPPING_REVENUE, "Shipping revenue", AccountType.REVENUE, Side.CR),
          new Account(SALES_RETURNS, "Sales returns", AccountType.REVENUE, Side.DR),
          new Account(COST_OF_GOODS_SOLD, "Cost of goods sold", AccountType.EXPENSE, Side.DR),
          new Account(
              INVENTORY_ADJUSTMENTS,
              "Inventory shrinkage & adjustments",
              AccountType.EXPENSE,
              Side.DR),
          new Account(CASH_OVER_SHORT, "Cash over / short", AccountType.EXPENSE, Side.DR));

  private static final Map<String, Account> BY_CODE =
      ACCOUNTS.stream().collect(Collectors.toUnmodifiableMap(Account::code, Function.identity()));

  private LedgerChart() {}

  /** The account a code names, or {@code null} for a code not in the chart. */
  public static Account byCode(String code) {
    return code == null ? null : BY_CODE.get(code);
  }

  /**
   * The asset account money on a rail lands in: the drawer for cash, the bank for either InstaPay
   * flavour, the processor receivable for a card (Paymob settles to the bank later; that transfer
   * is not modelled, so the balance shows what the processor holds). Mirrored by the SQL {@code
   * CASE} in {@code LedgerRepositoryImpl.CASH_ACCOUNT_BY_PROVIDER}; a new provider must be added to
   * both — the unit test pins the pair.
   */
  public static String cashAccountFor(PaymentProvider provider) {
    return switch (provider) {
      case CASH -> CASH;
      case INSTAPAY_MANUAL, INSTAPAY_IN_STORE -> BANK;
      case PAYMOB_CARD -> CARD_RECEIVABLE;
    };
  }
}
