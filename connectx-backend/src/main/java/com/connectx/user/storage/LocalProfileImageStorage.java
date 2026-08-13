package com.connectx.user.storage;

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

@Service
public class LocalProfileImageStorage implements ProfileImageStorage {

    private static final long MAX_BYTES = 15L * 1024L * 1024L;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp"
    );

    private final Path rootDirectory;

    public LocalProfileImageStorage(@Value("${connectx.upload.profile-dir:uploads/profiles}") String profileDir) {
        this.rootDirectory = Path.of(profileDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootDirectory);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to initialize profile image storage directory", ex);
        }
    }

    @Override
    public String store(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "Profile photo file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMAGE_TOO_LARGE", "Profile photo must be 15 MB or smaller");
        }

        String contentType = normalizeContentType(file.getContentType());
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE_TYPE", "Only JPG, PNG, and WEBP images are allowed");
        }

        validateImageContent(file);

        String extension = extensionForContentType(contentType);
        Path userDirectory = resolveUserDirectory(userId);
        try {
            Files.createDirectories(userDirectory);
            deleteExistingImages(userDirectory);

            Path target = userDirectory.resolve("profile." + extension).normalize();
            if (!target.startsWith(userDirectory)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "Invalid profile photo path");
            }

            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_STORE_FAILED", "Failed to store profile photo");
        }

        return "/api/v1/profile-images/" + userId;
    }

    @Override
    public void delete(Long userId) {
        Path userDirectory = resolveUserDirectory(userId);
        try {
            if (Files.exists(userDirectory)) {
                deleteExistingImages(userDirectory);
                Files.deleteIfExists(userDirectory);
            }
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_DELETE_FAILED", "Failed to remove profile photo");
        }
    }

    public Path resolveStoredImagePath(Long userId) {
        Path userDirectory = resolveUserDirectory(userId);
        for (String extension : new String[] { "jpg", "jpeg", "png", "webp" }) {
            Path candidate = userDirectory.resolve("profile." + extension).normalize();
            if (candidate.startsWith(userDirectory) && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Path resolveUserDirectory(Long userId) {
        Path userDirectory = rootDirectory.resolve(String.valueOf(userId)).normalize();
        if (!userDirectory.startsWith(rootDirectory)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "Invalid profile photo path");
        }
        return userDirectory;
    }

    private void deleteExistingImages(Path userDirectory) throws IOException {
        if (!Files.exists(userDirectory)) {
            return;
        }
        try (var paths = Files.list(userDirectory)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort cleanup before replacing the profile image.
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
