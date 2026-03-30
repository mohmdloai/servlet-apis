package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.Customer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository {

  Optional<Customer> findById(UUID id);

  List<Customer> findAll(int offset, int limit);

  long count();

  Customer insert(Customer customer);

  Customer update(Customer customer);

  void deleteById(UUID id);

  boolean existsByEmail(String email);

  boolean existsByEmailAndIdNot(String email, UUID excludeId);
}
