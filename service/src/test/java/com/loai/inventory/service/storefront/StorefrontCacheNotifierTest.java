package com.loai.inventory.service.storefront;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.repository.OrgRepository;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.service.storefront.StorefrontRevalidator.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A cache hint never fails the write that raised it. */
class StorefrontCacheNotifierTest {

  record Call(String slug, Set<Scope> scopes) {}

  private final List<Call> calls = new ArrayList<>();
  private final StorefrontRevalidator recording =
      (slug, scopes) -> calls.add(new Call(slug, scopes));
  private final OrgRepository orgRepo = mock(OrgRepository.class);
  private final OrgRepositoryFactory factory = mock(OrgRepositoryFactory.class);

  private StorefrontCacheNotifier notifier(StorefrontRevalidator r) {
    when(factory.create(any())).thenReturn(orgRepo);
    return new StorefrontCacheNotifier(null, factory, r);
  }

  @Test
  void resolvesTheSlug_andSendsProfileOnly() {
    UUID id = UUID.randomUUID();
    Org org = new Org();
    org.setSlug("mart-cairo");
    when(orgRepo.findById(id)).thenReturn(Optional.of(org));

    notifier(recording).profileChanged(id);

    assertEquals(List.of(new Call("mart-cairo", Set.of(Scope.PROFILE))), calls);
  }

  @Test
  void unknownOrg_blankSlug_fireNothing() {
    UUID id = UUID.randomUUID();
    when(orgRepo.findById(id)).thenReturn(Optional.empty());
    StorefrontCacheNotifier n = notifier(recording);

    n.profileChanged(id);
    n.profileChanged((String) null);
    n.profileChanged(" ");

    assertEquals(List.of(), calls);
  }

  @Test
  void aThrowingLookup_orAThrowingRevalidator_neverEscapes() {
    UUID id = UUID.randomUUID();
    when(orgRepo.findById(id)).thenThrow(new IllegalStateException("db down"));
    assertDoesNotThrow(() -> notifier(recording).profileChanged(id));

    StorefrontRevalidator exploding =
        (slug, scopes) -> {
          throw new IllegalStateException("boom");
        };
    assertDoesNotThrow(() -> notifier(exploding).profileChanged("acme"));
  }

  @Test
  void theNoop_touchesNothing() {
    assertDoesNotThrow(() -> StorefrontCacheNotifier.NOOP.profileChanged(UUID.randomUUID()));
    assertDoesNotThrow(() -> StorefrontCacheNotifier.NOOP.profileChanged("acme"));
  }
}
