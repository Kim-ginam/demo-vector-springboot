package com.example.vectortest.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/test")
public class LogTestController {

    private static final Logger log = LoggerFactory.getLogger(LogTestController.class);

    /**
     * GET /test/log
     *
     * 호출 시 INFO 레벨의 one-line JSON 로그를 logs/app.log 에 기록합니다.
     * MDC 에 requestId / endpoint 를 넣으면 LogstashEncoder 가 JSON 필드로 자동 포함합니다.
     *
     * 예) curl "http://localhost:8080/test/log?message=hello"
     */
    @GetMapping("/log")
    public ResponseEntity<Map<String, String>> testLog(
            @RequestParam(defaultValue = "Hello from Vector Test!") String message) {

        String requestId = UUID.randomUUID().toString();

        try {
            // MDC 에 컨텍스트 추가 → LogstashEncoder 가 JSON 필드로 출력
            MDC.put("requestId", requestId);
            MDC.put("endpoint", "/test/log");

            log.info(message);

            Map<String, String> response = new LinkedHashMap<>();
            response.put("requestId", requestId);
            response.put("status", "logged");
            response.put("message", message);
            return ResponseEntity.ok(response);
        } finally {
            // 스레드 재사용 환경에서 MDC 누수 방지
            MDC.clear();
        }
    }
}
