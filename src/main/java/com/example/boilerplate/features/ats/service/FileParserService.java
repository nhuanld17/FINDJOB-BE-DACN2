package com.example.boilerplate.features.ats.service;

import com.example.boilerplate.common.constant.ErrorCode;
import com.example.boilerplate.common.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Service trích xuất text từ file CV (PDF hoặc DOCX).
 * 
 * Hỗ trợ:
 * 
 *   - PDF (.pdf) — dùng Apache PDFBox 2.0.37
 *   - Word (.docx) — dùng Apache POI 5.4.0
 * 
 * 
 * ⚠️ File user upload là INPUT KHÔNG TIN CẬY → có tầng phòng thủ:
 * 
 *   - Kích thước: Spring multipart cap 10MB (application.yml)
 *   - Số trang PDF: tối đa {@value #MAX_PDF_PAGES} trang (chặn DoS bằng
 *       PDF giả mạo hàng nghìn trang làm treo CPU/RAM)
 *   - Bộ nhớ: PDFBox load theo chế độ temp-file (tránh OOM)
 *   - Độ dài text: truncate ~12k ký tự (tránh vượt token window của LLM)
 * 
 * Nếu file là ảnh scan (không có text layer) → trả text rỗng → service gọi sẽ
 * throw {@link AppException} với {@link ErrorCode#ATS_CV_EMPTY}.
 */
@Slf4j
@Service
public class FileParserService {

    /** Độ dài text tối thiểu để coi là "có thể đọc được" (tránh ảnh scan). */
    private static final int MIN_TEXT_LENGTH = 100;

    /** Độ dài text tối đa trích xuất (tránh vượt token window của LLM). */
    private static final int MAX_TEXT_LENGTH = 12_000;

    /**
     * Số trang PDF tối đa chấp nhận.
     * CV thật thường 1–5 trang; 50 là giới hạn rất rộng, đủ chặn DoS mà không
     * ảnh hưởng user bình thường.
     */
    private static final int MAX_PDF_PAGES = 50;

    /**
     * Trích xuất text từ file CV, kiểm tra tính hợp lệ và cắt bớt nếu quá dài.
     *
     * @param file File CV (PDF hoặc DOCX)
     * @return Text đã trích xuất, đã truncate ~12k ký tự
     * @throws AppException nếu file không đọc được / text rỗng / quá lớn
     */
    public String extractText(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.ATS_CV_REQUIRED);
        }

        // Kiểm tra content-type hoặc extension
        String contentType = file.getContentType();
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new AppException(ErrorCode.ATS_CV_INVALID_NAME);
        }

        String text;
        try {
            if (contentType != null && contentType.contains("pdf")
                    || originalFilename.toLowerCase().endsWith(".pdf")) {
                text = extractPdfText(file);
            } else if (contentType != null
                    && (contentType.contains("word") || contentType.contains("officedocument"))
                    || originalFilename.toLowerCase().endsWith(".docx")) {
                text = extractDocxText(file);
            } else {
                throw new AppException(ErrorCode.ATS_CV_UNSUPPORTED);
            }
        } catch (IOException e) {
            // Chi tiết lỗi thật (đường dẫn file, exception detail) chỉ log server-side,
            // không đưa vào message trả client (tránh leak thông tin hệ thống).
            log.warn("Cannot read CV file: {}", e.getMessage());
            throw new AppException(ErrorCode.ATS_CV_UNREADABLE);
        }

        // Kiểm tra text rỗng (ảnh scan) — message mặc định của ATS_CV_EMPTY đã mô tả đủ
        if (text == null || text.trim().length() < MIN_TEXT_LENGTH) {
            throw new AppException(ErrorCode.ATS_CV_EMPTY);
        }

        // Truncate nếu quá dài
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }

        return text.trim();
    }

    private String extractPdfText(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream();
             PDDocument document = PDDocument.load(is, MemoryUsageSetting.setupTempFileOnly())) {

            // Chặn DoS: PDF vài nghìn trang làm treo CPU/RAM khi stripper duyệt
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                // Số trang cụ thể (max 50) chỉ log server-side, client nhận message mặc định
                log.warn("CV has too many pages: {} (max {})", document.getNumberOfPages(), MAX_PDF_PAGES);
                throw new AppException(ErrorCode.ATS_CV_TOO_LARGE);
            }

            // Defense-in-depth: chỉ trích xuất trong khoảng trang cho phép
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(MAX_PDF_PAGES);
            return stripper.getText(document);
        }
    }

    /**
     * Trích xuất text từ file DOCX bằng Apache POI.
     */
    private String extractDocxText(MultipartFile file) throws IOException {
        try (InputStream is = file.getInputStream();
             XWPFDocument document = new XWPFDocument(is);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }
}