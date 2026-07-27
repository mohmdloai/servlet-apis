package com.loai.inventory.service.platform;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.PlatformSearchType;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * <strong>The single definition of which probes a query fires, and of how its input is
 * normalized.</strong>
 *
 * <p>Both rules are stated once, as data, in the two tables below — {@link Shape} and {@link
 * Probe}. That is the {@code PlatformQueuePredicates} move applied to a different kind of rule: a
 * reviewer must be able to see which probes {@code SO-000001} fires without running it, and
 * scattered {@code if}s across a service and a repository cannot be read that way. {@code
 * PlatformSearchRepository} is deliberately given one method per probe rather than a {@code
 * search(String)} entry point, so this decision cannot quietly migrate down a layer.
 *
 * <h2>Classification — the shape of the query says what it can possibly match</h2>
 *
 * <table>
 *   <caption>Exactly one shape fires; the probes are its row</caption>
 *   <tr><th>query looks like</th><th>shape</th><th>probes</th></tr>
 *   <tr><td>contains {@code @}</td><td>{@link Shape#EMAIL}</td>
 *       <td>{@code app_user.email}, {@code customer.email}</td></tr>
 *   <tr><td>starts with {@code SO-}</td><td>{@link Shape#ORDER_NUMBER}</td>
 *       <td>{@code sales_order.order_number}</td></tr>
 *   <tr><td>anything else</td><td>{@link Shape#OTHER}</td>
 *       <td>{@code org.slug}/{@code org.name}, {@code payment_transaction.provider_ref}</td></tr>
 * </table>
 *
 * <p><strong>{@code SO-} is a prefix test, not a format regex</strong>, case-insensitive and
 * applied <em>after</em> digit folding. Two order-number shapes are live: the allocator mints
 * {@code String.format("SO-%d-%05d", …)} → {@code SO-2026-00042}, while {@code perfdb}'s seeded
 * corpus is {@code SO-000001} — six digits, no year. A strict {@code ^SO-\d{4}-\d{5}$} would
 * silently drop half the order numbers that exist. The probe is an equality match either way, so a
 * loose classifier costs one wasted index lookup and a strict one costs a missing answer.
 *
 * <p><strong>Known consequence of testing {@code @} first:</strong> a {@code provider_ref} that
 * happens to contain an {@code @} classifies as {@link Shape#EMAIL} and therefore does not fire the
 * reference probe. Bank references are overwhelmingly alphanumeric, so this is the cheaper mistake
 * — but it is a real one, pinned by {@code PlatformSearchServiceTest} rather than left to be
 * discovered. Revisit by widening {@link Shape#EMAIL} to also probe {@code provider_ref} if an
 * operator ever hits it.
 *
 * <h2>Normalization — the same function that wrote the column</h2>
 *
 * <p>Lower-casing is not enough, and this is an Egypt-first product. Every column probed here was
 * written through {@code common/.../text/Text}, so the query goes through the same function or the
 * equality misses on input a real operator will really paste:
 *
 * <table>
 *   <caption>Per-probe normalizers</caption>
 *   <tr><th>probe</th><th>normalizer</th><th>why</th></tr>
 *   <tr><td>{@code app_user.email}, {@code customer.email}</td><td>{@link Text#normalizeEmail}</td>
 *       <td>what {@code AccountService}/{@code SalesOrderService}/{@code MemberService} wrote, and
 *           what V62 backfilled the rest to</td></tr>
 *   <tr><td>{@code payment_transaction.provider_ref}</td><td>{@link Text#normalizeNumeric}</td>
 *       <td>what {@code PaymentTransactionService} writes: <strong>Arabic-Indic and Persian digits
 *           fold to ASCII</strong>. A reference pasted out of an Arabic-locale bank SMS misses
 *           without this</td></tr>
 *   <tr><td>{@code sales_order.order_number}</td><td>{@link Text#normalizeNumeric}, then upper</td>
 *       <td>same digit fold; upper-cased because the number is always minted upper and a customer
 *           quoting it in an email often is not</td></tr>
 *   <tr><td>{@code org.slug}, {@code org.name}</td><td>none here — folded in SQL</td>
 *       <td>{@code fold_search} is called on <em>both sides</em> in the repository, the {@code
 *           ProductRepositoryImpl.searchCondition} precedent, so the stored key and the query fold
 *           cannot drift</td></tr>
 * </table>
 *
 * <p><strong>The order-number upper-casing deliberately diverges</strong> from the org-plane {@code
 * GET /api/orgs/{orgId}/sales-orders?order_number=} lookup, which is case-sensitive. That lookup is
 * a merchant checking their own record before committing money; this one is an operator retyping
 * what a customer emailed. Noted here rather than left to be found.
 *
 * <h2>Length</h2>
 *
 * <p>Minimum {@value #MIN_LENGTH} characters after trim and normalization, else a 400 naming it. A
 * blank or missing {@code q} is also a 400, not an empty result set — the caller made a mistake.
 *
 * <p><strong>Two is sufficient only because the fuzzy customer-name probe was cut.</strong> Every
 * remaining probe is an equality match or a 200-row scan. Do not re-add a trigram probe under this
 * limit: pg_trgm extracts no trigram below three characters, so a 2-char {@code LIKE '%ab%'} still
 * <em>chooses</em> the GIN index and then rechecks the entire table through it — 1706 ms over
 * {@code perfdb}'s 200,000 customers, against 0.045 ms at three. A minimum is not a rate limit
 * unless it clears the index's own floor.
 */
public final class PlatformSearchQuery {

  /** Characters required after trim and normalization. See the class Javadoc for why 2 holds. */
  public static final int MIN_LENGTH = 2;

  /** Results per group. No pagination — this is a jump-to affordance, not a worklist. */
  public static final int GROUP_CAP = 5;

  /** The three mutually exclusive buckets a query falls into. Exactly one fires. */
  public enum Shape {
    /** Contains {@code @}. */
    EMAIL,
    /** Starts with {@code SO-}, case-insensitively, after digit folding. */
    ORDER_NUMBER,
    /** Everything else. */
    OTHER
  }

  /** How a term is canonicalized before it is compared to a stored column. */
  private enum Normalizer {
    EMAIL(Text::normalizeEmail),
    /** The digit fold, then upper — order numbers are minted upper. */
    ORDER_NUMBER(raw -> upper(Text.normalizeNumeric(raw))),
    NUMERIC(Text::normalizeNumeric),
    /** Passed through; the fold happens in SQL on both sides. */
    RAW(UnaryOperator.identity());

    private final UnaryOperator<String> fn;

    Normalizer(UnaryOperator<String> fn) {
      this.fn = fn;
    }

    String apply(String raw) {
      return fn.apply(raw);
    }
  }

  /** One row of the table: a type, the shape that fires it, and how its term is normalized. */
  private record Probe(PlatformSearchType type, Shape shape, Normalizer normalizer) {}

  /**
   * <strong>The table.</strong> Declared in {@link PlatformSearchType} order, which is therefore
   * also the order groups appear on the wire.
   */
  private static final List<Probe> PROBES =
      List.of(
          new Probe(PlatformSearchType.ORG, Shape.OTHER, Normalizer.RAW),
          new Probe(PlatformSearchType.APP_USER, Shape.EMAIL, Normalizer.EMAIL),
          new Probe(PlatformSearchType.CUSTOMER, Shape.EMAIL, Normalizer.EMAIL),
          new Probe(PlatformSearchType.SALES_ORDER, Shape.ORDER_NUMBER, Normalizer.ORDER_NUMBER),
          new Probe(PlatformSearchType.PAYMENT_TRANSACTION, Shape.OTHER, Normalizer.NUMERIC));

  private static final String ORDER_NUMBER_PREFIX = "SO-";

  private final String query;
  private final Shape shape;
  private final List<Term> terms;

  /** One probe resolved against this query: which repository method to call, and with what. */
  public record Term(PlatformSearchType type, String value) {}

  private PlatformSearchQuery(String query, Shape shape, List<Term> terms) {
    this.query = query;
    this.shape = shape;
    this.terms = terms;
  }

  /**
   * Validate, classify and normalize {@code raw}, or throw the 400.
   *
   * @throws ValidationException blank/missing {@code q}, or shorter than {@value #MIN_LENGTH} after
   *     normalization.
   */
  public static PlatformSearchQuery of(String raw) {
    String cleaned = Text.normalizeText(raw);
    if (cleaned == null) {
      throw new ValidationException("Parameter 'q' is required");
    }
    if (cleaned.length() < MIN_LENGTH) {
      throw new ValidationException("Parameter 'q' must be at least " + MIN_LENGTH + " characters");
    }

    Shape shape = classify(cleaned);
    List<Term> terms =
        PROBES.stream()
            .filter(p -> p.shape() == shape)
            .map(p -> new Term(p.type(), p.normalizer().apply(cleaned)))
            // A normalizer maps blank to null; such a probe has nothing to compare and is dropped
            // rather than run against null, which would silently match nothing under `= NULL`.
            .filter(t -> t.value() != null && !t.value().isBlank())
            .toList();

    return new PlatformSearchQuery(cleaned, shape, terms);
  }

  /**
   * The classifier, stated once. Order matters: {@code @} is tested before the {@code SO-} prefix,
   * so {@code SO-1@x} is an email query — see the class Javadoc for that consequence.
   */
  private static Shape classify(String cleaned) {
    if (cleaned.indexOf('@') >= 0) {
      return Shape.EMAIL;
    }
    String folded = upper(Text.normalizeNumeric(cleaned));
    if (folded != null && folded.startsWith(ORDER_NUMBER_PREFIX)) {
      return Shape.ORDER_NUMBER;
    }
    return Shape.OTHER;
  }

  private static String upper(String s) {
    return s == null ? null : s.toUpperCase(Locale.ROOT);
  }

  /** The normalized query, echoed back on the response so a client can confirm what ran. */
  public String query() {
    return query;
  }

  /** Which bucket this query fell into. Exposed for the unit test, not for branching on. */
  public Shape shape() {
    return shape;
  }

  /** The probes to run, in wire order. Never empty for a query that passed validation. */
  public List<Term> terms() {
    return terms;
  }

  /** The normalized term for {@code type}, or {@code null} when this query does not fire it. */
  public String termFor(PlatformSearchType type) {
    return terms.stream().filter(t -> t.type() == type).map(Term::value).findFirst().orElse(null);
  }

  /** Every type this classifier can ever fire — a completeness check for the table above. */
  public static List<PlatformSearchType> coveredTypes() {
    return Arrays.stream(PlatformSearchType.values())
        .filter(t -> PROBES.stream().anyMatch(p -> p.type() == t))
        .toList();
  }
}
