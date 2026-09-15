package com.example.jpalab.stock;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VersionedStockRepository extends JpaRepository<VersionedStock, Long> {
}
