package com.spendsense.cash;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashTopUpRepository extends JpaRepository<CashTopUp, Long> {

    List<CashTopUp> findByUserIdOrderByEntryDateDescIdDesc(Long userId, Pageable pageable);

    Optional<CashTopUp> findByIdAndUserId(Long id, Long userId);

    List<CashTopUp> findByUserIdAndEntryDateBetween(Long userId, LocalDate from, LocalDate to);

    @Query("select coalesce(sum(t.amount), 0) from CashTopUp t where t.userId = :userId")
    BigDecimal totalForUser(@Param("userId") Long userId);

    @Query("select min(t.entryDate) from CashTopUp t where t.userId = :userId")
    LocalDate firstEntryDate(@Param("userId") Long userId);

    @Modifying
    @Query("delete from CashTopUp t where t.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
