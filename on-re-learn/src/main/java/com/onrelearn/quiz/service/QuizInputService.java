package com.onrelearn.quiz.service;

import com.onrelearn.quiz.ai.QuizContext;
import com.onrelearn.quiz.ai.QuizFocus;
import com.onrelearn.quiz.model.QuizPurpose;
import tools.jackson.databind.JsonNode;
import com.onrelearn.summary.ai.SummaryPrompt;
import com.onrelearn.summary.ai.SummaryNoteLinks;
import com.onrelearn.summary.service.MaterialSummaryService;
import com.onrelearn.summary.service.SummaryInput;
import com.onrelearn.summary.service.SummaryInputService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class QuizInputService {
    private final MaterialSummaryService summaries;
    private final SummaryInputService inputs;
    private final QuizWrongReviewService reviews;
    public QuizInputService(MaterialSummaryService summaries, SummaryInputService inputs, QuizWrongReviewService reviews) {
        this.summaries = summaries; this.inputs = inputs; this.reviews = reviews;
    }

    // ! Summary generation may call AI. Never hold a caller's transaction across it.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Prepared prepare(String memberId, String materialId) {
        return prepare(memberId, materialId, QuizPurpose.GENERAL, null, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Prepared prepare(String memberId, String materialId, QuizPurpose requestedPurpose,
                            String sourceAttemptId, JsonNode requestedTopics) {
        QuizPurpose purpose = requestedPurpose == null ? QuizPurpose.GENERAL : requestedPurpose;
        QuizFocus focus = null;
        List<String> sourceTopics = QuizFocusInput.topics(requestedTopics);
        List<String> sourceItemIds = List.of();
        List<Double> preferredPages = List.of();
        String reviewAttemptId = null;
        // ! Resolve review authorization and required topics before any potentially paid summary call.
        if (purpose == QuizPurpose.WRONG_REVIEW) {
            var review = reviews.load(memberId, materialId, sourceAttemptId);
            focus = review.focus();
            sourceTopics = focus.topics();
            sourceItemIds = review.sourceItemIds();
            preferredPages = review.preferredPages();
            reviewAttemptId = sourceAttemptId.trim();
        } else if (purpose == QuizPurpose.WEAK_TOPIC) {
            focus = QuizFocusInput.weakTopic(requestedTopics);
            sourceTopics = focus.topics();
        }
        var summary = summaries.ensure(memberId, materialId);
        // * build checks ownership again and returns one PDF/note read snapshot.
        var packet = inputs.build(memberId, materialId, SummaryPrompt.VERSION);
        var fingerprint = packet.fingerprint();
        if (!fingerprint.sourceHash().equals(summary.sourceHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Quiz source changed; retry generation");
        }
        return new Prepared(packet.materialId(), packet.subjectId(), summary.summaryId(), fingerprint,
                groundedContext(summary.content(), packet, preferredPages),
                purpose, focus, sourceTopics, sourceItemIds, reviewAttemptId);
    }

    private QuizContext groundedContext(String summary, SummaryInput packet, List<Double> preferredPages) {
        var selected=QuizContext.assemble(summary,packet,preferredPages);
        var memoFocus = SummaryNoteLinks.quizFocus(packet.pages());
        var selectedPages = new java.util.HashSet<Integer>();
        selected.sourcePages().forEach(page -> selectedPages.add(page.page()));
        memoFocus.forEach(focus -> selectedPages.add(focus.page()));
        // * Give general quizzes the original text for pages cited in the study summary.
        if (preferredPages.isEmpty()) {
            var citations = java.util.regex.Pattern.compile("\\(p\\.(\\d{1,9})\\)").matcher(summary);
            var citedPages = new java.util.LinkedHashSet<Integer>();
            while (citations.find()) citedPages.add(Integer.parseInt(citations.group(1)));
            packet.pages().stream().filter(page -> citedPages.contains(page.page())
                    && !page.pdfText().isBlank() && !selectedPages.contains(page.page()))
                    .limit(12).toList().forEach(page -> selectedPages.add(page.page()));
        }
        // * Keep independent PDF material available for the non-memo questions.
        if (!memoFocus.isEmpty()) {
            packet.pages().stream().filter(page -> !page.pdfText().isBlank() && !selectedPages.contains(page.page()))
                    .limit(3).toList().forEach(page -> selectedPages.add(page.page()));
        }
        var originals = packet.pages().stream().filter(page -> selectedPages.contains(page.page()))
                .map(page -> new QuizContext.SourcePage(page.page(), page.pdfText(), "", null)).toList();
        return new QuizContext(summary, selected.notes(), originals, memoFocus);
    }

    // * Carry validated review identity with the same source snapshot used to build AI context.
    public record Prepared(String materialId, String subjectId, String summaryId,
                           SummaryInput.Fingerprint fingerprint, QuizContext context, QuizPurpose purpose,
                           QuizFocus focus, List<String> sourceTopics, List<String> sourceItemIds, String sourceAttemptId) {
        public Prepared {
            sourceTopics = List.copyOf(sourceTopics);
            sourceItemIds = List.copyOf(sourceItemIds);
        }
        @Override public String toString() { return "PreparedQuizInput[content omitted]"; }
    }
}
