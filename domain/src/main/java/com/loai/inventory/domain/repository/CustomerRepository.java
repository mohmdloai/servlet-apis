package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Customer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository {

  Optional<Customer> findById(UUID orgId, UUID id);

  List<Customer> findAll(UUID orgId, int offset, int limit);

  long count(UUID orgId);

  Customer insert(Customer customer);

  Customer update(Customer customer);

  void deleteById(UUID orgId, UUID id);

  boolean existsByEmail(UUID orgId, String email);

  boolean existsByEmailAndIdNot(UUID orgId, String email, UUID excludeId);
}
