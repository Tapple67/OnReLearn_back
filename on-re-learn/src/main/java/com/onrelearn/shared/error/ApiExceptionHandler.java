package com.onrelearn.shared.error;

import com.onrelearn.auth.service.AuthenticationConfigurationException;
import com.onrelearn.auth.service.DuplicateEmailException;
import com.onrelearn.semester.service.GradeNotFoundException;
import com.onrelearn.material.storage.StorageUnavailableException;
import com.onrelearn.material.service.MaterialPersistenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {
    // * Controller와 Service가 던진 예상 가능한 실패를 기존 {"error": ...} 응답으로 모은다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> invalidJson(HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, "입력 누락 또는 잘못된 형식");
    }

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiError> duplicateEmail(DuplicateEmailException exception) {
        return error(HttpStatus.CONFLICT, "이미 존재하는 이메일");
    }

    @ExceptionHandler(GradeNotFoundException.class)
    public ResponseEntity<ApiError> gradeNotFound(GradeNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "해당 학기를 찾을 수 없습니다.");
    }

    @ExceptionHandler(AuthenticationConfigurationException.class)
    public ResponseEntity<ApiError> authenticationConfiguration(AuthenticationConfigurationException exception) {
        // ! 설정 오류의 내부 메시지나 비밀값은 응답에 포함하지 않는다.
        return error(HttpStatus.SERVICE_UNAVAILABLE, "인증 설정을 확인해 주세요.");
    }

    @ExceptionHandler(StorageUnavailableException.class)
    public ResponseEntity<ApiError> storageUnavailable(StorageUnavailableException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "자료 저장소를 사용할 수 없습니다.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return error(org.springframework.http.HttpStatusCode.valueOf(413), "PDF 파일 크기 제한을 초과했습니다.");
    }

    @ExceptionHandler(MaterialPersistenceException.class)
    public ResponseEntity<ApiError> materialPersistence(MaterialPersistenceException exception) {
        // ! 예상 밖의 DB 실패는 500으로 알리되 SQL·내부 예외 원문은 노출하지 않는다.
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "자료를 저장하지 못했습니다. 다시 시도해 주세요.");
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ApiError> databaseFailure(org.springframework.dao.DataAccessException exception) {
        // ! DB 오류를 입력 오류로 숨기거나 SQL·노트 내용을 응답에 노출하지 않는다.
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "데이터를 처리하지 못했습니다. 다시 시도해 주세요.");
    }

    @ExceptionHandler(com.onrelearn.quiz.service.AttemptAlreadySubmittedException.class)
    public ResponseEntity<SubmittedAttemptError> attemptAlreadySubmitted(
            com.onrelearn.quiz.service.AttemptAlreadySubmittedException exception) {
        // ! The start service verifies ownership before this identifier can be returned.
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new SubmittedAttemptError("Quiz already submitted", "ALREADY_SUBMITTED", exception.getAttemptId()));
    }
    public record SubmittedAttemptError(String error,String code,String attemptId) {}

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> expectedHttpFailure(ResponseStatusException exception) {
        String message = exception.getReason();
        return message == null ? ResponseEntity.status(exception.getStatusCode()).build()
            : error(exception.getStatusCode(), message);
    }

    private ResponseEntity<ApiError> error(org.springframework.http.HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(new ApiError(message));
    }

    public record ApiError(String error) {}
}
