package com.example.jpalab.stock;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.id = :stockId")
    Optional<Stock> findByIdForUpdate(@Param("stockId") Long stockId);

    @Modifying
    @Query("""
            update Stock s
               set s.quantity = s.quantity - 1
             where s.id = :stockId
               and s.quantity > 0
            """)
    int decreaseAtomically(@Param("stockId") Long stockId);
}
