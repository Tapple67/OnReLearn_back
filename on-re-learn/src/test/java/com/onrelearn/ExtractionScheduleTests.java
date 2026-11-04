package com.onrelearn;

import com.onrelearn.material.service.ExtractionSchedule;
import com.onrelearn.material.service.ExtractionWorker;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExtractionScheduleTests {
    @Test void enabledSchedulePollsItsWorkerWithoutASeparateProcess() {
        var worker = mock(ExtractionWorker.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic", Map.of(
                "onrelearn.material.extraction.enabled", "true",
                "onrelearn.material.extraction.poll-delay-ms", "50")));
            context.registerBean(ExtractionWorker.class, () -> worker);
            context.register(ExtractionSchedule.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(ExtractionSchedule.class).size());
            verify(worker, timeout(2000).atLeastOnce()).runOnce();
        }
    }

    @Test void disabledScheduleNeverStartsTheWorker() throws Exception {
        var worker = mock(ExtractionWorker.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ExtractionWorker.class, () -> worker);
            context.register(ExtractionSchedule.class);
            context.refresh();
            assertEquals(0, context.getBeansOfType(ExtractionSchedule.class).size());
            Thread.sleep(150);
            verifyNoInteractions(worker);
        }
    }
}
