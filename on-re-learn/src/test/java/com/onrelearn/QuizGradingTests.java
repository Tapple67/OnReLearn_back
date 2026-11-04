package com.onrelearn;

import com.onrelearn.quiz.service.QuizGrading;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class QuizGradingTests {
    private final JsonMapper json=JsonMapper.builder().build();
    private QuizGrading.Result grade(String type,Integer points,String key,String response) {
        return QuizGrading.grade(type,points,json.readTree(key),json.readTree(response));
    }
    @Test void choiceAndBooleanRequireTypedResponsesAndPreserveZeroFalse() {
        assertEquals(new QuizGrading.Result(true,7),grade("mcq",7,"{\"correctIndex\":0}","{\"selectedIndex\":0}"));
        assertFalse(grade("mcq",7,"{\"correctIndex\":0}","{\"selectedIndex\":\"0\"}").isCorrect());
        assertFalse(grade("mcq",7,"{\"correctIndex\":0}","{\"selectedIndex\":null}").isCorrect());
        assertEquals(new QuizGrading.Result(true,1),grade("tf",null,"{\"correct\":false}","{\"value\":false}"));
        assertEquals(0,grade("tf",9,"{\"correct\":false}","{\"value\":true}").earned());
        assertFalse(grade("tf",9,"{\"correct\":false}","null").isCorrect());
        assertFalse(grade("tf",9,"{\"correct\":false}","{\"value\":0}").isCorrect());
    }
    @Test void shortAnswersPreserveNormalizationAndKeywordThresholds() {
        String key="{\"accepted\":[\"Alpha Beta Gamma Delta\"]}";
        assertTrue(grade("short",10,key,"{\"text\":\"  ALPHA\\nBeta gamma DELTA  \"}").isCorrect());
        assertFalse(grade("short",10,key,"{\"text\":\"alpha\"}").isCorrect());
        assertTrue(grade("short",10,key,"{\"text\":\"alpha beta\"}").isCorrect());
        assertFalse(grade("short",10,key,"{\"text\":\"alpha alpha\"}").isCorrect());
        assertTrue(grade("short",10,"{\"accepted\":[\"alpha\"]}","{\"text\":\"alpha, extra\"}").isCorrect());
        assertFalse(grade("short",10,"{\"accepted\":[\"a b\"]}","{\"text\":\"a\"}").isCorrect());
        assertFalse(grade("short",10,key,"{}").isCorrect());
        assertTrue(grade("short",10,"{\"accepted\":[\"\\uD55C\\uAE00 \\uAC1C\\uB150\"]}","{\"text\":\"\\ufeff\\uD55C\\uAE00\\u00a0\\uAC1C\\uB150\\u3000\"}").isCorrect());
        assertTrue(grade("short",10,"{\"accepted\":[\"first token\",\"other word\"]}","{\"text\":\"first other\"}").isCorrect());
    }
    @Test void doesNotMutateInputOrHideInvalidStoredKeys() {
        var key=json.readTree("{\"accepted\":[\"Answer\"]}");
        var response=json.readTree("{\"text\":\" ANSWER \"}");
        var before=response.deepCopy();
        assertEquals(3,QuizGrading.grade("short",3,key,response).earned());
        assertEquals(before,response);
        assertEquals("Answer",key.get("accepted").get(0).stringValue());
        assertThrows(IllegalStateException.class,()->grade("tf",1,"{}","{}"));
        assertThrows(IllegalStateException.class,()->grade("short",1,"{\"accepted\":[1]}","{}"));
        assertEquals(new QuizGrading.Result(false,0),grade("unknown",1,"{}","{}"));
    }
}
