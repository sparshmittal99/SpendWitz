package com.spendwitz.budget;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BudgetRepository extends JpaRepository<Budget, Long> {

    List<Budget> findByUserIdOrderByCategory(Long userId);

    @Modifying
    @Query("delete from Budget b where b.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
