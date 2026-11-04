package com.onrelearn;

import com.onrelearn.material.entity.Material;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.member.entity.Member;
import com.onrelearn.member.repository.MemberRepository;
import com.onrelearn.semester.entity.Grade;
import com.onrelearn.semester.repository.GradeRepository;
import com.onrelearn.subject.entity.Subject;
import com.onrelearn.subject.repository.SubjectRepository;
import com.onrelearn.note.entity.Note;
import com.onrelearn.note.repository.NoteRepository;
import com.onrelearn.summary.ai.*;
import com.onrelearn.quiz.ai.*;
import com.onrelearn.quiz.model.QuizSpec;
import com.onrelearn.quiz.entity.QuizItem;
import com.onrelearn.quiz.repository.QuizItemRepository;
import com.onrelearn.quiz.service.QuizGenerationService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("mysql")
@ContextConfiguration(initializers=OnReLearnApplicationTests.TestDatabaseGuard.class)
@SpringBootTest(properties="spring.jpa.hibernate.ddl-auto=validate")
class QuizGenerationServiceMysqlTests {
    @Autowired MemberRepository members;
    @Autowired GradeRepository grades;
    @Autowired SubjectRepository subjects;
    @Autowired MaterialRepository materials;
    @Autowired NoteRepository notes;
    @Autowired QuizGenerationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean SummaryProvider summaries;
    @MockitoBean QuizProvider provider;
    @MockitoSpyBean QuizItemRepository items;
    private final QuizSpec spec=new QuizSpec(0,2,0);
    private QuizResult result() {
        String item="{\"type\":\"tf\",\"question\":\"Question\",\"explanation\":\"Explanation\",\"answerKey\":{\"correct\":true}}";
        return new QuizResult(JsonMapper.builder().build().readTree("{\"items\":["+item+","+item+"]}"),
                spec,"synthetic","model",QuizPrompt.VERSION);
    }
    @Test void createsNewSetsAndPreservesExistingDataOnFailureOrSourceChange() {
        String email=UUID.randomUUID()+"@example.invalid";
        String[] ids=new String[3];
        var tx=new TransactionTemplate(transactions);
        try {
            tx.executeWithoutResult(status -> {
                var member=members.saveAndFlush(new Member(email,"synthetic"));
                ids[0]=member.getId();
                var grade=grades.saveAndFlush(new Grade(member,1,1,true));
                var subject=subjects.saveAndFlush(new Subject(grade,"Quiz test"));
                var material=materials.saveAndFlush(new Material(subject,1,"Lecture","users/synthetic/test.pdf"));
                ids[1]=material.getId();
                ids[2]=notes.saveAndFlush(new Note(material,1,"Original note")).getId();
            });
            when(summaries.generate(any())).thenReturn(new SummaryResult("Summary",null,null,"synthetic","model",SummaryPrompt.VERSION));
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return result();
            }).when(provider).generate(any(),any(),isNull());
            var first=service.generate(ids[0],ids[1],spec);
            var second=service.generate(ids[0],ids[1],spec);
            assertNotEquals(first.quizSetId(),second.quizSetId());
            assertEquals(first.sourceHash(),second.sourceHash());
            assertEquals(2,count(ids[1]));
            var saved=items.findByQuizSet_IdOrderByPositionAsc(first.quizSetId());
            assertEquals(2,saved.size());
            assertEquals(1,saved.get(0).getPosition());
            assertEquals(100,saved.stream().mapToInt(QuizItem::getPoints).sum());
            assertTrue(saved.get(0).getAnswerKey().contains("correct"));
            assertEquals("[]",saved.get(0).getSignalHits());
            assertStatus(401,()->service.generate(null,ids[1],spec));
            assertStatus(400,()->service.generate(ids[0]," ",spec));
            assertStatus(403,()->service.generate(UUID.randomUUID().toString(),ids[1],spec));
            verify(provider,times(2)).generate(any(),any(),isNull());
            doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Synthetic failure"))
                    .when(provider).generate(any(),any(),isNull());
            assertStatus(502,()->service.generate(ids[0],ids[1],spec));
            assertEquals(2,count(ids[1]));
            doAnswer(call -> {
                jdbc.update("UPDATE note SET content=? WHERE id=?","Changed during generation",ids[2]);
                return result();
            }).when(provider).generate(any(),any(),isNull());
            assertStatus(409,()->service.generate(ids[0],ids[1],spec));
            assertEquals(2,count(ids[1]));
            doReturn(result()).when(provider).generate(any(),any(),isNull());
            AtomicInteger inserted=new AtomicInteger();
            // ! Flush a real first item, then inject a persistence failure. Both it and the new set must roll back.
            doAnswer(call -> {
                if(inserted.incrementAndGet()==2) throw new DataIntegrityViolationException("Synthetic insert failure");
                QuizItem value=call.getArgument(0);
                entityManager.persist(value);
                entityManager.flush();
                return value;
            }).when(items).save(any(QuizItem.class));
            assertThrows(DataIntegrityViolationException.class,()->service.generate(ids[0],ids[1],spec));
            assertEquals(2,count(ids[1]));
            assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_item i JOIN quiz_set s ON i.quiz_set_id=s.id WHERE s.material_id=?",Integer.class,ids[1]));
        } finally {
            if(ids[1]!=null) jdbc.update("DELETE FROM material WHERE id=?",ids[1]);
            jdbc.update("DELETE FROM app_user WHERE email=?",email);
        }
    }

    @Test void createsReviewSetsWithMetadataAndRechecksSourceBeforeAtomicSave() {
        String email=UUID.randomUUID()+"@example.invalid";
        String[] ids=new String[5];
        var json=JsonMapper.builder().build();
        var purpose=com.onrelearn.quiz.model.QuizPurpose.WRONG_REVIEW;
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var member=members.saveAndFlush(new Member(email,"synthetic"));
                ids[0]=member.getId();
                var grade=grades.saveAndFlush(new Grade(member,1,1,true));
                var subject=subjects.saveAndFlush(new Subject(grade,"Review"));
                var material=materials.saveAndFlush(new Material(subject,1,"Lecture","users/synthetic/review.pdf"));
                ids[1]=material.getId();
                notes.saveAndFlush(new Note(material,1,"Review note"));
                var set=com.onrelearn.quiz.entity.QuizSet.builder().material(material).sourceHash("source")
                        .purpose(com.onrelearn.quiz.model.QuizPurpose.GENERAL).build();
                entityManager.persist(set);
                var item=QuizItem.builder().quizSet(set).position(1).type("tf").question("Original question")
                        .answerKey("{\"correct\":true}").points(100).topic("Topic").evidence("{\"page\":1}").build();
                entityManager.persist(item); ids[3]=item.getId();
                var attempt=new com.onrelearn.quiz.entity.QuizAttempt(member.getId(),set);
                entityManager.persist(attempt); ids[2]=attempt.getId();
                var answer=new com.onrelearn.quiz.entity.QuizAnswer(attempt,item,"{\"value\":false}");
                answer.recordGrade(false,0); entityManager.persist(answer); ids[4]=answer.getId();
                attempt.completeGrading(0,100,java.time.Instant.now());
                entityManager.flush();
            });
            when(summaries.generate(any())).thenReturn(new SummaryResult("Summary",null,null,"synthetic","model",SummaryPrompt.VERSION));
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                QuizFocus focus=call.getArgument(2);
                assertEquals(QuizFocus.Kind.WRONG_REVIEW,focus.kind());
                assertEquals("Original question",focus.wrongItems().get(0).question());
                return result();
            }).when(provider).generate(any(),any(),any());
            var created=service.generate(ids[0],ids[1],spec,purpose,ids[2],null);
            assertEquals("WRONG_REVIEW",jdbc.queryForObject("SELECT purpose FROM quiz_set WHERE id=?",String.class,created.quizSetId()));
            assertEquals(ids[2],jdbc.queryForObject("SELECT source_attempt_id FROM quiz_set WHERE id=?",String.class,created.quizSetId()));
            assertEquals(ids[3],json.readTree(jdbc.queryForObject("SELECT source_item_ids FROM quiz_set WHERE id=?",String.class,created.quizSetId())).get(0).stringValue());
            assertEquals("Topic",json.readTree(jdbc.queryForObject("SELECT source_topics FROM quiz_set WHERE id=?",String.class,created.quizSetId())).get(0).stringValue());
            // * Both legacy completed states can supply a review.
            jdbc.update("UPDATE quiz_attempt SET status='SUBMITTED' WHERE id=?",ids[2]);
            service.generate(ids[0],ids[1],spec,purpose,ids[2],null);
            assertStatus(404,()->service.generate("other",ids[1],spec,purpose,ids[2],null));
            assertStatus(404,()->service.generate(ids[0],"other-material",spec,purpose,ids[2],null));
            assertStatus(400,()->service.generate(ids[0],ids[1],spec,com.onrelearn.quiz.model.QuizPurpose.WEAK_TOPIC,null,json.createArrayNode()));
            doAnswer(call -> {
                assertEquals(QuizFocus.Kind.WEAK_TOPIC,((QuizFocus)call.getArgument(2)).kind());
                return result();
            }).when(provider).generate(any(),any(),any());
            var weak=service.generate(ids[0],ids[1],spec,com.onrelearn.quiz.model.QuizPurpose.WEAK_TOPIC,null,json.createArrayNode().add(" Topic "));
            assertEquals("WEAK_TOPIC",jdbc.queryForObject("SELECT purpose FROM quiz_set WHERE id=?",String.class,weak.quizSetId()));
            assertNull(jdbc.queryForObject("SELECT source_attempt_id FROM quiz_set WHERE id=?",String.class,weak.quizSetId()));
            int before=count(ids[1]);
            doAnswer(call -> {
                jdbc.update("UPDATE quiz_item SET question='Changed' WHERE id=?",ids[3]);
                return result();
            }).when(provider).generate(any(),any(),any());
            assertStatus(409,()->service.generate(ids[0],ids[1],spec,purpose,ids[2],null));
            assertEquals(before,count(ids[1]));
            doReturn(result()).when(provider).generate(any(),any(),any());
            AtomicInteger inserted=new AtomicInteger();
            doAnswer(call -> {
                if(inserted.incrementAndGet()==2) throw new DataIntegrityViolationException("Synthetic insert failure");
                QuizItem value=call.getArgument(0);entityManager.persist(value);entityManager.flush();return value;
            }).when(items).save(any(QuizItem.class));
            assertThrows(DataIntegrityViolationException.class,()->service.generate(ids[0],ids[1],spec,purpose,ids[2],null));
            assertEquals(before,count(ids[1]));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM quiz_answer WHERE id=?",Integer.class,ids[4]));
            assertEquals("SUBMITTED",jdbc.queryForObject("SELECT status FROM quiz_attempt WHERE id=?",String.class,ids[2]));
        } finally {
            reset(items);
            // ! Remove only this test's synthetic material/account and their dependent rows.
            if(ids[1]!=null) jdbc.update("DELETE FROM material WHERE id=?",ids[1]);
            jdbc.update("DELETE FROM app_user WHERE email=?",email);
            assertFalse(members.existsByEmail(email));
        }
    }
    private int count(String material) { return jdbc.queryForObject("SELECT COUNT(*) FROM quiz_set WHERE material_id=?",Integer.class,material); }
    private void assertStatus(int status,org.junit.jupiter.api.function.Executable action) {
        assertEquals(status,assertThrows(ResponseStatusException.class,action).getStatusCode().value());
    }
}
