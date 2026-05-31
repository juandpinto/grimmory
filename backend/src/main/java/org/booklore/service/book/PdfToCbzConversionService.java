package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.response.PdfOutlineItem;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.service.metadata.writer.CbxMetadataWriter;
import org.grimmory.pdfium4j.PdfDocument;
import org.grimmory.pdfium4j.model.Bookmark;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Converts a PDF to CBZ format, using PDF bookmarks to build a chapter folder structure.
 *
 * <p>Chapter folder naming (when bookmarks are present):
 * <ul>
 *   <li>{@code 00 - Front Matter/} — pages before the first bookmark</li>
 *   <li>{@code 01 - Chapter Title/}, {@code 02 - Chapter Title/}, … — bookmarked chapters</li>
 *   <li>{@code 99 - Back Matter/} — pages after the last bookmarked chapter (if any)</li>
 * </ul>
 * When no bookmarks exist, all pages are written flat (no sub-folders).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfToCbzConversionService {

    private static final int DEFAULT_DPI = 200;
    private static final String COMIC_INFO_ENTRY = "ComicInfo.xml";

    private final CbxMetadataWriter cbxMetadataWriter;

    /** A flat bookmark entry with a 1-based page number. */
    private record ChapterMark(String title, int pageNumber) {}

    /**
     * Converts the PDF at {@code pdfPath} to a CBZ file in the same directory.
     * The original PDF is left untouched.
     *
     * @return path to the generated CBZ file
     */
    public Path convert(Path pdfPath, BookMetadataEntity metadata) throws Exception {
        Path cbzPath = replaceExtension(pdfPath, "cbz");
        Path tempPath = Files.createTempFile(pdfPath.getParent(), ".cbz_pdf_", ".tmp");

        try (PdfDocument doc = PdfDocument.open(pdfPath)) {
            int pageCount = doc.pageCount();
            if (pageCount <= 0) {
                throw new IllegalStateException("PDF has no pages: " + pdfPath.getFileName());
            }

            List<ChapterMark> chapters = extractFlatBookmarks(doc);

            byte[] comicInfoXml = cbxMetadataWriter.generateComicInfoXml(metadata, pageCount);

            try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(tempPath))) {
                if (chapters.isEmpty()) {
                    writeFlatPages(doc, pageCount, zipOut);
                } else {
                    writeChapteredPages(doc, pageCount, chapters, zipOut);
                }

                // Embed ComicInfo.xml
                zipOut.putNextEntry(new ZipEntry(COMIC_INFO_ENTRY));
                zipOut.write(comicInfoXml);
                zipOut.closeEntry();
            }
        } catch (Exception e) {
            Files.deleteIfExists(tempPath);
            throw e;
        }

        Files.move(tempPath, cbzPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("PdfToCbzConversionService: Successfully converted {} → {}", pdfPath.getFileName(), cbzPath.getFileName());
        return cbzPath;
    }

    // -------------------------------------------------------------------------
    // Page writing strategies
    // -------------------------------------------------------------------------

    private void writeFlatPages(PdfDocument doc, int pageCount, ZipOutputStream zipOut) throws Exception {
        int digits = String.valueOf(pageCount).length();
        String fmt = "%0" + Math.max(digits, 4) + "d.jpg";
        for (int i = 0; i < pageCount; i++) {
            String entryName = String.format(fmt, i + 1);
            byte[] jpeg = renderPage(doc, i);
            zipOut.putNextEntry(new ZipEntry(entryName));
            zipOut.write(jpeg);
            zipOut.closeEntry();
        }
    }

    private void writeChapteredPages(PdfDocument doc, int pageCount,
                                      List<ChapterMark> chapters, ZipOutputStream zipOut) throws Exception {
        // chapters is sorted by pageNumber (1-based) and deduped.
        // Assign each page to a folder:
        //   Pages before chapters[0].pageNumber      → "00 - Front Matter"
        //   Pages from chapters[i].pageNumber until
        //   chapters[i+1].pageNumber - 1             → "NN - Title"
        //   Pages from chapters[last].pageNumber
        //   to end of PDF                            → stay in last chapter (no separate back matter,
        //                                               since PDF bookmarks only mark chapter starts)

        int chapterIdx = 0;
        String currentFolder = "00 - Front Matter";
        boolean frontMatterDone = false;

        for (int page = 1; page <= pageCount; page++) {
            // Advance through chapters whose start page we've reached
            while (chapterIdx < chapters.size() && chapters.get(chapterIdx).pageNumber() <= page) {
                ChapterMark mark = chapters.get(chapterIdx);
                currentFolder = String.format("%02d - %s", chapterIdx + 1, sanitizeFolderName(mark.title()));
                chapterIdx++;
                frontMatterDone = true;
            }

            byte[] jpeg = renderPage(doc, page - 1);
            String entryName = currentFolder + "/" + String.format("%04d.jpg", page);
            zipOut.putNextEntry(new ZipEntry(entryName));
            zipOut.write(jpeg);
            zipOut.closeEntry();
        }
    }

    // -------------------------------------------------------------------------
    // Bookmark extraction
    // -------------------------------------------------------------------------

    /**
     * Flattens the PDF bookmark tree into a sorted, deduplicated list of chapter marks.
     * Only top-level entries are used to avoid deeply nested sub-chapter pollution.
     */
    private List<ChapterMark> extractFlatBookmarks(PdfDocument doc) {
        List<ChapterMark> marks = new ArrayList<>();
        try {
            List<Bookmark> bookmarks = doc.bookmarks();
            for (Bookmark bm : bookmarks) {
                if (bm.title() != null && !bm.title().isBlank() && bm.pageIndex() >= 0) {
                    marks.add(new ChapterMark(bm.title().trim(), bm.pageIndex() + 1)); // 1-based
                }
            }
        } catch (Exception e) {
            log.debug("PdfToCbzConversionService: Could not extract bookmarks: {}", e.getMessage());
        }

        return marks.stream()
                .sorted(Comparator.comparingInt(ChapterMark::pageNumber))
                .distinct()
                .toList();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private byte[] renderPage(PdfDocument doc, int zeroBasedIndex) throws Exception {
        try {
            return doc.renderPageToBytes(zeroBasedIndex, DEFAULT_DPI, "jpeg");
        } catch (Exception e) {
            throw new IOException("Failed to render PDF page " + (zeroBasedIndex + 1) + ": " + e.getMessage(), e);
        }
    }

    private String sanitizeFolderName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private Path replaceExtension(Path path, String ext) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        return path.getParent().resolve(base + "." + ext);
    }
}
