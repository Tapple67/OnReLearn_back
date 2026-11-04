package com.onrelearn;

import com.onrelearn.quiz.service.QuizInputService;
import com.onrelearn.summary.ai.SummaryPrompt;
import com.onrelearn.summary.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuizInputServiceTests {
    private final MaterialSummaryService summaries = mock(MaterialSummaryService.class);
    private final SummaryInputService inputs = mock(SummaryInputService.class);
    private final com.onrelearn.quiz.service.QuizWrongReviewService reviews = mock(com.onrelearn.quiz.service.QuizWrongReviewService.class);
    private final QuizInputService service = new QuizInputService(summaries, inputs, reviews);
    private SummaryInput packet(String note) {
        return SummaryInput.assemble("material","subject",1,"title",SummaryPrompt.VERSION,
                List.of(new SummaryInput.Text(1,"PDF")),List.of(new SummaryInput.Note(1,note,null,null)));
    }
    private void summary(SummaryInput packet, boolean reused) {
        when(summaries.ensure("owner","material")).thenReturn(new MaterialSummaryService.View(
                "summary",packet.fingerprint().sourceHash(),"canonical","adaptive",reused));
    }
    @Test void preparesFreshOrReusedCanonicalSummaryWithSourceIdentity() {
        var packet = packet("private note");
        for (boolean reused : List.of(false,true)) {
            summary(packet,reused);
            when(inputs.build("owner","material",SummaryPrompt.VERSION)).thenReturn(packet);
            var prepared = service.prepare("owner","material");
            assertEquals("canonical",prepared.context().summary());
            assertEquals("private note",prepared.context().notes().get(0).note());
            assertEquals("PDF",prepared.context().sourcePages().get(0).pdfText());
            assertEquals("summary",prepared.summaryId());
            assertEquals("subject",prepared.subjectId());
            assertEquals(packet.fingerprint(),prepared.fingerprint());
            assertFalse(prepared.toString().contains("private"));
        }
    }
    @Test void rejectsChangedNotesInsteadOfMixingNewInputWithOldSummary() {
        summary(packet("old note"),true);
        when(inputs.build("owner","material",SummaryPrompt.VERSION)).thenReturn(packet("new note"));
        var error = assertThrows(ResponseStatusException.class, () -> service.prepare("owner","material"));
        assertEquals(409,error.getStatusCode().value());
        assertFalse(error.toString().contains("new note"));
    }
    @Test void propagatesOwnershipAndSummaryFailuresWithoutBuildingContext() {
        for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST,HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN,HttpStatus.BAD_GATEWAY,HttpStatus.SERVICE_UNAVAILABLE)) {
            reset(summaries,inputs);
            var failure = new ResponseStatusException(status,"Unavailable");
            when(summaries.ensure("owner","material")).thenThrow(failure);
            assertSame(failure,assertThrows(ResponseStatusException.class, () -> service.prepare("owner","material")));
            verifyNoInteractions(inputs);
        }
        reset(summaries,inputs);
        summary(packet("note"),true);
        when(inputs.build("owner","material",SummaryPrompt.VERSION)).thenThrow(
                new ResponseStatusException(HttpStatus.FORBIDDEN,"Material unavailable"));
        assertEquals(403,assertThrows(ResponseStatusException.class,
                () -> service.prepare("owner","material")).getStatusCode().value());
    }
    @Test void preparesWrongReviewContextAndIdentityBeforeSummary() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var focus = new com.onrelearn.quiz.ai.QuizFocus(com.onrelearn.quiz.ai.QuizFocus.Kind.WRONG_REVIEW,
                List.of("Topic"), List.of(new com.onrelearn.quiz.ai.QuizFocus.WrongItem("Q","Topic",null,
                json.readTree("{\"page\":5}"))));
        when(reviews.load("owner","material"," attempt ")).thenReturn(
                new com.onrelearn.quiz.service.QuizWrongReviewService.Source(focus,List.of("item")));
        var packet = SummaryInput.assemble("material","subject",1,"title",SummaryPrompt.VERSION,
                List.of(new SummaryInput.Text(1,"one"),new SummaryInput.Text(5,"five")),List.of());
        summary(packet,true);
        when(inputs.build("owner","material",SummaryPrompt.VERSION)).thenReturn(packet);
        var prepared = service.prepare("owner","material",com.onrelearn.quiz.model.QuizPurpose.WRONG_REVIEW," attempt ",null);
        assertEquals(focus,prepared.focus()); assertEquals("attempt",prepared.sourceAttemptId());
        assertEquals(List.of("item"),prepared.sourceItemIds()); assertEquals(List.of("Topic"),prepared.sourceTopics());
        assertEquals(List.of(5),prepared.context().sourcePages().stream().map(p->p.page()).toList());
        var order=inOrder(reviews,summaries,inputs);
        order.verify(reviews).load("owner","material"," attempt ");
        order.verify(summaries).ensure("owner","material");
        order.verify(inputs).build("owner","material",SummaryPrompt.VERSION);
    }
    @Test void validatesWeakTopicsAndPropagatesReviewFailureBeforeSummary() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.prepare("owner","material",
                com.onrelearn.quiz.model.QuizPurpose.WEAK_TOPIC,null,json.readTree("[]"))).getStatusCode().value());
        var failure=new ResponseStatusException(HttpStatus.NOT_FOUND,"Review unavailable");
        when(reviews.load("owner","material","bad")).thenThrow(failure);
        assertSame(failure,assertThrows(ResponseStatusException.class,()->service.prepare("owner","material",
                com.onrelearn.quiz.model.QuizPurpose.WRONG_REVIEW,"bad",null)));
        verifyNoInteractions(summaries,inputs);
        var packet=packet(""); summary(packet,true);
        when(inputs.build("owner","material",SummaryPrompt.VERSION)).thenReturn(packet);
        var prepared=service.prepare("owner","material",com.onrelearn.quiz.model.QuizPurpose.WEAK_TOPIC,null,
                json.createArrayNode().add(" Topic ").add("Topic"));
        assertEquals(List.of("Topic"),prepared.sourceTopics());
        assertEquals(com.onrelearn.quiz.ai.QuizFocus.Kind.WEAK_TOPIC,prepared.focus().kind());
        assertNull(prepared.sourceAttemptId()); assertTrue(prepared.sourceItemIds().isEmpty());
    }
}
