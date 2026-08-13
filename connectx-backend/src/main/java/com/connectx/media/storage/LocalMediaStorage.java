package com.connectx.media.storage;

import com.connectx.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;

@Service
public class LocalMediaStorage implements MediaStorage {

    private static final long MAX_BYTES = 10L * 1024L * 1024L;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp"
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

        String contentType = normalizeContentType(file.getContentType());
        String extension = extensionForContentType(contentType);
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
        Path imagePath = resolveExistingPath(storageKey, mimeType);
        if (imagePath == null || !Files.isRegularFile(imagePath)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "Media file was not found");
        }
        return new FileSystemResource(imagePath);
    }

    @Override
    public void delete(String storageKey, String mimeType) {
        Path imagePath = resolveExistingPath(storageKey, mimeType);
        if (imagePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(imagePath);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_DELETE_FAILED", "Failed to delete media file");
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "Media file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MEDIA_TOO_LARGE", "Image must be 10 MB or smaller");
        }

        String contentType = normalizeContentType(file.getContentType());
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA_TYPE", "Only JPG, PNG, and WEBP images are allowed");
        }

        try (InputStream inputStream = file.getInputStream()) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "Uploaded file is not a valid image");
            }
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "Uploaded file is not a valid image");
        }
    }

    private Path resolveStoragePath(String storageKey, String extension) {
        if (storageKey == null || storageKey.isBlank() || storageKey.contains("..") || storageKey.contains("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "Invalid media storage key");
        }

        Path target = rootDirectory.resolve(storageKey + "." + extension).normalize();
        if (!target.startsWith(rootDirectory)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA", "Invalid media storage path");
        }
        return target;
    }

    private Path resolveExistingPath(String storageKey, String mimeType) {
        for (String extension : extensionsForMimeType(mimeType)) {
            Path candidate = resolveStoragePath(storageKey, extension);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String[] extensionsForMimeType(String mimeType) {
        return switch (normalizeContentType(mimeType)) {
            case "image/png" -> new String[] { "png" };
            case "image/webp" -> new String[] { "webp" };
            default -> new String[] { "jpg", "jpeg" };
        };
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        return contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
    }

    private String extensionForContentType(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }
}
