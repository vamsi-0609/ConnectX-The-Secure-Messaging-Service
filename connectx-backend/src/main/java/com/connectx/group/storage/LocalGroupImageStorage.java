package com.connectx.group.storage;

import com.connectx.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
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

/**
 * Mirrors LocalProfileImageStorage exactly (same size/type limits, same on-disk layout strategy,
 * same path-traversal guard) but keyed by group (conversationId) instead of userId, storing under
 * a completely separate root directory -- a group avatar must never be reachable through, or
 * confusable with, a user's profile-photo storage.
 */
@Service
public class LocalGroupImageStorage implements GroupImageStorage {

    private static final long MAX_BYTES = 15L * 1024L * 1024L;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp"
    );

    private final Path rootDirectory;

    public LocalGroupImageStorage(@Value("${connectx.upload.group-dir:uploads/groups}") String groupDir) {
        this.rootDirectory = Path.of(groupDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to initialize group image storage directory", ex);
        }
    }

    @Override
    public String store(Long groupId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "Group photo file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_TOO_LARGE", "Group photo must be 15 MB or smaller");
        }

        String contentType = normalizeContentType(file.getContentType());
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE_TYPE", "Only JPG, PNG, and WEBP images are allowed");
        }

        validateImageContent(file);

        String extension = extensionForContentType(contentType);
        Path groupDirectory = resolveGroupDirectory(groupId);
        try {
            Files.createDirectories(groupDirectory);
            deleteExistingImages(groupDirectory);

            Path target = groupDirectory.resolve("avatar." + extension).normalize();
            if (!target.startsWith(groupDirectory)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "Invalid group photo path");
            }

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_STORE_FAILED", "Failed to store group photo");
        }

        return "/api/v1/group-images/" + groupId;
    }

    @Override
    public void delete(Long groupId) {
        Path groupDirectory = resolveGroupDirectory(groupId);
        try {
            if (Files.exists(groupDirectory)) {
                deleteExistingImages(groupDirectory);
                Files.deleteIfExists(groupDirectory);
            }
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_DELETE_FAILED", "Failed to remove group photo");
        }
    }

    public Path resolveStoredImagePath(Long groupId) {
        Path groupDirectory = resolveGroupDirectory(groupId);
        for (String extension : new String[] { "jpg", "jpeg", "png", "webp" }) {
            Path candidate = groupDirectory.resolve("avatar." + extension).normalize();
            if (candidate.startsWith(groupDirectory) && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Path resolveGroupDirectory(Long groupId) {
        Path groupDirectory = rootDirectory.resolve(String.valueOf(groupId)).normalize();
        if (!groupDirectory.startsWith(rootDirectory)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "Invalid group photo path");
        }
        return groupDirectory;
    }

    private void deleteExistingImages(Path groupDirectory) throws IOException {
        if (!Files.exists(groupDirectory)) {
            return;
        }
        try (var paths = Files.list(groupDirectory)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort cleanup before replacing the group avatar.
                }
            });
        }
    }

    private void validateImageContent(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "Uploaded file is not a valid image");
            }
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "Uploaded file is not a valid image");
        }
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
