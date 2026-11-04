package com.onrelearn.quiz.service;

import com.onrelearn.quiz.ai.*;
import com.onrelearn.quiz.entity.*;
import com.onrelearn.quiz.model.*;
import com.onrelearn.quiz.repository.*;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.subject.repository.SubjectRepository;
import com.onrelearn.summary.ai.SummaryPrompt;
import com.onrelearn.summary.service.SummaryInputService;
import java.time.Instant;
import java.util.ArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class QuizGenerationService {
    private final QuizInputService inputs;
    private final QuizWrongReviewService reviews;
    private final SummaryInputService sources;
    private final QuizProvider provider;
    private final SubjectRepository subjects;
    private final MaterialRepository materials;
    private final QuizSetRepository sets;
    private final QuizItemRepository items;
    private final TransactionTemplate write;
    private final JsonMapper json = JsonMapper.builder().build();

    public QuizGenerationService(QuizInputService inputs, SummaryInputService sources, QuizProvider provider,
            SubjectRepository subjects, MaterialRepository materials, QuizSetRepository sets,
            QuizItemRepository items, PlatformTransactionManager transactions, QuizWrongReviewService reviews) {
        this.inputs=inputs; this.reviews=reviews; this.sources=sources; this.provider=provider; this.subjects=subjects;
        this.materials=materials; this.sets=sets; this.items=items;
        write=new TransactionTemplate(transactions);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // ! AI calls stay outside transactions; source authorization is checked again before saving.
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Created generate(String memberId, String materialId, QuizSpec requestedSpec) {
        return generate(memberId, materialId, requestedSpec, QuizPurpose.GENERAL, null, null);
    }

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Created generate(String memberId, String materialId, QuizSpec requestedSpec,
                            QuizPurpose requestedPurpose, String sourceAttemptId, JsonNode sourceTopics) {
        return generate(memberId,materialId,requestedSpec,requestedPurpose,sourceAttemptId,sourceTopics,null);
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public Created generate(String memberId, String materialId, QuizSpec requestedSpec,
            QuizPurpose requestedPurpose, String sourceAttemptId, JsonNode sourceTopics, java.util.function.Consumer<String> stage) {
        QuizPurpose purpose = requestedPurpose == null ? QuizPurpose.GENERAL : requestedPurpose;
        QuizSpec spec = requestedSpec == null
                ? (purpose == QuizPurpose.GENERAL ? QuizSpec.DEFAULT : QuizSpec.REVIEW) : requestedSpec;
        var prepared=inputs.prepare(memberId,materialId,purpose,sourceAttemptId,sourceTopics);
        var result=stage==null ? provider.generate(prepared.context(),spec,prepared.focus()) : provider.generate(prepared.context(),spec,prepared.focus(),stage);
        if (result == null || !spec.equals(result.spec()) || !QuizPrompt.VERSION.equals(result.promptVersion()))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Invalid quiz response");
        var output=result.output();
        QuizOutputValidator.validate(output,spec);
        var weights=new ArrayList<Double>();
        for (var item:output.get("items")) weights.add(item.has("points") ? item.get("points").doubleValue() : 1d);
        var points=QuizPoints.normalize(weights);
        if (stage!=null) stage.accept("saving");
        return write.execute(status -> {
            // ! Serialize with note-save/material-delete, then recheck source and ownership before any insert.
            subjects.findOwnedForUpdate(prepared.subjectId(),memberId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,"Material unavailable"));
            var current=sources.build(memberId,prepared.materialId(),SummaryPrompt.VERSION);
            if (!prepared.fingerprint().equals(current.fingerprint()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Quiz source changed; retry generation");
            if (prepared.purpose() == QuizPurpose.WRONG_REVIEW) {
                var review = reviews.load(memberId, prepared.materialId(), prepared.sourceAttemptId());
                if (!review.focus().equals(prepared.focus()) || !review.sourceItemIds().equals(prepared.sourceItemIds()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Review source changed; retry generation");
            }
            var material=materials.findByIdAndSubject_Grade_User_Id(prepared.materialId(),memberId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,"Material unavailable"));
            var set=sets.saveAndFlush(QuizSet.builder().material(material).summaryId(prepared.summaryId())
                    .sourceHash(prepared.fingerprint().sourceHash()).title(text(output.get("title")))
                    .provider(result.provider()).model(result.model()).promptVersion(result.promptVersion())
                    .purpose(prepared.purpose()).sourceAttemptId(prepared.sourceAttemptId())
                    .sourceTopics(json.writeValueAsString(prepared.sourceTopics()))
                    .sourceItemIds(json.writeValueAsString(prepared.sourceItemIds())).build());
            int index=0;
            for (var item:output.get("items")) {
                String topic=text(item.get("topic"));
                topic=topic == null || topic.isBlank() ? "\uAE30\uD0C0 \uAC1C\uB150" : topic.trim();
                items.save(QuizItem.builder().quizSet(set).position(index+1)
                        .type(item.get("type").stringValue()).question(item.get("question").stringValue())
                        .choices(encoded(item.get("choices"))).answerKey(encoded(item.get("answerKey")))
                        .explanation(item.get("explanation").stringValue()).topic(topic).points(points.get(index))
                        .evidence(item.has("evidence") ? encoded(item.get("evidence")) : "{\"source\":\"pdf\"}")
                        .signalHits(item.has("signalHits") ? encoded(item.get("signalHits")) : "[]").build());
                index++;
            }
            // * A failed item insert rolls back the new set as well; existing sets are never replaced.
            items.flush();
            return new Created(set.getId(),set.getCreatedAt(),set.getSummaryId(),set.getSourceHash());
        });
    }
    private String encoded(JsonNode value) { return value == null || value.isNull() ? null : json.writeValueAsString(value); }
    private static String text(JsonNode value) { return value == null || value.isNull() ? null : value.stringValue(); }
    public record Created(String quizSetId, Instant createdAt, String summaryId, String sourceHash) {}
}
