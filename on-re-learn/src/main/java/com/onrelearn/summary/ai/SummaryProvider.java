package com.onrelearn.summary.ai;

import com.onrelearn.summary.service.SummaryInput;

// * Implementations own model/endpoint/timeout settings; business services depend on this boundary.
public interface SummaryProvider {
    SummaryResult generate(SummaryInput input);
    default SummaryResult generate(SummaryInput input, java.util.function.Consumer<String> delta) {
        var result = generate(input); delta.accept(result.content()); return result;
    }
}
