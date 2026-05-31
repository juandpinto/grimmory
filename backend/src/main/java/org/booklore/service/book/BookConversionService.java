package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.config.security.service.AuthenticationService;
import org.booklore.exception.ApiError;
import org.booklore.mapper.BookMapper;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.model.enums.TaskType;
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
import org.booklore.service.file.FileFingerprint;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Orchestrates book-to-CBZ conversions, dispatching to the appropriate
 * format-specific service and updating the database accordingly.
 *
 * <ul>
 *   <li>CBR / CB7  → CBZ: replaces the existing BookFileEntity (same DB record, new file)</li>
 *   <li>EPUB       → CBZ: keeps the original EPUB; adds CBZ as an additional book format</li>
 *   <li>PDF        → CBZ: keeps the original PDF; adds CBZ as an additional book format</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookConversionService {

    private static final long BYTES_TO_KB = 1024L;

    private final BookRepository bookRepository;
    private final BookAdditionalFileRepository bookFileRepository;
    private final CbxNormalizationService cbxNormalizationService;
    private final EpubToCbzConversionService epubToCbzConversionService;
    private final PdfToCbzConversionService pdfToCbzConversionService;
    private final BookMapper bookMapper;
    private final NotificationService notificationService;
    private final AuthenticationService authenticationService;

    /**
     * Converts the primary file of {@code bookId} to CBZ format.
     * Progress is reported via WebSocket to the requesting user's task-progress queue.
     *
     * @param bookId  ID of the book to convert
     * @param taskId  task ID for progress reporting
     */
    public void convertToCbz(Long bookId, String taskId) {
        String username = authenticationService.getAuthenticatedUser().getUsername();
        sendProgress(taskId, username, "Starting conversion…", 0, TaskStatus.IN_PROGRESS);

        BookEntity book = bookRepository.findByIdWithBookFiles(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));

        BookFileEntity primaryFile = book.getPrimaryBookFile();
        if (primaryFile == null) {
            throw new IllegalStateException("Book " + bookId + " has no primary file");
        }

        BookFileType sourceType = primaryFile.getBookType();
        if (sourceType == null) {
            throw new IllegalStateException("Book " + bookId + " primary file has no type");
        }

        Path sourcePath = primaryFile.getFullFilePath();
        if (sourcePath == null || !Files.exists(sourcePath)) {
            throw new IllegalStateException("Primary file not found on disk: " + sourcePath);
        }

        try {
            switch (sourceType) {
                case CBX -> {
                    String ext = sourceExtension(primaryFile.getFileName());
                    if ("cbz".equalsIgnoreCase(ext)) {
                        throw new IllegalStateException("Book is already in CBZ format");
                    }
                    sendProgress(taskId, username, "Re-archiving " + ext.toUpperCase() + " → CBZ…", 10, TaskStatus.IN_PROGRESS);
                    Path cbzPath = cbxNormalizationService.convert(sourcePath, book.getMetadata());
                    sendProgress(taskId, username, "Updating database record…", 80, TaskStatus.IN_PROGRESS);
                    replacePrimaryFile(primaryFile, cbzPath, sourcePath);
                    Files.deleteIfExists(sourcePath);
                }
                case EPUB -> {
                    sendProgress(taskId, username, "Converting EPUB → CBZ…", 10, TaskStatus.IN_PROGRESS);
                    Path cbzPath = epubToCbzConversionService.convert(sourcePath, book.getMetadata());
                    sendProgress(taskId, username, "Saving CBZ as alternative format…", 85, TaskStatus.IN_PROGRESS);
                    addAlternativeFormat(book, cbzPath, primaryFile.getFileSubPath(), "CBZ version of EPUB");
                }
                case PDF -> {
                    sendProgress(taskId, username, "Rendering PDF pages → CBZ…", 10, TaskStatus.IN_PROGRESS);
                    Path cbzPath = pdfToCbzConversionService.convert(sourcePath, book.getMetadata());
                    sendProgress(taskId, username, "Saving CBZ as alternative format…", 85, TaskStatus.IN_PROGRESS);
                    addAlternativeFormat(book, cbzPath, primaryFile.getFileSubPath(), "CBZ version of PDF");
                }
                default -> throw new IllegalStateException("Unsupported source format for CBZ conversion: " + sourceType);
            }
        } catch (Exception e) {
            log.error("BookConversionService: Conversion failed for book {}: {}", bookId, e.getMessage(), e);
            sendProgress(taskId, username, "Conversion failed: " + e.getMessage(), 100, TaskStatus.FAILED);
            throw new RuntimeException("CBZ conversion failed: " + e.getMessage(), e);
        }

        // Notify frontend to refresh the book
        BookEntity refreshed = bookRepository.findByIdWithBookFiles(bookId).orElse(book);
        notificationService.sendMessageToUser(username, Topic.BOOK_UPDATE, bookMapper.toBookWithDescription(refreshed, false));

        sendProgress(taskId, username, "Conversion complete.", 100, TaskStatus.COMPLETED);
        log.info("BookConversionService: Conversion completed for book {}", bookId);
    }

    // -------------------------------------------------------------------------
    // Database helpers
    // -------------------------------------------------------------------------

    @Transactional
    protected void replacePrimaryFile(BookFileEntity fileEntity, Path newFile, Path oldFile) throws IOException {
        String newFileName = newFile.getFileName().toString();
        String newHash = FileFingerprint.generateHash(newFile);
        long newSizeKb = Files.size(newFile) / BYTES_TO_KB;

        fileEntity.setFileName(newFileName);
        fileEntity.setBookType(BookFileType.CBX);
        fileEntity.setInitialHash(newHash);
        fileEntity.setCurrentHash(newHash);
        fileEntity.setFileSizeKb(newSizeKb);

        bookFileRepository.save(fileEntity);
    }

    @Transactional
    protected void addAlternativeFormat(BookEntity book, Path cbzFile, String fileSubPath, String description) throws IOException {
        String hash = FileFingerprint.generateHash(cbzFile);
        long sizeKb = Files.size(cbzFile) / BYTES_TO_KB;

        BookFileEntity cbzEntity = BookFileEntity.builder()
                .book(book)
                .fileName(cbzFile.getFileName().toString())
                .fileSubPath(fileSubPath)
                .isBookFormat(true)
                .bookType(BookFileType.CBX)
                .fileSizeKb(sizeKb)
                .initialHash(hash)
                .currentHash(hash)
                .description(description)
                .addedOn(Instant.now())
                .build();

        bookFileRepository.save(cbzEntity);
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private void sendProgress(String taskId, String username, String message, int progress, TaskStatus status) {
        TaskProgressPayload payload = TaskProgressPayload.builder()
                .taskId(taskId)
                .taskType(TaskType.CONVERT_TO_CBZ)
                .message(message)
                .progress(progress)
                .taskStatus(status)
                .build();
        notificationService.sendMessageToUser(username, Topic.TASK_PROGRESS, payload);
    }

    private String sourceExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1).toLowerCase() : "";
    }
}
