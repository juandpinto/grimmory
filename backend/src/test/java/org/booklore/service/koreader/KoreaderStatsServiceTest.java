package org.booklore.service.koreader;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import org.booklore.config.security.userdetails.KoreaderUserDetails;
import org.booklore.model.dto.koreader.KoreaderPageStat;
import org.booklore.model.dto.koreader.KoreaderStatsPayload;
import org.booklore.model.entity.*;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.*;
import org.booklore.service.hardcover.HardcoverSyncService;
import org.booklore.util.koreader.EpubCfiService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KoreaderStatsServiceTest {

    @Mock UserBookProgressRepository progressRepo;
    @Mock UserBookFileProgressRepository fileProgressRepo;
    @Mock BookRepository bookRepo;
    @Mock UserRepository userRepo;
    @Mock KoreaderUserRepository koreaderUserRepo;
    @Mock HardcoverSyncService hardcoverSyncService;
    @Mock EpubCfiService epubCfiService;
    @Mock ReadingSessionRepository readingSessionRepo;

    @InjectMocks
    KoreaderService service;

    private KoreaderUserDetails details;

    @BeforeEach
    void setUpAuth() {
        details = mock(KoreaderUserDetails.class);
        when(details.getUsername()).thenReturn("user");
        when(details.getBookLoreUserId()).thenReturn(1L);
        when(details.isSyncEnabled()).thenReturn(true);
        Authentication auth = mock(Authentication.class);
        SecurityContext context = new SecurityContextImpl();
        when(auth.getPrincipal()).thenReturn(details);
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // squashIntoSessions — pure logic tests, no I/O
    // =========================================================================

    private KoreaderPageStat stat(int page, long startTime, int duration, int totalPages) {
        KoreaderPageStat s = new KoreaderPageStat();
        s.setPage(page);
        s.setStartTime(startTime);
        s.setDuration(duration);
        s.setTotalPages(totalPages);
        s.setBookMd5("abc");
        return s;
    }

    private BookLoreUserEntity user() {
        BookLoreUserEntity u = new BookLoreUserEntity();
        u.setId(1L);
        return u;
    }

    private BookEntity book() {
        BookEntity b = new BookEntity();
        b.setId(10L);
        return b;
    }

    private BookFileEntity file(BookFileType type) {
        BookFileEntity f = new BookFileEntity();
        f.setBookType(type);
        return f;
    }

    @Test
    void squash_emptyInput_returnsEmpty() {
        List<ReadingSessionEntity> result = service.squashIntoSessions(List.of(), user(), book(), file(BookFileType.PDF));
        assertTrue(result.isEmpty());
    }

    @Test
    void squash_singlePage_oneSession() {
        List<KoreaderPageStat> stats = List.of(stat(5, 1000L, 60, 200));
        List<ReadingSessionEntity> result = service.squashIntoSessions(stats, user(), book(), file(BookFileType.PDF));

        assertEquals(1, result.size());
        ReadingSessionEntity session = result.get(0);
        assertEquals(60, session.getDurationSeconds());
        assertEquals("5", session.getStartLocation());
        assertEquals("5", session.getEndLocation());
        assertEquals(1000L, session.getStartTime().getEpochSecond());
        assertEquals(1060L, session.getEndTime().getEpochSecond());
        assertEquals(BookFileType.PDF, session.getBookType());
    }

    @Test
    void squash_contiguousPages_oneSession() {
        // page 1: start=0, duration=100 → ends at 100
        // page 2: start=100, duration=80  → gap=0, still same session
        // page 3: start=180, duration=50  → gap=0, still same session
        List<KoreaderPageStat> stats = List.of(
                stat(1, 0L, 100, 100),
                stat(2, 100L, 80, 100),
                stat(3, 180L, 50, 100)
        );
        List<ReadingSessionEntity> result = service.squashIntoSessions(stats, user(), book(), file(BookFileType.EPUB));

        assertEquals(1, result.size());
        assertEquals(230, result.get(0).getDurationSeconds());
        assertEquals("1", result.get(0).getStartLocation());
        assertEquals("3", result.get(0).getEndLocation());
    }

    @Test
    void squash_largeGap_twoSessions() {
        // Session 1: page 1 ends at 100
        // Gap: 1000 seconds later (> 900 threshold)
        // Session 2: page 2
        List<KoreaderPageStat> stats = List.of(
                stat(1, 0L, 100, 200),
                stat(2, 1100L, 90, 200)
        );
        List<ReadingSessionEntity> result = service.squashIntoSessions(stats, user(), book(), file(BookFileType.EPUB));

        assertEquals(2, result.size());
        assertEquals("1", result.get(0).getStartLocation());
        assertEquals("2", result.get(1).getStartLocation());
        assertEquals(100, result.get(0).getDurationSeconds());
        assertEquals(90, result.get(1).getDurationSeconds());
    }

    @Test
    void squash_exactlyAtThreshold_sameSession() {
        // Gap exactly 900 seconds — should NOT split (only > 900 splits)
        List<KoreaderPageStat> stats = List.of(
                stat(1, 0L, 100, 100),
                stat(2, 1000L, 60, 100) // gap = 1000 - 100 = 900
        );
        List<ReadingSessionEntity> result = service.squashIntoSessions(stats, user(), book(), file(BookFileType.EPUB));
        assertEquals(1, result.size());
    }

    @Test
    void squash_justOverThreshold_twoSessions() {
        // Gap 901 seconds — should split
        List<KoreaderPageStat> stats = List.of(
                stat(1, 0L, 100, 100),
                stat(2, 1001L, 60, 100) // gap = 1001 - 100 = 901
        );
        List<ReadingSessionEntity> result = service.squashIntoSessions(stats, user(), book(), file(BookFileType.EPUB));
        assertEquals(2, result.size());
    }

    @Test
    void squash_progressCalculation() {
        // page=50 out of total=200 → progress = 25%
        List<KoreaderPageStat> stats = List.of(
                stat(50, 0L, 100, 200),
                stat(100, 100L, 100, 200)
        );
        List<ReadingSessionEntity> result = service.squashIntoSessions(stats, user(), book(), file(BookFileType.PDF));
        assertEquals(1, result.size());
        ReadingSessionEntity s = result.get(0);
        assertEquals(25f, s.getStartProgress(), 0.01f);
        assertEquals(50f, s.getEndProgress(), 0.01f);
        assertEquals(25f, s.getProgressDelta(), 0.01f);
    }

    @Test
    void squash_statsOutOfOrder_sorted() {
        // Provide stats in reverse order; squashing should sort them first
        List<KoreaderPageStat> stats = new ArrayList<>();
        stats.add(stat(2, 100L, 60, 100));
        stats.add(stat(1, 0L, 100, 100));
        List<ReadingSessionEntity> result = service.squashIntoSessions(stats, user(), book(), file(BookFileType.EPUB));
        assertEquals(1, result.size());
        assertEquals("1", result.get(0).getStartLocation()); // chronologically first
        assertEquals("2", result.get(0).getEndLocation());
    }

    @Test
    void squash_ignoresNullFields() {
        // Stats with null page or duration should be filtered out
        KoreaderPageStat nullDuration = new KoreaderPageStat();
        nullDuration.setPage(1);
        nullDuration.setStartTime(0L);
        nullDuration.setDuration(null);
        nullDuration.setTotalPages(100);
        nullDuration.setBookMd5("abc");

        KoreaderPageStat valid = stat(2, 500L, 60, 100);

        List<ReadingSessionEntity> result = service.squashIntoSessions(List.of(nullDuration, valid), user(), book(), file(BookFileType.EPUB));
        assertEquals(1, result.size());
        assertEquals("2", result.get(0).getStartLocation());
    }

    // =========================================================================
    // syncStats — integration-style tests with mocks
    // =========================================================================

    @Test
    void syncStats_emptyPayload_returnsNoStatsMessage() {
        KoreaderStatsPayload payload = new KoreaderStatsPayload();
        payload.setStats(List.of());

        BookLoreUserEntity userEntity = user();
        when(userRepo.findById(1L)).thenReturn(Optional.of(userEntity));

        Map<String, Object> result = service.syncStats(payload);
        assertEquals("no stats provided", result.get("status"));
        verify(readingSessionRepo, never()).save(any());
    }

    @Test
    void syncStats_bookNotFound_skipsAndLogs() {
        when(userRepo.findById(1L)).thenReturn(Optional.of(user()));
        when(bookRepo.findByCurrentHash("unknown_md5")).thenReturn(Optional.empty());

        KoreaderPageStat s = stat(1, 0L, 100, 100);
        s.setBookMd5("unknown_md5");

        KoreaderStatsPayload payload = new KoreaderStatsPayload();
        payload.setStats(List.of(s));

        Map<String, Object> result = service.syncStats(payload);
        assertEquals("ok", result.get("status"));
        assertEquals(0, result.get("inserted"));
        assertEquals(1, result.get("skipped_books"));
        verify(readingSessionRepo, never()).save(any());
    }

    @Test
    void syncStats_insertsNewSession() {
        BookLoreUserEntity userEntity = user();
        BookEntity bookEntity = book();
        BookFileEntity fileEntity = file(BookFileType.PDF);
        bookEntity.getBookFiles().add(fileEntity);

        when(userRepo.findById(1L)).thenReturn(Optional.of(userEntity));
        when(bookRepo.findByCurrentHash("md5abc")).thenReturn(Optional.of(bookEntity));
        when(readingSessionRepo.findByUserIdAndBookIdAndStartTime(anyLong(), anyLong(), any())).thenReturn(Optional.empty());

        KoreaderPageStat s = stat(10, 1000L, 120, 300);
        s.setBookMd5("md5abc");

        KoreaderStatsPayload payload = new KoreaderStatsPayload();
        payload.setStats(List.of(s));

        Map<String, Object> result = service.syncStats(payload);
        assertEquals("ok", result.get("status"));
        assertEquals(1, result.get("inserted"));
        assertEquals(0, result.get("updated"));
        verify(readingSessionRepo, times(1)).save(any());
    }

    @Test
    void syncStats_updatesExistingSession() {
        BookLoreUserEntity userEntity = user();
        BookEntity bookEntity = book();
        BookFileEntity fileEntity = file(BookFileType.PDF);
        bookEntity.getBookFiles().add(fileEntity);

        ReadingSessionEntity existing = new ReadingSessionEntity();

        when(userRepo.findById(1L)).thenReturn(Optional.of(userEntity));
        when(bookRepo.findByCurrentHash("md5abc")).thenReturn(Optional.of(bookEntity));
        when(readingSessionRepo.findByUserIdAndBookIdAndStartTime(anyLong(), anyLong(), any())).thenReturn(Optional.of(existing));

        KoreaderPageStat s = stat(10, 1000L, 120, 300);
        s.setBookMd5("md5abc");

        KoreaderStatsPayload payload = new KoreaderStatsPayload();
        payload.setStats(List.of(s));

        Map<String, Object> result = service.syncStats(payload);
        assertEquals("ok", result.get("status"));
        assertEquals(0, result.get("inserted"));
        assertEquals(1, result.get("updated"));
        // Verify mutable fields were updated on existing entity
        assertNotNull(existing.getEndTime());
        verify(readingSessionRepo, times(1)).save(existing);
    }
}
