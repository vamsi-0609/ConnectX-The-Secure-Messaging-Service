package com.connectx.media.storage;

import com.connectx.common.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * H-04: media upload used to trust the client-supplied Content-Type header outright, so a
 * "document" with Content-Type: text/html (or any other disallowed type) was stored and later
 * served back with that same header, which the frontend's "Open" flow renders as HTML in a
 * blob: URL -- a stored XSS with access to the app's own localStorage JWT. These tests exercise
 * LocalMediaStorage directly (no Spring context, no DB) since the validation logic is pure.
 */
class LocalMediaStorageValidationTest {

    private LocalMediaStorage newStorage() {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "connectx-media-test-" + System.nanoTime());
        return new LocalMediaStorage(tempDir.toString());
    }

    @Test
    void rejectsHtmlDisguisedAsDocument() {
        LocalMediaStorage storage = newStorage();
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.html", "text/html",
                "<!DOCTYPE html><html><body><script>alert(document.cookie)</script></body></html>"
                        .getBytes(StandardCharsets.UTF_8));

        ApiException ex = assertThrows(ApiException.class, () -> storage.store("key1", file));
        assertEquals("UNSUPPORTED_MEDIA_TYPE", ex.getCode());
    }

    @Test
    void rejectsSvgWithEmbeddedScript() {
        LocalMediaStorage storage = newStorage();
        MockMultipartFile file = new MockMultipartFile(
                "file", "picture.svg", "image/svg+xml",
                "<svg onload=\"alert(1)\"></svg>".getBytes(StandardCharsets.UTF_8));

        ApiException ex = assertThrows(ApiException.class, () -> storage.store("key2", file));
        assertEquals("UNSUPPORTED_MEDIA_TYPE", ex.getCode());
    }

    @Test
    void rejectsContentTypeSpoofedAsAllowedTypeWhenBytesAreActuallyHtml() {
        LocalMediaStorage storage = newStorage();
        // Claims to be a PNG (an allowed type) but the actual bytes are HTML -- the whitelist
        // alone wouldn't catch this; the magic-byte signature check is what rejects it.
        MockMultipartFile file = new MockMultipartFile(
                "file", "totally-a-photo.png", "image/png",
                "<html><body><script>alert(1)</script></body></html>".getBytes(StandardCharsets.UTF_8));

        ApiException ex = assertThrows(ApiException.class, () -> storage.store("key3", file));
        assertEquals("MEDIA_TYPE_MISMATCH", ex.getCode());
    }

    @Test
    void acceptsGenuinePng() {
        LocalMediaStorage storage = newStorage();
        byte[] pngBytes = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03 };
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", pngBytes);

        MediaStorage.StoredMediaFile stored = storage.store("key4", file);
        assertEquals("image/png", stored.mimeType());
    }

    @Test
    void acceptsGenuinePdf() {
        LocalMediaStorage storage = newStorage();
        byte[] pdfBytes = "%PDF-1.4\n%rest of a pdf".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", pdfBytes);

        MediaStorage.StoredMediaFile stored = storage.store("key5", file);
        assertEquals("application/pdf", stored.mimeType());
    }

    @Test
    void acceptsGenuineDocxByZipSignatureWithOctetStreamContentType() {
        LocalMediaStorage storage = newStorage();
        // Browsers sometimes fail to set a precise Content-Type for less common extensions --
        // falls back to filename-based detection, still gets checked against real bytes.
        byte[] zipBytes = { 0x50, 0x4B, 0x03, 0x04, 0x00, 0x00 };
        MockMultipartFile file = new MockMultipartFile("file", "report.docx", "application/octet-stream", zipBytes);

        MediaStorage.StoredMediaFile stored = storage.store("key6", file);
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", stored.mimeType());
    }

    @Test
    void acceptsPlainTextWithoutMarkup() {
        LocalMediaStorage storage = newStorage();
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "just some plain notes, nothing weird here".getBytes(StandardCharsets.UTF_8));

        MediaStorage.StoredMediaFile stored = storage.store("key7", file);
        assertEquals("text/plain", stored.mimeType());
    }

    @Test
    void rejectsUnknownBinaryType() {
        LocalMediaStorage storage = newStorage();
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.exe", "application/x-msdownload", new byte[]{0x4D, 0x5A, 0x00, 0x00});

        ApiException ex = assertThrows(ApiException.class, () -> storage.store("key8", file));
        assertEquals("UNSUPPORTED_MEDIA_TYPE", ex.getCode());
    }
}
