package com.company.yoga.common.exception;

import com.company.yoga.common.api.ApiResponse;
import com.company.yoga.common.api.CommonErrorCode;
import com.company.yoga.common.api.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode ec = ex.getErrorCode();
        String message = resolveMessage(ec.getKey(), ex.getArgs(), ex.getMessage());
        log.warn("Nghiệp vụ từ chối [{}]: {}", ec.getCode(), message);
        return ResponseEntity.status(ec.getHttpStatusCode())
                .body(ApiResponse.failure(ec.getCode(), message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Dữ liệu đầu vào không hợp lệ: {}", details);
        return ResponseEntity.status(CommonErrorCode.VALIDATION_FAILED.getHttpStatusCode())
                .body(ApiResponse.failure(CommonErrorCode.VALIDATION_FAILED.getCode(), details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("Tham số request không hợp lệ: {}", ex.getMessage());
        return ResponseEntity.status(CommonErrorCode.VALIDATION_FAILED.getHttpStatusCode())
                .body(ApiResponse.failure(CommonErrorCode.VALIDATION_FAILED.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateKeyException(DuplicateKeyException ex) {
        log.warn("Vi phạm khóa duy nhất (Duplicate Key): {}", ex.getMessage());
        return ResponseEntity.status(CommonErrorCode.CONFLICT.getHttpStatusCode())
                .body(ApiResponse.failure(CommonErrorCode.CONFLICT));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException ex) {
        log.warn("Từ chối truy cập: {}", ex.getMessage());
        return ResponseEntity.status(CommonErrorCode.FORBIDDEN.getHttpStatusCode())
                .body(ApiResponse.failure(CommonErrorCode.FORBIDDEN));
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class, org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class, org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(CommonErrorCode.BAD_REQUEST));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntegrity(org.springframework.dao.DataIntegrityViolationException ex) {
        log.warn("Database rejected invalid or conflicting data: {}", ex.getMessage());
        String msg = extractDbErrorMessage(ex);
        return ResponseEntity.status(409).body(ApiResponse.failure("CONFLICT", msg != null ? msg : "Dữ liệu xung đột hoặc không thỏa mãn ràng buộc hệ thống"));
    }

    @ExceptionHandler(org.springframework.transaction.TransactionSystemException.class)
    public ResponseEntity<ApiResponse<Void>> handleTransactionSystemException(org.springframework.transaction.TransactionSystemException ex) {
        log.warn("Lỗi giao dịch TransactionSystemException: {}", ex.getMessage());
        String msg = extractDbErrorMessage(ex);
        return ResponseEntity.status(400).body(ApiResponse.failure("TRANSACTION_FAILED", msg != null ? msg : "Giao dịch không thành công do vi phạm ràng buộc nghiệp vụ"));
    }

    private String extractDbErrorMessage(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String m = cur.getMessage();
            if (m != null) {
                if (m.contains("ERROR:") || m.contains("Exception:")) {
                    // Extract message after ERROR: or Exception:
                    int idx = m.indexOf("ERROR: ");
                    if (idx != -1) {
                        int end = m.indexOf('\n', idx);
                        return end != -1 ? m.substring(idx + 7, end).trim() : m.substring(idx + 7).trim();
                    }
                }
            }
            cur = cur.getCause();
        }
        return null;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {
        log.error("Lỗi máy chủ không xác định: ", ex);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_SERVER_ERROR.getHttpStatusCode())
                .body(ApiResponse.failure(CommonErrorCode.INTERNAL_SERVER_ERROR));
    }

    private String resolveMessage(String key, Object[] args, String defaultMessage) {
        if (key == null || messageSource == null) {
            return defaultMessage;
        }
        try {
            return messageSource.getMessage(key, args, defaultMessage, LocaleContextHolder.getLocale());
        } catch (Exception e) {
            return defaultMessage;
        }
    }
}
