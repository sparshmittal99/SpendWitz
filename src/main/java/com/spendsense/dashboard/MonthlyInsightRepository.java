package com.spendsense.dashboard;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MonthlyInsightRepository extends JpaRepository<MonthlyInsight, Long> {

    Optional<MonthlyInsight> findByUserIdAndMonth(Long userId, String month);

    @Modifying
    @Query("delete from MonthlyInsight i where i.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
