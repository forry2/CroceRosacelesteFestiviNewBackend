package com.example.crocerosacelestefestivinewbackend.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<List<Map<String, Object>>> handleValidation(ValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getViolations());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<List<Map<String, Object>>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<Map<String, Object>> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> {
            Map<String, Object> m = new HashMap<>();
            m.put("row", 0);
            m.put("field", fe.getField());
            m.put("message", fe.getDefaultMessage());
            errors.add(m);
        });
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<List<Map<String, Object>>> handleGeneric(Exception ex) {
        List<Map<String, Object>> errors = new ArrayList<>();
        Map<String, Object> m = new HashMap<>();
        m.put("row", 0);
        m.put("field", "__global__");
        m.put("message", ex.getMessage());
        errors.add(m);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }
}


