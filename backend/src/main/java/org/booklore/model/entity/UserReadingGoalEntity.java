package org.booklore.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_reading_goal",
        uniqueConstraints = @UniqueConstraint(name = "uq_reading_goal_user_year", columnNames = {"user_id", "year"}))
public class UserReadingGoalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private BookLoreUserEntity user;

    @Column(name = "year", nullable = false)
    private Integer year;

    @Column(name = "book_goal", nullable = false)
    private Integer bookGoal;
}
