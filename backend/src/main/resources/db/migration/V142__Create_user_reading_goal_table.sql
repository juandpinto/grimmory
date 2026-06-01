CREATE TABLE IF NOT EXISTS user_reading_goal
(
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id   BIGINT NOT NULL,
    year      INT    NOT NULL,
    book_goal INT    NOT NULL,
    CONSTRAINT fk_reading_goal_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uq_reading_goal_user_year UNIQUE (user_id, year)
);
