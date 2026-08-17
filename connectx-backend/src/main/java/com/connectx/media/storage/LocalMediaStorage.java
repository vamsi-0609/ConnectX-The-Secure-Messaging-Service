package com.connectx.media.storage;

import com.connectx.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;

@Service
public class LocalMediaStorage implements MediaStorage {

    private static final long MAX_BYTES = 50L * 1024L * 1024L;

    // Only bytes actually needed for magic-number checks below (WAV/WEBP's "WAVE"/"WEBP"
    // marker is the deepest, at offset 8-11); kept small since it's read into memory for
    // every upload.
    private static final int HEADER_PEEK_BYTES = 64;

    // H-04: the upload endpoint used to trust file.getContentType() (fully client-controlled,
    // whether from the browser's file picker or a hand-crafted multipart request) with no
    // whitelist at all -- an attacker could upload a file with Content-Type: text/html, have
    // it stored and served back with that same Content-Type via GET /media/{id}, and the
    // frontend's "Open" action (blob URL + window.open, see DocumentMessageContent.tsx) would
    // then render it as HTML in the app's own origin -- a stored XSS with access to the
    // logged-in user's localStorage JWT. Only types the app actually needs are allowed here.
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/csv",
            "application/zip",
            "video/mp4",
            "audio/mpeg",
            "audio/ogg",
            "audio/wav"
    );

    private final Path rootDirectory;

    public LocalMediaStorage(@Value("${connectx.upload.media-dir:uploads/media}") String mediaDir) {
        this.rootDirectory = Path.of(mediaDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to initialize media storage directory", ex);
        }
    }

    @Override
    public StoredMediaFile store(String storageKey, MultipartFile file) {
        validateUpload(file);

        String contentType = resolveContentType(file);
        byte[] header = readHeader(file);
        validateMimeType(contentType, header);

        String extension = resolveFileExtension(file.getOriginalFilename(), contentType);
        Path target = resolveStoragePath(storageKey, extension);

        try {
            Files.createDirectories(target.getParent());
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_STORE_FAILED", "Failed to store media file");
        }

        return new StoredMediaFile(storageKey, contentType, file.getSize());
    }

    @Override
    public Resource load(String storageKey, String mimeType) {
        Path mediaPath = resolveExistingPath(storageKey, mimeType);
        if (mediaPath == null || !Files.isRegularFile(mediaPath)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "Media file was not found");
        }
        return new FileSystemResource(mediaPath);
    }

    @Override
    public void delete(String storageKey, String mimeType) {
        Path mediaPath = resolveExistingPath(storageKey, mimeType);
        if (mediaPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(mediaPath);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_DELETE_FAILED", "Failed to delete media file");
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "Media file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MEDIA_TOO_LARGE", "File must be 50 MB or smaller");
        }
    }

    /**
     * Resolves the content type to trust: the client-supplied header when it's present and
     * meaningful, otherwise a best-effort guess from the filename extension. Either way, the
     * result is still just a claim -- {@link #validateMimeType} is what actually verifies it
     * against the file's real bytes before anything is written to disk.
     */
    private String resolveContentType(MultipartFile file) {
        String contentType = normalizeContentType(file.getContentType());
        if (!contentType.isBlank() && !contentType.equals("application/octet-stream")) {
            return contentType;
        }
        String byExtension = contentTypeFromExtension(file.getOriginalFilename());
        return byExtension != null ? byExtension : "application/octet-stream";
    }

    private String contentTypeFromExtension(String originalFilename) {
        if (originalFilename == null) {
            return null;
        }
        String lower = originalFilename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        return null;
    }

    private byte[] readHeader(MultipartFile file) {
        byte[] buffer = new byte[HEADER_PEEK_BYTES];
        try (InputStream in = file.getInputStream()) {
            int totalRead = 0;
            int n;
            while (totalRead < buffer.length && (n = in.read(buffer, totalRead, buffer.length - totalRead)) != -1) {
                totalRead += n;
            }
            return totalRead == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, totalRead);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "Unable to read media file");
        }
    }

    private void validateMimeType(String contentType, byte[] header) {
        if (!ALLOWED_MIME_TYPES.contains(contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_MEDIA_TYPE",
                    "File type '" + contentType + "' is not supported");
        }
        if (!matchesSignature(contentType, header)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MEDIA_TYPE_MISMATCH",
                    "File content does not match its declared type");
        }
    }

    /**
     * Confirms the file's actual bytes are consistent with the claimed MIME type, so a request
     * can't simply relabel disallowed content (e.g. HTML) as an allowed type to slip past the
     * whitelist above. Office Open XML formats (docx/xlsx/pptx) and plain zip are all
     * indistinguishable at the container level -- all are just zip archives -- so they share one
     * signature check; legacy binary Office formats (doc/xls/ppt) share the OLE/CFB signature.
     * Plain text has no byte signature to check, so it instead gets a heuristic reject on
     * content that looks like markup (the concrete attack this bug is about).
     */
    private boolean matchesSignature(String contentType, byte[] header) {
        return switch (contentType) {
            case "image/png" -> startsWith(header, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/jpeg", "image/jpg" -> startsWith(header, 0xFF, 0xD8, 0xFF);
            case "image/gif" -> startsWith(header, 'G', 'I', 'F', '8');
            case "image/webp" -> startsWith(header, 'R', 'I', 'F', 'F') && matchesAt(header, 8, 'W', 'E', 'B', 'P');
            case "application/pdf" -> startsWith(header, '%', 'P', 'D', 'F', '-');
            case "application/zip",
                 "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                 "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                 "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                    startsWith(header, 0x50, 0x4B, 0x03, 0x04)
                            || startsWith(header, 0x50, 0x4B, 0x05, 0x06)
                            || startsWith(header, 0x50, 0x4B, 0x07, 0x08);
            case "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint" ->
                    startsWith(header, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
            case "video/mp4" -> matchesAt(header, 4, 'f', 't', 'y', 'p');
            case "audio/mpeg" -> startsWith(header, 'I', 'D', '3')
                    || (header.length >= 2 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xE0) == 0xE0);
            case "audio/ogg" -> startsWith(header, 'O', 'g', 'g', 'S');
            case "audio/wav" -> startsWith(header, 'R', 'I', 'F', 'F') && matchesAt(header, 8, 'W', 'A', 'V', 'E');
            case "text/plain", "text/csv" -> !looksLikeMarkup(header);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] data, int... expected) {
        if (data.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAt(byte[] data, int offset, int... expected) {
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean looksLikeMarkup(byte[] header) {
        // ISO-8859-1 maps each byte to one char 1:1 with no decode failures, which is all
        // that's needed here -- this only has to recognize plain-ASCII markup tokens.
        String snippet = new String(header, StandardCharsets.ISO_8859_1).stripLeading().toLowerCase(Locale.ROOT);
        return snippet.startsWith("<!doctype html")
                || snippet.startsWith("<html")
                || snippet.startsWith("<script")
                || snippet.startsWith("<?xml")
                || snippet.startsWith("<svg");
    }

    private Path resolveStoragePath(String storageKey, String extension) {
        if (storageKey == null || storageKey.isBlank() || storageKey.contains("..") || storageKey.contains("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "Invalid media storage key");
        }

        String fileName = (extension == null || extension.isBlank()) ? storageKey : (storageKey + "." + extension);
        Path target = rootDirectory.resolve(fileName).normalize();
        if (!target.startsWith(rootDirectory)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "Invalid media storage path");
        }
        return target;
    }

    private Path resolveExistingPath(String storageKey, String mimeType) {
        if (storageKey == null || storageKey.isBlank() || storageKey.contains("..") || storageKey.contains("/")) {
            return null;
        }

        Path exactPath = rootDirectory.resolve(storageKey).normalize();
        if (exactPath.startsWith(rootDirectory) && Files.isRegularFile(exactPath)) {
            return exactPath;
        }

        try (var stream = Files.newDirectoryStream(rootDirectory, storageKey + ".*")) {
            for (Path entry : stream) {
                if (entry.startsWith(rootDirectory) && Files.isRegularFile(entry)) {
                    return entry;
                }
            }
        } catch (IOException ignored) {
        }

        return null;
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        return contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
    }

    private String resolveFileExtension(String originalFilename, String contentType) {
        if (originalFilename != null && originalFilename.contains(".")) {
            String ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT).trim();
            if (ext.matches("^[a-z0-9]{1,10}$")) {
                return ext;
            }
        }
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "application/pdf" -> "pdf";
            case "text/plain" -> "txt";
            case "application/zip" -> "zip";
            case "video/mp4" -> "mp4";
            case "audio/mpeg" -> "mp3";
            default -> "bin";
        };
    }
}
