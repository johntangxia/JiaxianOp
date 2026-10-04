package com.jiaxian.analytics.pointsconfig;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 只作用于积分权重接口：参数问题 400，不存在 404，冲突 409，响应体是一句说明原因的文字。 */
@RestControllerAdvice(assignableTypes = PointsConfigController.class)
public class PointsConfigExceptionHandler {

    @ExceptionHandler(InvalidPointsConfigException.class)
    public ResponseEntity<String> invalid(InvalidPointsConfigException e) {
        return text(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> typeMismatch(MethodArgumentTypeMismatchException e) {
        return text(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> unreadableBody(HttpMessageNotReadableException e) {
        return text(HttpStatus.BAD_REQUEST, "Request body must be JSON like {\"weight\": 5}");
    }

    @ExceptionHandler(PointsConfigNotFoundException.class)
    public ResponseEntity<String> notFound(PointsConfigNotFoundException e) {
        return text(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PointsConfigConflictException.class)
    public ResponseEntity<String> conflict(PointsConfigConflictException e) {
        return text(HttpStatus.CONFLICT, e.getMessage());
    }

    private static ResponseEntity<String> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(message);
    }
}
