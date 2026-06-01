package org.booklore.service.book;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.service.metadata.writer.CbxMetadataWriter;
import org.booklore.util.FileService;
import org.booklore.util.MimeDetector;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Converts image-based EPUBs to CBZ format, preserving the chapter structure
 * derived from the EPUB's table of contents.
 *
 * <p>Chapter folder naming:
 * <ul>
 *   <li>{@code 00 - Front Matter/} — pages before the first TOC entry</li>
 *   <li>{@code 01 - Chapter Title/}, {@code 02 - Chapter Title/}, … — TOC chapters</li>
 *   <li>Pages past the last TOC entry remain in the last chapter's folder</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EpubToCbzConversionService {

    private static final float JPEG_QUALITY = 0.85f;
    private static final String COMIC_INFO_ENTRY = "ComicInfo.xml";

    private final CbxMetadataWriter cbxMetadataWriter;

    // -------------------------------------------------------------------------
    // Internal data carriers
    // -------------------------------------------------------------------------

    private record ManifestItem(String id, String href, String mediaType, String properties) {}

    private record TocEntry(String title, int spineIndex) {}

    private record PageEntry(String zipPath, String chapterFolder) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Converts the EPUB at {@code epubPath} to a CBZ file in the same directory.
     *
     * @return path to the generated CBZ file
     */
    public Path convert(Path epubPath, BookMetadataEntity metadata) throws Exception {
        Path cbzPath = replaceExtension(epubPath, "cbz");
        Path tempPath = Files.createTempFile(epubPath.getParent(), ".cbz_epub_", ".tmp");

        try (ZipFile epub = new ZipFile(epubPath.toFile())) {
            String opfPath = findOpfPath(epub);
            String opfBaseDir = parentDir(opfPath);

            Map<String, ManifestItem> manifest = new LinkedHashMap<>();
            List<String> spine = new ArrayList<>();
            parseOpf(epub, opfPath, opfBaseDir, manifest, spine);

            List<TocEntry> toc = parseToc(epub, manifest, opfPath, opfBaseDir, spine);

            List<PageEntry> pages = collectPages(epub, manifest, spine, opfBaseDir, toc);

            if (pages.isEmpty()) {
                throw new IllegalStateException("No images found in EPUB: " + epubPath.getFileName());
            }

            byte[] comicInfoXml = cbxMetadataWriter.generateComicInfoXml(metadata, pages.size());

            writeCbz(epub, tempPath, pages, comicInfoXml);
        }

        Files.move(tempPath, cbzPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("EpubToCbzConversionService: Successfully converted {} → {}", epubPath.getFileName(), cbzPath.getFileName());
        return cbzPath;
    }

    // -------------------------------------------------------------------------
    // EPUB parsing
    // -------------------------------------------------------------------------

    /** Finds the OPF rootfile path via META-INF/container.xml. */
    private String findOpfPath(ZipFile epub) throws Exception {
        ZipEntry containerEntry = epub.getEntry("META-INF/container.xml");
        if (containerEntry == null) {
            throw new IllegalStateException("EPUB missing META-INF/container.xml");
        }
        try (InputStream in = epub.getInputStream(containerEntry)) {
            org.w3c.dom.Document doc = parseXmlSecurely(in);
            NodeList rootfiles = doc.getElementsByTagNameNS("*", "rootfile");
            if (rootfiles.getLength() == 0) {
                throw new IllegalStateException("No rootfile element found in container.xml");
            }
            String path = rootfiles.item(0).getAttributes().getNamedItem("full-path").getNodeValue();
            if (path == null || path.isBlank()) {
                throw new IllegalStateException("rootfile full-path attribute is empty");
            }
            return path;
        }
    }

    /**
     * Parses the OPF file to extract the manifest (id→item) and spine (ordered idref list).
     */
    private void parseOpf(ZipFile epub, String opfPath, String opfBaseDir,
                          Map<String, ManifestItem> manifest, List<String> spine) throws Exception {
        ZipEntry opfEntry = epub.getEntry(opfPath);
        if (opfEntry == null) {
            throw new IllegalStateException("OPF file not found in EPUB: " + opfPath);
        }
        try (InputStream in = epub.getInputStream(opfEntry)) {
            org.w3c.dom.Document doc = parseXmlSecurely(in);

            // Build manifest map
            NodeList items = doc.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < items.getLength(); i++) {
                Node item = items.item(i);
                String id = attrValue(item, "id");
                String href = attrValue(item, "href");
                String mediaType = attrValue(item, "media-type");
                if (id != null && href != null) {
                    String decodedHref = URLDecoder.decode(href, StandardCharsets.UTF_8);
                    String properties = attrValue(item, "properties");
                    manifest.put(id, new ManifestItem(id, decodedHref, mediaType != null ? mediaType : "", properties != null ? properties : ""));
                }
            }

            // Build spine list (ordered idrefs)
            NodeList itemrefs = doc.getElementsByTagNameNS("*", "itemref");
            for (int i = 0; i < itemrefs.getLength(); i++) {
                String idref = attrValue(itemrefs.item(i), "idref");
                if (idref != null) {
                    spine.add(idref);
                }
            }
        }
    }

    /**
     * Parses the table of contents (EPUB3 nav or EPUB2 NCX) and maps each entry to a spine index.
     */
    private List<TocEntry> parseToc(ZipFile epub, Map<String, ManifestItem> manifest,
                                    String opfPath, String opfBaseDir, List<String> spine) {
        // Build a map from spine item href (resolved, normalised) → spine index
        Map<String, Integer> hrefToSpineIndex = buildHrefToSpineIndex(manifest, spine, opfBaseDir);

        // Try EPUB3 nav first
        Optional<ManifestItem> navItem = manifest.values().stream()
                .filter(item -> item.mediaType().contains("xhtml") &&
                                item.mediaType().contains("nav") ||
                                // properties="nav" is in OPF but not captured in ManifestItem
                                // Fall back: check all XHTML items named "nav"
                                item.href().contains("nav"))
                .findFirst();

        // More robust: scan for the OPF nav item via properties attribute — but we don't store it.
        // Instead, look for toc.ncx in the manifest (EPUB2) or toc.xhtml / nav.xhtml (EPUB3).
        List<TocEntry> toc = tryParseEpub3Nav(epub, manifest, opfBaseDir, hrefToSpineIndex);
        if (toc.isEmpty()) {
            toc = tryParseNcx(epub, manifest, opfBaseDir, hrefToSpineIndex);
        }

        return toc;
    }

    private List<TocEntry> tryParseEpub3Nav(ZipFile epub, Map<String, ManifestItem> manifest,
                                             String opfBaseDir, Map<String, Integer> hrefToSpineIndex) {
        // Find nav document: prefer manifest item with properties="nav" (EPUB3 spec)
        Optional<ManifestItem> navItem = manifest.values().stream()
                .filter(item -> item.properties().contains("nav"))
                .findFirst();
        // Fallback: look for XHTML items with "nav" or "toc" in their href
        if (navItem.isEmpty()) {
            navItem = manifest.values().stream()
                    .filter(item -> item.mediaType().contains("application/xhtml+xml") &&
                                    (item.href().toLowerCase().contains("nav") ||
                                     item.href().toLowerCase().contains("toc")))
                    .findFirst();
        }

        if (navItem.isEmpty()) {
            log.debug("EpubToCbzConversionService: No EPUB3 nav document found in manifest");
            return Collections.emptyList();
        }

        String navFullPath = joinPath(opfBaseDir, navItem.get().href());
        ZipEntry navEntry = epub.getEntry(navFullPath);
        if (navEntry == null) return Collections.emptyList();

        try (InputStream in = epub.getInputStream(navEntry)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Document html = Jsoup.parse(content);
            Elements tocNav = html.select("nav[epub\\:type='toc'], nav[*|type='toc']");
            if (tocNav.isEmpty()) {
                tocNav = html.select("nav");
            }
            if (tocNav.isEmpty()) return Collections.emptyList();

            List<TocEntry> entries = new ArrayList<>();
            String navBaseDir = parentDir(navFullPath);
            for (Element a : tocNav.first().select("a[href]")) {
                String href = a.attr("href");
                String title = a.text().trim();
                if (title.isEmpty()) continue;

                // Strip fragment
                String hrefNoFrag = href.contains("#") ? href.substring(0, href.indexOf('#')) : href;
                hrefNoFrag = URLDecoder.decode(hrefNoFrag, StandardCharsets.UTF_8);
                String fullHref = normaliseHref(navBaseDir, hrefNoFrag);

                Integer spineIdx = hrefToSpineIndex.get(fullHref);
                if (spineIdx != null) {
                    entries.add(new TocEntry(title, spineIdx));
                }
            }
            return deduplicateBySpineIndex(entries);
        } catch (Exception e) {
            log.debug("EpubToCbzConversionService: Could not parse EPUB3 nav: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<TocEntry> tryParseNcx(ZipFile epub, Map<String, ManifestItem> manifest,
                                        String opfBaseDir, Map<String, Integer> hrefToSpineIndex) {
        Optional<ManifestItem> ncxItem = manifest.values().stream()
                .filter(item -> item.mediaType().contains("ncx") ||
                                item.href().toLowerCase().endsWith(".ncx"))
                .findFirst();

        if (ncxItem.isEmpty()) return Collections.emptyList();

        String ncxFullPath = joinPath(opfBaseDir, ncxItem.get().href());
        ZipEntry ncxEntry = epub.getEntry(ncxFullPath);
        if (ncxEntry == null) return Collections.emptyList();

        try (InputStream in = epub.getInputStream(ncxEntry)) {
            org.w3c.dom.Document doc = parseXmlSecurely(in);
            String ncxBaseDir = parentDir(ncxFullPath);

            NodeList navPoints = doc.getElementsByTagNameNS("*", "navPoint");
            List<TocEntry> entries = new ArrayList<>();
            for (int i = 0; i < navPoints.getLength(); i++) {
                Node navPoint = navPoints.item(i);

                // NCX structure: <navPoint><navLabel><text>Title</text></navLabel><content src="..."/></navPoint>
                // title is a grandchild: navPoint → navLabel → text
                String title = null;
                Node navLabel = firstChildByLocalName(navPoint, "navLabel");
                if (navLabel != null) {
                    Node textNode = firstChildByLocalName(navLabel, "text");
                    if (textNode != null) title = textNode.getTextContent();
                }
                if (title == null || title.isBlank()) continue;

                // src is the "src" attribute of <content src="..."/>
                Node contentNode = firstChildByLocalName(navPoint, "content");
                String src = contentNode != null ? attrValue(contentNode, "src") : null;
                if (src == null) continue;

                String hrefNoFrag = src.contains("#") ? src.substring(0, src.indexOf('#')) : src;
                hrefNoFrag = URLDecoder.decode(hrefNoFrag, StandardCharsets.UTF_8);
                String fullHref = normaliseHref(ncxBaseDir, hrefNoFrag);

                Integer spineIdx = hrefToSpineIndex.get(fullHref);
                if (spineIdx != null) {
                    entries.add(new TocEntry(title, spineIdx));
                }
            }
            return deduplicateBySpineIndex(entries);
        } catch (Exception e) {
            log.debug("EpubToCbzConversionService: Could not parse NCX: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // -------------------------------------------------------------------------
    // Page collection
    // -------------------------------------------------------------------------

    private List<PageEntry> collectPages(ZipFile epub, Map<String, ManifestItem> manifest,
                                          List<String> spine, String opfBaseDir,
                                          List<TocEntry> toc) {
        // Map: spine index → TocEntry (chapter start at that index)
        Map<Integer, TocEntry> chapterStartMap = new TreeMap<>();
        for (TocEntry entry : toc) {
            chapterStartMap.put(entry.spineIndex(), entry);
        }

        String currentFolder = "00 - Front Matter";
        int chapterCounter = 0;
        List<PageEntry> pages = new ArrayList<>();

        for (int spineIdx = 0; spineIdx < spine.size(); spineIdx++) {
            TocEntry chapterStart = chapterStartMap.get(spineIdx);
            if (chapterStart != null) {
                chapterCounter++;
                currentFolder = String.format("%02d - %s", chapterCounter, sanitizeFolderName(chapterStart.title()));
            }
            // Pages past the last TOC entry remain in the last chapter's folder; no Back Matter fallback.

            String idref = spine.get(spineIdx);
            ManifestItem xhtmlItem = manifest.get(idref);
            if (xhtmlItem == null) continue;

            String xhtmlFullPath = joinPath(opfBaseDir, xhtmlItem.href());
            String xhtmlBaseDir = parentDir(xhtmlFullPath);

            ZipEntry xhtmlZipEntry = epub.getEntry(xhtmlFullPath);
            if (xhtmlZipEntry == null) {
                log.debug("EpubToCbzConversionService: Spine item not found in ZIP: {}", xhtmlFullPath);
                continue;
            }

            List<String> imageHrefs = extractImagePathsFromXhtml(epub, xhtmlZipEntry, xhtmlBaseDir, opfBaseDir, manifest);
            Set<String> seen = new LinkedHashSet<>();
            for (String imagePath : imageHrefs) {
                if (seen.add(imagePath) && epub.getEntry(imagePath) != null) {
                    pages.add(new PageEntry(imagePath, currentFolder));
                }
            }
        }

        return pages;
    }

    /**
     * Parses an XHTML spine item and extracts the full ZIP paths of embedded images,
     * resolving relative hrefs against the XHTML file's directory.
     */
    private List<String> extractImagePathsFromXhtml(ZipFile epub, ZipEntry xhtmlEntry,
                                                     String xhtmlBaseDir, String opfBaseDir,
                                                     Map<String, ManifestItem> manifest) {
        try (InputStream in = epub.getInputStream(xhtmlEntry)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Document html = Jsoup.parse(content);
            List<String> paths = new ArrayList<>();

            // <img src="...">
            for (Element img : html.select("img[src]")) {
                String src = img.attr("src");
                String resolved = resolveImageHref(src, xhtmlBaseDir);
                if (resolved != null) paths.add(resolved);
            }

            // <image href="..."> or <image xlink:href="..."> (SVG in EPUB)
            for (Element image : html.select("image")) {
                String href = image.hasAttr("href") ? image.attr("href") :
                              image.hasAttr("xlink:href") ? image.attr("xlink:href") : null;
                if (href != null && !href.startsWith("data:")) {
                    String resolved = resolveImageHref(href, xhtmlBaseDir);
                    if (resolved != null) paths.add(resolved);
                }
            }

            return paths;
        } catch (Exception e) {
            log.debug("EpubToCbzConversionService: Could not parse XHTML {}: {}", xhtmlEntry.getName(), e.getMessage());
            return Collections.emptyList();
        }
    }

    private String resolveImageHref(String href, String baseDir) {
        if (href == null || href.isBlank() || href.startsWith("data:") || href.startsWith("http")) {
            return null;
        }
        try {
            String decoded = URLDecoder.decode(href, StandardCharsets.UTF_8);
            String stripped = decoded.contains("#") ? decoded.substring(0, decoded.indexOf('#')) : decoded;
            return normaliseHref(baseDir, stripped);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // CBZ writing
    // -------------------------------------------------------------------------

    private void writeCbz(ZipFile epub, Path cbzPath, List<PageEntry> pages, byte[] comicInfoXml) throws Exception {
        Map<String, Integer> folderPageCounter = new LinkedHashMap<>();

        try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(cbzPath))) {
            for (PageEntry page : pages) {
                String folder = page.chapterFolder();
                int idx = folderPageCounter.merge(folder, 1, Integer::sum);
                String entryName = folder + "/" + String.format("%03d.jpg", idx);

                ZipEntry zipEntry = epub.getEntry(page.zipPath());
                if (zipEntry == null) continue;

                byte[] imageBytes;
                try (InputStream in = epub.getInputStream(zipEntry)) {
                    imageBytes = in.readAllBytes();
                }

                byte[] outputBytes = toJpegIfNeeded(imageBytes, page.zipPath());

                zipOut.putNextEntry(new ZipEntry(entryName));
                zipOut.write(outputBytes);
                zipOut.closeEntry();
            }

            // ComicInfo.xml at root
            zipOut.putNextEntry(new ZipEntry(COMIC_INFO_ENTRY));
            zipOut.write(comicInfoXml);
            zipOut.closeEntry();
        }
    }

    private byte[] toJpegIfNeeded(byte[] imageBytes, String zipPath) {
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
                log.warn("EpubToCbzConversionService: Could not decode image '{}', copying raw bytes", zipPath);
                return imageBytes;
            }
            return encodeJpeg(image);
        } catch (Exception e) {
            log.warn("EpubToCbzConversionService: Error converting '{}': {}, copying raw bytes", zipPath, e.getMessage());
            return imageBytes;
        }
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgb.createGraphics().drawImage(image, 0, 0, null);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) throw new IOException("No JPEG writer available");
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
        }
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private Map<String, Integer> buildHrefToSpineIndex(Map<String, ManifestItem> manifest,
                                                        List<String> spine, String opfBaseDir) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < spine.size(); i++) {
            String idref = spine.get(i);
            ManifestItem item = manifest.get(idref);
            if (item != null) {
                String full = joinPath(opfBaseDir, item.href());
                map.put(full, i);
            }
        }
        return map;
    }

    private List<TocEntry> deduplicateBySpineIndex(List<TocEntry> entries) {
        // Keep only the first TOC entry per spine index and sort by spine index
        Map<Integer, TocEntry> seen = new LinkedHashMap<>();
        for (TocEntry e : entries) {
            seen.putIfAbsent(e.spineIndex(), e);
        }
        return seen.values().stream()
                .sorted(Comparator.comparingInt(TocEntry::spineIndex))
                .toList();
    }

    /** Resolves a relative href against a base directory, normalising '..' components. */
    private String normaliseHref(String baseDir, String href) {
        if (href.isEmpty()) return href;
        String combined = baseDir.isEmpty() ? href : (baseDir + "/" + href);
        // Normalise path segments
        List<String> parts = new ArrayList<>(Arrays.asList(combined.split("/")));
        Deque<String> resolved = new ArrayDeque<>();
        for (String part : parts) {
            if ("..".equals(part)) {
                if (!resolved.isEmpty()) resolved.pollLast();
            } else if (!".".equals(part) && !part.isEmpty()) {
                resolved.addLast(part);
            }
        }
        return String.join("/", resolved);
    }

    private String joinPath(String baseDir, String href) {
        if (href.startsWith("/")) return href.substring(1);
        return normaliseHref(baseDir, href);
    }

    private String parentDir(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash) : "";
    }

    private String sanitizeFolderName(String name) {
        // Replace characters illegal in ZIP entry names / common filesystem chars
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private Path replaceExtension(Path path, String ext) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        return path.getParent().resolve(base + "." + ext);
    }

    private org.w3c.dom.Document parseXmlSecurely(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Prevent XXE
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(in);
    }

    private String attrValue(Node node, String attrName) {
        if (node == null || node.getAttributes() == null) return null;
        Node attr = node.getAttributes().getNamedItem(attrName);
        return attr != null ? attr.getNodeValue() : null;
    }

    private String getTextContent(Node parent, String localName) {
        Node child = firstChildByLocalName(parent, localName);
        return child != null ? child.getTextContent() : null;
    }

    private Node firstChildByLocalName(Node parent, String localName) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (localName.equals(child.getLocalName())) return child;
        }
        return null;
    }
}
