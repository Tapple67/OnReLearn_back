package com.onrelearn;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.*;
import static org.junit.jupiter.api.Assertions.*;

class ControllerParameterNamesTests {
    @Test void requestBindingsDoNotRequireCompilerParameterMetadata() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (var bean : scanner.findCandidateComponents("com.onrelearn")) {
            for (var method : Class.forName(bean.getBeanClassName()).getDeclaredMethods()) {
                for (var parameter : method.getParameters()) {
                    var query = parameter.getAnnotation(RequestParam.class);
                    var path = parameter.getAnnotation(PathVariable.class);
                    var part = parameter.getAnnotation(RequestPart.class);
                    var cookie = parameter.getAnnotation(CookieValue.class);
                    if (query != null) assertTrue(!query.name().isBlank() || !query.value().isBlank(), method.toString());
                    if (path != null) assertTrue(!path.name().isBlank() || !path.value().isBlank(), method.toString());
                    if (part != null) assertTrue(!part.name().isBlank() || !part.value().isBlank(), method.toString());
                    if (cookie != null) assertTrue(!cookie.name().isBlank() || !cookie.value().isBlank(), method.toString());
                }
            }
        }
    }
}
