package org.booklore.service.koreader;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.userdetails.KoreaderUserDetails;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.koreader.KoreaderPageStat;
import org.booklore.model.dto.koreader.KoreaderStatsPayload;
import org.booklore.model.dto.progress.KoreaderProgress;
import org.booklore.model.entity.*;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.*;
import org.booklore.service.hardcover.HardcoverSyncService;
import org.booklore.util.koreader.EpubCfiService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@AllArgsConstructor
@Service
public class KoreaderService {

    private final UserBookProgressRepository progressRepository;
    private final UserBookFileProgressRepository fileProgressRepository;
    private final BookRepository bookRepository;
    private final UserRepository userRepository;
    private final KoreaderUserRepository koreaderUserRepository;
    private final HardcoverSyncService hardcoverSyncService;
    private final EpubCfiService epubCfiService;
    private final ReadingSessionRepository readingSessionRepository;

    public ResponseEntity<Map<String, String>> authorizeUser() {
        KoreaderUserDetails authDetails = getAuthDetails();
        KoreaderUserEntity koreaderUser = findKoreaderUser(authDetails.getUsername());
        validatePassword(koreaderUser, authDetails);

        log.info("User '{}' authorized", authDetails.getUsername());
        return ResponseEntity.ok(Map.of("username", authDetails.getUsername()));
    }

    public KoreaderProgress getProgress(String bookHash) {
        KoreaderUserDetails authDetails = getAuthDetailsWithSyncCheck();
        BookEntity book = findBookByHash(bookHash);
        UserBookProgressEntity progress = findUserProgress(authDetails.getBookLoreUserId(), book.getId());

        log.info("getProgress: fetched progress='{}' percentage={} for userId={} bookHash={}",
                progress.getKoreaderProgress(), progress.getKoreaderProgressPercent(),
                authDetails.getBookLoreUserId(), bookHash);

        Long timestamp = progress.getKoreaderLastSyncTime() != null
                ? progress.getKoreaderLastSyncTime().getEpochSecond()
                : null;

        return KoreaderProgress.builder()
                .timestamp(timestamp)
                .document(bookHash)
                .progress(progress.getKoreaderProgress())
                .percentage(progress.getKoreaderProgressPercent())
                .device("BookLore")
                .device_id("BookLore")
                .build();
    }

    @Transactional
    public void saveProgress(String bookHash, KoreaderProgress koProgress) {
        KoreaderUserDetails authDetails = getAuthDetailsWithSyncCheck();
        BookEntity book = findBookByHash(bookHash);
        BookLoreUserEntity user = findBookLoreUser(authDetails.getBookLoreUserId());

        UserBookProgressEntity userProgress = getOrCreateUserProgress(user, book);
        Float previousProgressPercent = userProgress.getKoreaderProgressPercent();
        ReadStatus previousReadStatus = userProgress.getReadStatus();
        updateProgressData(userProgress, koProgress, authDetails.isSyncWithWebReader(), book);

        progressRepository.save(userProgress);

        // Also save to file-level progress table (dual-write)
        saveToFileProgress(user, book, userProgress);

        log.info("saveProgress: saved progress='{}' percentage={} for userId={} bookHash={}", koProgress.getProgress(), koProgress.getPercentage(), authDetails.getBookLoreUserId(), bookHash);

        // Sync progress to Hardcover asynchronously (if enabled for this user)
        // But only if the progress percentage has changed from last time, or the read status has changed
        if (koProgress.getPercentage() != null && (!koProgress.getPercentage().equals(previousProgressPercent)
                || userProgress.getReadStatus() != previousReadStatus)) {
            Float progressPercent = normalizeProgressPercent(koProgress.getPercentage());
            hardcoverSyncService.syncProgressToHardcover(book.getId(), progressPercent, authDetails.getBookLoreUserId());
        }
    }

    @Transactional
    public Map<String, Object> syncStats(KoreaderStatsPayload payload) {
        KoreaderUserDetails authDetails = getAuthDetailsWithSyncCheck();
        Long userId = authDetails.getBookLoreUserId();
        BookLoreUserEntity user = findBookLoreUser(userId);

        if (payload.getStats() == null || payload.getStats().isEmpty()) {
            return Map.of("status", "no stats provided");
        }

        // Group page stats by book MD5
        Map<String, List<KoreaderPageStat>> statsByMd5 = payload.getStats().stream()
                .filter(s -> s.getBookMd5() != null && s.getStartTime() != null && s.getDuration() != null)
                .collect(Collectors.groupingBy(KoreaderPageStat::getBookMd5));

        int savedCount = 0;
        int updatedCount = 0;
        int skippedBooks = 0;

        for (Map.Entry<String, List<KoreaderPageStat>> entry : statsByMd5.entrySet()) {
            String md5 = entry.getKey();
            List<KoreaderPageStat> pageStats = entry.getValue();

            BookEntity book = bookRepository.findByCurrentHash(md5).orElse(null);
            if (book == null) {
                log.debug("syncStats: no book found for md5={}, skipping", md5);
                skippedBooks++;
                continue;
            }

            BookFileEntity primaryFile = book.getPrimaryBookFile();
            if (primaryFile == null) {
                log.warn("syncStats: book id={} has no primary file, skipping", book.getId());
                skippedBooks++;
                continue;
            }

            List<ReadingSessionEntity> sessions = squashIntoSessions(pageStats, user, book, primaryFile);

            for (ReadingSessionEntity session : sessions) {
                ReadingSessionEntity existing = readingSessionRepository
                        .findByUserIdAndBookIdAndStartTime(userId, book.getId(), session.getStartTime())
                        .orElse(null);

                if (existing != null) {
                    existing.setEndTime(session.getEndTime());
                    existing.setDurationSeconds(session.getDurationSeconds());
                    existing.setEndLocation(session.getEndLocation());
                    existing.setEndProgress(session.getEndProgress());
                    existing.setProgressDelta(session.getProgressDelta());
                    readingSessionRepository.save(existing);
                    updatedCount++;
                } else {
                    readingSessionRepository.save(session);
                    savedCount++;
                }
            }
        }

        log.info("syncStats: userId={} inserted={} updated={} skippedBooks={}", userId, savedCount, updatedCount, skippedBooks);
        return Map.of("status", "ok", "inserted", savedCount, "updated", updatedCount, "skipped_books", skippedBooks);
    }

    /**
     * Squashes a list of raw per-page KOReader stats into aggregated reading sessions.
     * A new session begins whenever the gap between the end of one page turn and the
     * start of the next exceeds 15 minutes (900 seconds).
     */
    List<ReadingSessionEntity> squashIntoSessions(List<KoreaderPageStat> pageStats,
                                                  BookLoreUserEntity user,
                                                  BookEntity book,
                                                  BookFileEntity primaryFile) {
        List<KoreaderPageStat> sorted = pageStats.stream()
                .filter(s -> s.getStartTime() != null && s.getDuration() != null && s.getPage() != null)
                .sorted(Comparator.comparingLong(KoreaderPageStat::getStartTime))
                .toList();

        List<ReadingSessionEntity> sessions = new ArrayList<>();
        if (sorted.isEmpty()) {
            return sessions;
        }

        List<KoreaderPageStat> currentGroup = new ArrayList<>();
        currentGroup.add(sorted.get(0));

        for (int i = 1; i < sorted.size(); i++) {
            KoreaderPageStat prev = sorted.get(i - 1);
            KoreaderPageStat curr = sorted.get(i);

            long prevEnd = prev.getStartTime() + prev.getDuration();
            long gap = curr.getStartTime() - prevEnd;

            if (gap > 900) {
                sessions.add(buildSession(currentGroup, user, book, primaryFile));
                currentGroup = new ArrayList<>();
            }
            currentGroup.add(curr);
        }
        sessions.add(buildSession(currentGroup, user, book, primaryFile));
        return sessions;
    }

    private ReadingSessionEntity buildSession(List<KoreaderPageStat> group,
                                              BookLoreUserEntity user,
                                              BookEntity book,
                                              BookFileEntity primaryFile) {
        KoreaderPageStat first = group.get(0);
        KoreaderPageStat last = group.get(group.size() - 1);

        int totalDuration = group.stream().mapToInt(KoreaderPageStat::getDuration).sum();
        Instant startTime = Instant.ofEpochSecond(first.getStartTime());
        Instant endTime = Instant.ofEpochSecond(last.getStartTime() + last.getDuration());

        float totalPages = first.getTotalPages() != null && first.getTotalPages() > 0 ? first.getTotalPages() : 1f;
        float startProgress = (first.getPage() / totalPages) * 100f;
        float endProgress = (last.getPage() / totalPages) * 100f;
        float progressDelta = Math.max(0f, endProgress - startProgress);

        return ReadingSessionEntity.builder()
                .user(user)
                .book(book)
                .bookType(primaryFile.getBookType())
                .startTime(startTime)
                .endTime(endTime)
                .durationSeconds(totalDuration)
                .startLocation(String.valueOf(first.getPage()))
                .endLocation(String.valueOf(last.getPage()))
                .startProgress(startProgress)
                .endProgress(endProgress)
                .progressDelta(progressDelta)
                .build();
    }

    private void saveToFileProgress(BookLoreUserEntity user, BookEntity book, UserBookProgressEntity progress) {
        try {
            BookFileEntity primaryFile = book.getPrimaryBookFile();
            UserBookFileProgressEntity fileProgress = fileProgressRepository
                    .findByUserIdAndBookFileId(user.getId(), primaryFile.getId())
                    .orElseGet(UserBookFileProgressEntity::new);

            fileProgress.setUser(user);
            fileProgress.setBookFile(primaryFile);
            fileProgress.setLastReadTime(progress.getLastReadTime());

            // Map progress based on book type
            switch (primaryFile.getBookType()) {
                case EPUB, FB2, MOBI, AZW3 -> {
                    fileProgress.setPositionData(progress.getEpubProgress());
                    fileProgress.setPositionHref(progress.getEpubProgressHref());
                    fileProgress.setProgressPercent(progress.getEpubProgressPercent());
                }
                case PDF -> {
                    fileProgress.setPositionData(progress.getPdfProgress() != null ?
                            String.valueOf(progress.getPdfProgress()) : null);
                    fileProgress.setProgressPercent(progress.getPdfProgressPercent());
                }
                case CBX -> {
                    fileProgress.setPositionData(progress.getCbxProgress() != null ?
                            String.valueOf(progress.getCbxProgress()) : null);
                    fileProgress.setProgressPercent(progress.getCbxProgressPercent());
                }
            }

            fileProgressRepository.save(fileProgress);
        } catch (Exception e) {
            log.warn("Failed to save file-level progress for book {}: {}", book.getId(), e.getMessage());
        }
    }

    private void updateProgressData(UserBookProgressEntity userProgress, KoreaderProgress koProgress, boolean syncWithWebReader, BookEntity book) {
        userProgress.setKoreaderProgress(koProgress.getProgress());
        userProgress.setKoreaderProgressPercent(koProgress.getPercentage());
        userProgress.setKoreaderDevice(koProgress.getDevice());
        userProgress.setKoreaderDeviceId(koProgress.getDevice_id());
        userProgress.setKoreaderLastSyncTime(Instant.now());
        userProgress.setLastReadTime(Instant.now());
        if (syncWithWebReader && koProgress.getProgress() != null) {
            try {
                String cfi = epubCfiService.convertXPointerToCfi(book.getFullFilePath(), koProgress.getProgress());

                float percent = koProgress.getPercentage() * 100f;
                float rounded = BigDecimal
                        .valueOf(percent)
                        .setScale(1, RoundingMode.HALF_UP)
                        .floatValue();

                userProgress.setEpubProgress(cfi);
                userProgress.setEpubProgressPercent(rounded);

                log.info("Converted xpointer to CFI for BookLore reader sync: {}", cfi);
            } catch (Exception e) {
                log.warn("Failed to convert xpointer to CFI: {}", e.getMessage());
            }
        }

        updateReadStatus(userProgress, koProgress.getPercentage());
    }

    private void updateReadStatus(UserBookProgressEntity userProgress, Float progressFraction) {
        if (progressFraction == null) {
            return;
        }
        double progressPercent = progressFraction * 100.0;
        if (progressPercent >= 99.5) {
            userProgress.setReadStatus(ReadStatus.READ);
            userProgress.setDateFinished(Instant.now());
        } else if (progressPercent >= 0.25) {
            userProgress.setReadStatus(ReadStatus.READING);
        } else {
            userProgress.setReadStatus(ReadStatus.UNREAD);
        }
    }

    private Float normalizeProgressPercent(Float progress) {
        if (progress == null) {
            return null;
        }
        if (progress <= 1.0f) {
            return progress * 100.0f;
        }
        return progress;
    }

    private KoreaderUserDetails getAuthDetails() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof KoreaderUserDetails details)) {
            log.warn("Authentication failed: invalid principal type");
            throw ApiError.GENERIC_UNAUTHORIZED.createException("User not authenticated");
        }
        return details;
    }

    private KoreaderUserDetails getAuthDetailsWithSyncCheck() {
        KoreaderUserDetails authDetails = getAuthDetails();
        ensureSyncEnabled(authDetails);
        return authDetails;
    }

    private KoreaderUserEntity findKoreaderUser(String username) {
        return koreaderUserRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.warn("KOReader user '{}' not found", username);
                    return ApiError.GENERIC_NOT_FOUND.createException("KOReader user not found");
                });
    }

    private void validatePassword(KoreaderUserEntity koreaderUser, KoreaderUserDetails authDetails) {
        if (koreaderUser.getPasswordMD5() == null ||
                !koreaderUser.getPasswordMD5().equalsIgnoreCase(authDetails.getPassword())) {
            log.warn("Password mismatch for user '{}'", authDetails.getUsername());
            throw ApiError.GENERIC_UNAUTHORIZED.createException("Invalid credentials");
        }
    }

    private BookEntity findBookByHash(String bookHash) {
        return bookRepository.findByCurrentHash(bookHash)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Book not found for hash " + bookHash));
    }

    private BookLoreUserEntity findBookLoreUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("User not found with id " + userId));
    }

    private UserBookProgressEntity findUserProgress(long userId, Long bookId) {
        return progressRepository.findByUserIdAndBookId(userId, bookId)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("No progress found for user and book"));
    }

    private UserBookProgressEntity getOrCreateUserProgress(BookLoreUserEntity user, BookEntity book) {
        return progressRepository.findByUserIdAndBookId(user.getId(), book.getId())
                .orElseGet(() -> {
                    UserBookProgressEntity newProgress = new UserBookProgressEntity();
                    newProgress.setUser(user);
                    newProgress.setBook(book);
                    return newProgress;
                });
    }

    private void ensureSyncEnabled(KoreaderUserDetails details) {
        if (!details.isSyncEnabled()) {
            log.warn("Sync is disabled for user '{}'", details.getUsername());
            throw ApiError.GENERIC_UNAUTHORIZED.createException("Sync is disabled for this user");
        }
    }
}
