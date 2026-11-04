package com.onrelearn.material.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "onrelearn.material.extraction.enabled", havingValue = "true")
public class ExtractionSchedule {
    private final ExtractionWorker worker;

    public ExtractionSchedule(ExtractionWorker worker) { this.worker = worker; }

    // ! 저장소 연결 확인 전에는 기본 비활성이다. 명시적으로 켠 앱 인스턴스만 대기 작업을 처리한다.
    @Scheduled(fixedDelayString = "${onrelearn.material.extraction.poll-delay-ms:5000}")
    public void poll() {
        try { worker.runOnce(); }
        catch (RuntimeException failure) {
            // ! 스케줄러의 기본 예외 기록은 내부 원인·원격 URL을 노출할 수 있어 여기서 종료한다.
            System.err.println("PDF extraction scheduler failed; retry on the next poll");
        }
    }
}
