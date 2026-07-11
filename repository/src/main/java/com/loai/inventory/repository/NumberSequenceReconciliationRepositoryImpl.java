package com.loai.inventory.repository;

import com.loai.inventory.domain.repository.NumberSequenceReconciliationRepository;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Postgres implementation of the number-sequence heal. Both the detection and the realign parse the
 * trailing {@code %04d} suffix out of the {@code …-YYYY-NNNN} number with {@code split_part(…, '-',
 * n)::int}; the year is part 2 and the sequence is part 3 of the three-segment number. The
 * identifier fragments woven into these statements are compile-time constants, never caller input.
 */
public final class NumberSequenceReconciliationRepositoryImpl
    implements NumberSequenceReconciliationRepository {

  private final DSLContext dsl;

  public NumberSequenceReconciliationRepositoryImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<CounterDrift> detectInvoiceDrift() {
    return detectDrift("sales_invoice", "invoice_number", "invoice_number_counter");
  }

  @Override
  public List<CounterDrift> detectCreditNoteDrift() {
    return detectDrift("credit_note", "credit_note_number", "credit_note_number_counter");
  }

  @Override
  public void realignInvoiceCounters() {
    realign("sales_invoice", "invoice_number", "invoice_number_counter");
  }

  @Override
  public void realignCreditNoteCounters() {
    realign("credit_note", "credit_note_number", "credit_note_number_counter");
  }

  private List<CounterDrift> detectDrift(String docTable, String numberCol, String counterTable) {
    String sql =
        "SELECT d.org_id AS org_id,"
            + " split_part(d."
            + numberCol
            + ", '-', 2)::int AS yr,"
            + " c.next_val AS current_next_val,"
            + " max(split_part(d."
            + numberCol
            + ", '-', 3)::int) + 1 AS required_next_val"
            + " FROM "
            + docTable
            + " d"
            + " LEFT JOIN "
            + counterTable
            + " c"
            + "   ON c.org_id = d.org_id"
            + "  AND c.year = split_part(d."
            + numberCol
            + ", '-', 2)::int"
            + " GROUP BY d.org_id, split_part(d."
            + numberCol
            + ", '-', 2)::int, c.next_val"
            + " HAVING c.next_val IS NULL"
            + "     OR c.next_val < max(split_part(d."
            + numberCol
            + ", '-', 3)::int) + 1";
    return dsl.fetch(sql)
        .map(
            r ->
                new CounterDrift(
                    r.get("org_id", UUID.class),
                    r.get("yr", Integer.class),
                    r.get("current_next_val", Long.class),
                    r.get("required_next_val", Long.class)));
  }

  private void realign(String docTable, String numberCol, String counterTable) {
    // Forward-only: GREATEST(existing, MAX+1) never lowers an already-ahead counter, and the
    // ON CONFLICT branch creates the row when it is entirely absent. One statement per document
    // type covers every (org_id, year) at once.
    String sql =
        "INSERT INTO "
            + counterTable
            + " (org_id, year, next_val)"
            + " SELECT org_id,"
            + " split_part("
            + numberCol
            + ", '-', 2)::int,"
            + " max(split_part("
            + numberCol
            + ", '-', 3)::int) + 1"
            + " FROM "
            + docTable
            + " GROUP BY org_id, split_part("
            + numberCol
            + ", '-', 2)::int"
            + " ON CONFLICT (org_id, year)"
            + " DO UPDATE SET next_val = GREATEST("
            + counterTable
            + ".next_val, EXCLUDED.next_val)";
    dsl.execute(sql);
  }
}
