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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

@Service
public class LocalMediaStorage implements MediaStorage {

    private static final long MAX_BYTES = 50L * 1024L * 1024L;

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

        String rawContentType = file.getContentType();
        String contentType = normalizeContentType(rawContentType);
        if (contentType.isBlank() || contentType.equals("application/octet-stream")) {
            String originalName = file.getOriginalFilename();
            if (originalName != null) {
                String lower = originalName.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".docx")) {
                    contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                } else if (lower.endsWith(".doc")) {
                    contentType = "application/msword";
                } else if (lower.endsWith(".pdf")) {
                    contentType = "application/pdf";
                }
            }
            if (contentType.isBlank()) {
                contentType = "application/octet-stream";
            }
        }

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
