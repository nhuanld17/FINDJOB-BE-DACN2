package com.example.boilerplate.infrastructure.cloudinary;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Service xử lý upload file lên Cloudinary CDN.
 *
 * Hỗ trợ upload ảnh đại diện, ảnh bìa công ty, CV, ...
 *
 * Usage:
 *   String url = cloudinaryService.uploadFile(file, "companies/logos", CloudinaryService.IMAGE_TYPES);
 *
 * Lưu ý: Dùng {@link MultipartFile#getBytes()} để upload — phù hợp với file nhỏ
 * (dưới 5MB như logo, ảnh bìa). Với file lớn hơn, cần refactor sang InputStream.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private final Cloudinary cloudinary;

    /**
     * Upload file lên Cloudinary.
     *
     * @param file   File cần upload (từ MultipartFile)
     * @param folder Thư mục trên Cloudinary (VD: "companies/logos", "companies/covers")
     * @return URL của file đã upload
     * @throws AppException nếu file rỗng, sai content-type hoặc upload thất bại
     */
    public String uploadFile(MultipartFile file, String folder, Set<String> allowedContentTypes) {
        validateFile(file, allowedContentTypes);
        try {
            Map<?, ?> uploadResult = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", folder,
                            "resource_type", "auto"
                    )
            );
            String url = uploadResult.get("url").toString();
            log.info("Uploaded file to Cloudinary: folder={}, url={}", folder, url);
            return url;
        } catch (IOException e) {
            log.error("Failed to upload file to Cloudinary: folder={}", folder, e);
            throw new AppException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Xóa file trên Cloudinary.
     *
     * Chấp nhận cả publicId lẫn URL đầy đủ:
     * 
     *   - {@code deleteFile("companies/logos/abc123")} — publicId thuần
     *   - {@code deleteFile("https://res.cloudinary.com/.../abc123.jpg")} — URL, tự extract publicId
     * 
     *
     * @param urlOrPublicId Public ID hoặc URL Cloudinary của file cần xóa
     */
    public void deleteFile(String urlOrPublicId) {
        String publicId = urlOrPublicId.contains("res.cloudinary.com/")
                ? extractPublicIdFromUrl(urlOrPublicId)
                : urlOrPublicId;
        try {
            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
            log.info("Deleted file from Cloudinary: publicId={}", publicId);
        } catch (IOException e) {
            log.error("Failed to delete file from Cloudinary: publicId={}", publicId, e);
            throw new AppException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Trích xuất publicId từ URL Cloudinary.
     *
     * VD: {@code https://res.cloudinary.com/demo/image/upload/v12345/companies/logos/abc123.jpg}
     * → {@code companies/logos/abc123}
     *
     * Lưu ý: Chỉ xử lý URL không chứa transformation segment
     * (VD: {@code /upload/c_fill,w_300/v12345/...}).
     * Nếu sau này cần, phải mở rộng hàm này để skip transformation segment.
     */
    private String extractPublicIdFromUrl(String url) {
        // Cắt từ /upload/ đến hết URL (bỏ qua version v{nếu có})
        String uploadMarker = "/upload/";
        int uploadIndex = url.indexOf(uploadMarker);
        if (uploadIndex == -1) {
            throw new AppException(ErrorCode.INTERNAL_ERROR);
        }

        String afterUpload = url.substring(uploadIndex + uploadMarker.length());

        // Bỏ version segment nếu có (VD: v12345/)
        // Cloudinary version luôn có format v{chữ số}+ (VD: v1234567890)
        // Dùng regex để tránh false positive với folder tên "videos", "version", ...
        int firstSlash = afterUpload.indexOf('/');
        String firstSegment = firstSlash != -1
                ? afterUpload.substring(0, firstSlash)
                : afterUpload;
        if (firstSlash != -1 && firstSegment.matches("v\\d+")) {
            afterUpload = afterUpload.substring(firstSlash + 1);
        }

        // Bỏ extension (.jpg, .png, ...)
        int dotIndex = afterUpload.lastIndexOf('.');
        if (dotIndex != -1) {
            afterUpload = afterUpload.substring(0, dotIndex);
        }

        return afterUpload;
    }

    /**
     * Upload file và trả về cả url + publicId.
     * Dùng khi cần xóa file cũ sau này.
     */
    public UploadResult uploadWithDetails(MultipartFile file, String folder, Set<String> allowedContentTypes) {
        validateFile(file, allowedContentTypes);
        try {
            Map<?, ?> uploadResult = cloudinary.uploader().upload(
                    file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", folder,
                            "resource_type", "auto"
                    )
            );
            UploadResult result = new UploadResult(
                    uploadResult.get("url").toString(),
                    uploadResult.get("public_id").toString()
            );
            log.info("Uploaded file to Cloudinary: folder={}, publicId={}", folder, result.publicId());
            return result;
        } catch (IOException e) {
            log.error("Failed to upload file to Cloudinary: folder={}", folder, e);
            throw new AppException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * Kiểm tra file upload hợp lệ.
     *
     * @param file                File cần kiểm tra
     * @param allowedContentTypes Danh sách content-type được phép (VD: image/jpeg, application/pdf)
     * @throws AppException nếu file null, rỗng hoặc sai content-type
     */
    private void validateFile(MultipartFile file, Set<String> allowedContentTypes) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.BLANK_FIELD);
        }

        String contentType = file.getContentType();
        if (contentType == null || !allowedContentTypes.contains(contentType)) {
            log.warn("Rejected file with content-type '{}' (allowed: {})", contentType, allowedContentTypes);
            throw new AppException(ErrorCode.BLANK_FIELD);
        }
    }

    // ────────────────────── Content-type constants ──────────────────────

    /** Các content-type được phép cho ảnh (avatar, logo, cover). */
    public static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );

    /** Các content-type được phép cho CV. */
    public static final Set<String> PDF_TYPE = Set.of(
            "application/pdf"
    );

    /**
     * Kết quả upload chứa URL và publicId.
     */
    public record UploadResult(
            String url,
            String publicId
    ) {}
}
