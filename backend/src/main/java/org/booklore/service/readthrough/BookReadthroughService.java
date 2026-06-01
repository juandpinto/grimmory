package org.booklore.service.readthrough;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookReadthroughMapper;
import org.booklore.model.dto.BookReadthroughDto;
import org.booklore.model.dto.ReadthroughSummaryDto;
import org.booklore.model.dto.request.BookReadthroughRequest;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookLoreUserEntity;
import org.booklore.model.entity.BookReadthroughEntity;
import org.booklore.model.enums.ReadStatus;
import org.booklore.repository.BookReadthroughRepository;
import org.booklore.repository.BookRepository;
import org.booklore.repository.ReadingSessionRepository;
import org.booklore.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BookReadthroughService {

    private final BookReadthroughRepository readthroughRepository;
    private final BookRepository bookRepository;
    private final UserRepository userRepository;
    private final ReadingSessionRepository readingSessionRepository;
    private final BookReadthroughMapper readthroughMapper;
    private final AuthenticationService authenticationService;

    /**
     * Called from all status-transition trigger points. Creates a readthrough only when
     * the status is transitioning INTO READ (handles re-reads correctly).
     *
     * @param userId         the user whose progress changed
     * @param bookId         the book that was marked as read
     * @param previousStatus the status before this update (may be null for newly created records)
     * @param newStatus      the status after this update
     * @param finishedOn     the date to record as finished_on (UTC date extracted from Instant)
     */
    @Transactional
    public void createReadthroughIfNewCompletion(Long userId, Long bookId,
                                                  ReadStatus previousStatus, ReadStatus newStatus,
                                                  LocalDate finishedOn) {
        if (newStatus != ReadStatus.READ || previousStatus == ReadStatus.READ) {
            return;
        }

        BookLoreUserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("User not found: " + userId));
        BookEntity book = bookRepository.findById(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        LocalDate startedOn = resolveStartedOn(userId, bookId);

        BookReadthroughEntity readthrough = BookReadthroughEntity.builder()
                .user(user)
                .book(book)
                .startedOn(startedOn)
                .finishedOn(finishedOn)
                .build();

        readthroughRepository.save(readthrough);
        log.debug("Created readthrough for userId={} bookId={} finishedOn={}", userId, bookId, finishedOn);
    }

    /**
     * Resolves the started_on date by querying the earliest reading session after the
     * most recent previous readthrough's finished_on date (or the absolute earliest if none).
     */
    private LocalDate resolveStartedOn(Long userId, Long bookId) {
        Instant afterTime = readthroughRepository
                .findMostRecentByUserIdAndBookId(userId, bookId)
                .map(rt -> rt.getFinishedOn().atStartOfDay().toInstant(ZoneOffset.UTC))
                .orElse(null);

        Instant earliestSession = readingSessionRepository.findEarliestSessionStartAfter(userId, bookId, afterTime);

        return earliestSession != null
                ? earliestSession.atZone(ZoneOffset.UTC).toLocalDate()
                : null;
    }

    @Transactional(readOnly = true)
    public List<BookReadthroughDto> listForBook(Long bookId) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        return readthroughRepository.findByUserIdAndBookIdOrderByFinishedOnDesc(userId, bookId)
                .stream()
                .map(readthroughMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public BookReadthroughDto create(Long bookId, BookReadthroughRequest request) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        BookLoreUserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("User not found: " + userId));
        BookEntity book = bookRepository.findById(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        BookReadthroughEntity entity = BookReadthroughEntity.builder()
                .user(user)
                .book(book)
                .startedOn(request.getStartedOn())
                .finishedOn(request.getFinishedOn())
                .notes(request.getNotes())
                .build();

        return readthroughMapper.toDto(readthroughRepository.save(entity));
    }

    @Transactional
    public BookReadthroughDto update(Long readthroughId, BookReadthroughRequest request) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        BookReadthroughEntity entity = readthroughRepository.findById(readthroughId)
                .filter(r -> r.getUser().getId().equals(userId))
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Readthrough not found: " + readthroughId));

        entity.setStartedOn(request.getStartedOn());
        entity.setFinishedOn(request.getFinishedOn());
        entity.setNotes(request.getNotes());

        return readthroughMapper.toDto(readthroughRepository.save(entity));
    }

    @Transactional
    public void delete(Long readthroughId) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        BookReadthroughEntity entity = readthroughRepository.findById(readthroughId)
                .filter(r -> r.getUser().getId().equals(userId))
                .orElseThrow(() -> ApiError.GENERIC_NOT_FOUND.createException("Readthrough not found: " + readthroughId));

        readthroughRepository.delete(entity);
    }

    @Transactional(readOnly = true)
    public List<ReadthroughSummaryDto> listByUserAndYear(Long userId, int year) {
        return readthroughRepository.findByUserIdAndYear(userId, year)
                .stream()
                .map(this::toSummaryDto)
                .collect(Collectors.toList());
    }

    private ReadthroughSummaryDto toSummaryDto(BookReadthroughEntity rt) {
        var meta = rt.getBook().getMetadata();
        String authors = meta != null && meta.getAuthors() != null
                ? meta.getAuthors().stream()
                .map(a -> a.getName())
                .collect(Collectors.joining(", "))
                : null;

        return ReadthroughSummaryDto.builder()
                .readthroughId(rt.getId())
                .bookId(rt.getBook().getId())
                .bookTitle(meta != null ? meta.getTitle() : null)
                .pageCount(meta != null ? meta.getPageCount() : null)
                .authors(authors)
                .startedOn(rt.getStartedOn())
                .finishedOn(rt.getFinishedOn())
                .notes(rt.getNotes())
                .build();
    }
}
