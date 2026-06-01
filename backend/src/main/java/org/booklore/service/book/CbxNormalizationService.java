package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.service.ArchiveService;
import org.booklore.service.metadata.writer.CbxMetadataWriter;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.util.FileService;
import org.booklore.util.MimeDetector;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Re-archives CBR (RAR) and CB7 (7-Zip) comic archives as CBZ (ZIP), converting
 * non-JPEG images to JPEG at quality 85 and embedding a ComicInfo.xml entry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CbxNormalizationService {

    private static final float JPEG_QUALITY = 0.85f;
    private static final String COMIC_INFO_ENTRY = "ComicInfo.xml";

    private final ArchiveService archiveService;
    private final CbxMetadataWriter cbxMetadataWriter;

    /**
     * Converts a CBR or CB7 archive to CBZ format.
     *
     * @param sourcePath path to the source CBR/CB7 file
     * @param metadata   book metadata used to generate ComicInfo.xml
     * @return path to the generated CBZ file (same directory, same base name, .cbz extension)
     */
    public Path convert(Path sourcePath, BookMetadataEntity metadata) throws Exception {
        List<ArchiveService.Entry> entries = archiveService.getEntries(sourcePath);

        List<String> imageEntries = entries.stream()
                .map(ArchiveService.Entry::name)
                .filter(this::isImageEntry)
                .sorted(Comparator.comparing(String::toLowerCase))
                .toList();

        if (imageEntries.isEmpty()) {
            throw new IllegalStateException("No image entries found in archive: " + sourcePath.getFileName());
        }

        byte[] comicInfoXml = cbxMetadataWriter.generateComicInfoXml(metadata, imageEntries.size());

        Path cbzPath = replaceExtension(sourcePath, "cbz");
        Path tempPath = Files.createTempFile(sourcePath.getParent(), ".cbz_norm_", ".tmp");

        try {
            try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(tempPath))) {
                int pageIndex = 0;
                for (String entryName : imageEntries) {
                    String outputName = buildOutputEntryName(entryName, pageIndex++);
                    ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();
                    archiveService.transferEntryTo(sourcePath, entryName, entryBuffer);
                    byte[] imageBytes = entryBuffer.toByteArray();

                    byte[] outputBytes = convertToJpegIfNeeded(imageBytes, entryName, sourcePath);

                    ZipEntry zipEntry = new ZipEntry(outputName);
                    zipOut.putNextEntry(zipEntry);
                    zipOut.write(outputBytes);
                    zipOut.closeEntry();
                }

                // Embed ComicInfo.xml
                ZipEntry comicInfoEntry = new ZipEntry(COMIC_INFO_ENTRY);
                zipOut.putNextEntry(comicInfoEntry);
                zipOut.write(comicInfoXml);
                zipOut.closeEntry();
            }

            Files.move(tempPath, cbzPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("CbxNormalizationService: Successfully converted {} → {}", sourcePath.getFileName(), cbzPath.getFileName());
        } catch (Exception e) {
            Files.deleteIfExists(tempPath);
            throw e;
        }

        return cbzPath;
    }

    private String buildOutputEntryName(String originalEntryName, int pageIndex) {
        // Preserve folder structure from the original archive, but rename the file part.
        // If original was in a subfolder, keep the subfolder.
        int lastSlash = originalEntryName.lastIndexOf('/');
        String ext = ".jpg";
        String fileName = String.format("%04d%s", pageIndex + 1, ext);
        if (lastSlash >= 0) {
            return originalEntryName.substring(0, lastSlash + 1) + fileName;
        }
        return fileName;
    }

    private byte[] convertToJpegIfNeeded(byte[] imageBytes, String entryName, Path sourcePath) {
        try {
            String mime;
            try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
                mime = MimeDetector.detect(bais);
            }
            if ("image/jpeg".equals(mime)) {
                return imageBytes;
            }
            BufferedImage image = FileService.readImage(imageBytes);
            if (image == null) {
                log.warn("CbxNormalizationService: Could not decode image '{}', copying raw bytes", entryName);
                return imageBytes;
            }
            return encodeJpeg(image);
        } catch (Exception e) {
            log.warn("CbxNormalizationService: Error converting image '{}': {}, copying raw bytes", entryName, e.getMessage());
            return imageBytes;
        }
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        BufferedImage rgbImage = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgbImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgbImage.createGraphics().drawImage(image, 0, 0, null);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG image writer available");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
        }
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgbImage, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    private boolean isImageEntry(String entryName) {
        if (entryName.contains("__MACOSX")) return false;
        String fileName = entryName;
        int lastSlash = entryName.lastIndexOf('/');
        if (lastSlash >= 0) fileName = entryName.substring(lastSlash + 1);
        if (fileName.startsWith("._")) return false;

        String lower = fileName.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".png") || lower.endsWith(".webp") ||
               lower.endsWith(".gif") || lower.endsWith(".bmp") ||
               lower.endsWith(".avif") || lower.endsWith(".heic");
    }

    private Path replaceExtension(Path path, String newExtension) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        return path.getParent().resolve(base + "." + newExtension);
    }
}
