package org.booklore.repository;

import org.booklore.model.entity.UserReadingGoalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserReadingGoalRepository extends JpaRepository<UserReadingGoalEntity, Long> {

    Optional<UserReadingGoalEntity> findByUserIdAndYear(Long userId, int year);

    boolean existsByUserIdAndYear(Long userId, int year);

    void deleteByUserIdAndYear(Long userId, int year);
}
