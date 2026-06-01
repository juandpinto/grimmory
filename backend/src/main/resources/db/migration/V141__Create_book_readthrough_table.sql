CREATE TABLE IF NOT EXISTS book_readthrough
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    book_id     BIGINT NOT NULL,
    started_on  DATE,
    finished_on DATE   NOT NULL,
    notes       TEXT,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_readthrough_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_readthrough_book FOREIGN KEY (book_id) REFERENCES book (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_readthrough_user_book ON book_readthrough (user_id, book_id);
CREATE INDEX IF NOT EXISTS idx_readthrough_user_year ON book_readthrough (user_id, finished_on);

-- Seed one readthrough per user_book_progress record that has a date_finished.
-- started_on is left NULL because we cannot reliably reconstruct it from historical data.
INSERT INTO book_readthrough (user_id, book_id, finished_on, created_at)
SELECT user_id, book_id, CAST(date_finished AS DATE), NOW()
FROM user_book_progress
WHERE date_finished IS NOT NULL;
