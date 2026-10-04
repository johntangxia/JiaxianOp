package com.jiaxian.analytics.pv;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 只作用于 PV 接口：参数问题统一返回 400 + 一句说明原因的文字。 */
@RestControllerAdvice(assignableTypes = {PvAnalyticsController.class, PvAdminController.class})
public class PvExceptionHandler {

    @ExceptionHandler(InvalidPvQueryException.class)
    public ResponseEntity<String> invalidQuery(InvalidPvQueryException e) {
        return badRequest(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> typeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest("Invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<String> missingParameter(MissingServletRequestParameterException e) {
        return badRequest("Missing required parameter '" + e.getParameterName() + "'");
    }

    private static ResponseEntity<String> badRequest(String message) {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(message);
    }
}
