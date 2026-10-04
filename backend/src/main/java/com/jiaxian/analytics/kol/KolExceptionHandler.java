package com.jiaxian.analytics.kol;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 只作用于 KOL 接口：参数问题 400，文案或员工不存在 404，文案键冲突 409，响应体是一句说明原因的文字。 */
@RestControllerAdvice(assignableTypes = {BloggerController.class, KolAdminController.class,
    PlaybookController.class, BloggerProfileController.class})
public class KolExceptionHandler {

    @ExceptionHandler(InvalidKolQueryException.class)
    public ResponseEntity<String> invalidQuery(InvalidKolQueryException e) {
        return text(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> typeMismatch(MethodArgumentTypeMismatchException e) {
        return text(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> missingParameter(MissingServletRequestParameterException e) {
        return text(HttpStatus.BAD_REQUEST, "Missing required parameter '" + e.getParameterName() + "'");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> unreadableBody(HttpMessageNotReadableException e) {
        return text(HttpStatus.BAD_REQUEST, "Request body is not valid JSON or contains an invalid value");
    }

    @ExceptionHandler(PlaybookNotFoundException.class)
    public ResponseEntity<String> notFound(PlaybookNotFoundException e) {
        return text(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(EmployeeNotFoundException.class)
    public ResponseEntity<String> employeeNotFound(EmployeeNotFoundException e) {
        return text(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PlaybookConflictException.class)
    public ResponseEntity<String> conflict(PlaybookConflictException e) {
        return text(HttpStatus.CONFLICT, e.getMessage());
    }

    private static ResponseEntity<String> text(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(message);
    }
}
