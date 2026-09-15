package com.loai.inventory.api.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.Supplier;
import com.loai.inventory.service.SupplierService.SupplierEdit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The supplier directory ({@code stories/supplier_goods_receipt.md}): one supplier per FOLDED name,
 * {@code ?q=} over name and email but never phone, {@code name ASC} under ICU, the active flag, and
 * a delete that refuses while receipts reference it.
 */
@Testcontainers
class SupplierIT extends InboundItBase {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  @BeforeAll
  static void startInfra() {
    wire(PG);
  }

  @Test
  void duplicateFoldedName_is409_namingTheExisting() {
    UUID org = createOrg("acme");
    suppliers.create(org, named("أحمد للورق"));

    ConflictException e =
        assertThrows(ConflictException.class, () -> suppliers.create(org, named("احمد للورق")));

    assertTrue(e.getMessage().contains("أحمد للورق"), e.getMessage());
    assertEquals(1, suppliers.list(org, null, null, 0, 20).total());
  }

  @Test
  void foldedNameIsUniquePerOrg_notGlobally() {
    UUID a = createOrg("acme");
    UUID b = createOrg("beta");
    suppliers.create(a, named("Zaki Paper"));
    Supplier other = suppliers.create(b, named("Zaki Paper"));

    assertNotNull(other.getId());
    assertEquals(1, suppliers.list(a, null, null, 0, 20).total());
    assertThrows(NotFoundException.class, () -> suppliers.getById(a, other.getId()));
  }

  @Test
  void search_matchesFoldedNameAndEmail_butNotPhone() {
    UUID org = createOrg("acme");
    suppliers.create(
        org, new SupplierEdit("أحمد للورق", "01012345678", "sales@zaki.test", null, null, null));

    assertEquals(1, suppliers.list(org, "احمد", null, 0, 20).total(), "folded name");
    assertEquals(1, suppliers.list(org, "SALES@ZAKI", null, 0, 20).total(), "email, case-folded");
    assertEquals(0, suppliers.list(org, "01012345678", null, 0, 20).total(), "phone is not a leg");
    assertEquals(1, suppliers.list(org, "   ", null, 0, 20).total(), "blank q is the whole list");
  }

  @Test
  void ordering_isNameAscending_overAMixedScriptSet() {
    UUID org = createOrg("acme");
    suppliers.create(org, named("Zaki Paper"));
    suppliers.create(org, named("أحمد للورق"));
    suppliers.create(org, named("Beta Supplies"));

    List<String> names =
        suppliers.list(org, null, null, 0, 20).suppliers().stream().map(Supplier::getName).toList();

    assertEquals(3, names.size());
    assertTrue(
        names.indexOf("Beta Supplies") < names.indexOf("Zaki Paper"),
        "name ASC, not newest-first: " + names);
  }

  @Test
  void activeFlag_narrows_andAbsentMeansBoth() {
    UUID org = createOrg("acme");
    suppliers.create(org, named("Live"));
    Supplier retired = suppliers.create(org, named("Retired"));
    suppliers.update(
        org, retired.getId(), new SupplierEdit(null, null, null, null, null, Boolean.FALSE));

    assertEquals(2, suppliers.list(org, null, null, 0, 20).total());
    assertEquals(1, suppliers.list(org, null, Boolean.TRUE, 0, 20).total());
    assertEquals(1, suppliers.list(org, null, Boolean.FALSE, 0, 20).total());
  }

  @Test
  void phoneE164_isDerived_andNullWhenUnparseable() {
    UUID org = createOrg("acme");
    Supplier dialable =
        suppliers.create(org, new SupplierEdit("Dialable", "01012345678", null, null, null, null));
    Supplier nonsense =
        suppliers.create(
            org, new SupplierEdit("Nonsense", "call the shop", null, null, null, null));

    assertNotNull(suppliers.getById(org, dialable.getId()).getPhoneE164());
    assertNull(suppliers.getById(org, nonsense.getId()).getPhoneE164());
  }

  @Test
  void update_isAMerge_andRenamingOntoAnotherFoldedNameIs409() {
    UUID org = createOrg("acme");
    Supplier zaki =
        suppliers.create(
            org, new SupplierEdit("Zaki Paper", "0101", "z@t.test", "Cairo", "note", null));
    suppliers.create(org, named("أحمد للورق"));

    Supplier merged =
        suppliers.update(org, zaki.getId(), new SupplierEdit(null, null, null, "Giza", null, null));
    assertEquals("Zaki Paper", merged.getName(), "null name leaves it unchanged");
    assertEquals("Giza", merged.getAddress());
    assertEquals("note", merged.getNotes());

    assertThrows(
        ConflictException.class, () -> suppliers.update(org, zaki.getId(), named("احمد للورق")));
    // Renaming to its own folded name is not a self-collision.
    assertEquals(
        "Zaki  Paper", suppliers.update(org, zaki.getId(), named("Zaki  Paper")).getName());
  }

  @Test
  void delete_is409WhileReceiptsReferenceIt_and204Otherwise() {
    UUID org = createOrg("acme");
    UUID user = createUser("owner@acme.test");
    Supplier unused = suppliers.create(org, named("Unused"));
    Supplier used = suppliers.create(org, named("Used"));
    UUID product = createTrackedProduct(org, "PEN", null, 0, 0);
    receipts.record(
        org, delivery(used.getId(), line(product, 5, "3.00")), "k-1", actor(user), user);

    suppliers.delete(org, unused.getId());
    assertThrows(NotFoundException.class, () -> suppliers.getById(org, unused.getId()));

    ConflictException e =
        assertThrows(ConflictException.class, () -> suppliers.delete(org, used.getId()));
    assertTrue(e.getMessage().contains("active:false"), e.getMessage());
    assertNotNull(suppliers.getById(org, used.getId()), "nothing was deleted");
  }

  @Test
  void receiptsSubresource_404sAnUnknownSupplier_andEmptyPagesOneWithNone() {
    UUID org = createOrg("acme");
    Supplier quiet = suppliers.create(org, named("Quiet"));

    assertThrows(NotFoundException.class, () -> suppliers.receipts(org, UUID.randomUUID(), 0, 20));
    var page = suppliers.receipts(org, quiet.getId(), 0, 20);
    assertEquals(0, page.total());
    assertTrue(page.receipts().isEmpty());
  }

  @Test
  void create_defaultsToActive_andNormalizesEmail() {
    UUID org = createOrg("acme");
    Supplier s =
        suppliers.create(
            org, new SupplierEdit("  Zaki Paper  ", null, "  SALES@Zaki.TEST ", null, null, null));

    assertEquals("Zaki Paper", s.getName());
    assertEquals("sales@zaki.test", s.getEmail());
    assertTrue(s.isActive());
    assertFalse(s.getId() == null);
  }
}
