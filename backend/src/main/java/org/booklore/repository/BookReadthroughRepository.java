package org.booklore.repository;

import org.booklore.model.entity.BookReadthroughEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookReadthroughRepository extends JpaRepository<BookReadthroughEntity, Long> {

    List<BookReadthroughEntity> findByUserIdAndBookIdOrderByFinishedOnDesc(Long userId, Long bookId);

    List<BookReadthroughEntity> findByUserIdOrderByFinishedOnDesc(Long userId);

    @Query("""
            SELECT r FROM BookReadthroughEntity r
            WHERE r.user.id = :userId
            AND FUNCTION('YEAR', r.finishedOn) = :year
            ORDER BY r.finishedOn DESC
            """)
    List<BookReadthroughEntity> findByUserIdAndYear(@Param("userId") Long userId, @Param("year") int year);

    @Query("""
            SELECT COUNT(r) FROM BookReadthroughEntity r
            WHERE r.user.id = :userId
            AND FUNCTION('YEAR', r.finishedOn) = :year
            """)
    long countByUserIdAndYear(@Param("userId") Long userId, @Param("year") int year);

    @Query("""
            SELECT FUNCTION('MONTH', r.finishedOn) as month, COUNT(r) as count
            FROM BookReadthroughEntity r
            WHERE r.user.id = :userId
            AND FUNCTION('YEAR', r.finishedOn) = :year
            GROUP BY FUNCTION('MONTH', r.finishedOn)
            ORDER BY FUNCTION('MONTH', r.finishedOn)
            """)
    List<Object[]> countByUserIdAndYearGroupedByMonth(@Param("userId") Long userId, @Param("year") int year);

    @Query("""
            SELECT SUM(b.metadata.pageCount) FROM BookReadthroughEntity r
            JOIN r.book b
            WHERE r.user.id = :userId
            AND FUNCTION('YEAR', r.finishedOn) = :year
            AND b.metadata.pageCount IS NOT NULL
            """)
    Long sumPagesByUserIdAndYear(@Param("userId") Long userId, @Param("year") int year);

    /**
     * Finds the most recent readthrough for a user+book that finished before the given date.
     * Used to scope started_on auto-population to the correct reading period.
     */
    @Query("""
            SELECT r FROM BookReadthroughEntity r
            WHERE r.user.id = :userId
            AND r.book.id = :bookId
            ORDER BY r.finishedOn DESC
            LIMIT 1
            """)
    Optional<BookReadthroughEntity> findMostRecentByUserIdAndBookId(@Param("userId") Long userId, @Param("bookId") Long bookId);

    boolean existsByIdAndUserId(Long id, Long userId);
}
