package com.onrelearn;
import com.onrelearn.quiz.service.QuizEvidencePages;
import com.onrelearn.quiz.ai.QuizFocus;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class QuizEvidencePagesTests {
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void matchesLegacyNumericConversionAndPreservesEvidence() {
        for (String value : List.of("2","2.0","\" 2 \"","\"2e0\"","\"0x2\"","\"0b10\"","\"0o2\"","[2]","[[\"2\"]]")) {
            var input=json.readTree("{\"page\":"+value+"}");
            var before=input.deepCopy();
            assertEquals(2d,QuizEvidencePages.page(input));
            assertEquals(before,input);
        }
        assertEquals(1d,QuizEvidencePages.page(json.readTree("{\"page\":true}")));
        assertEquals(2147483648d,QuizEvidencePages.page(json.readTree("{\"page\":2147483648}")));
        for (String value : List.of("null","false","0","-1","1.5","\"\"","\"NaN\"","\"Infinity\"","\"2f\"","\"0x1p1\"","[]","[1,2]","{}")) {
            assertNull(QuizEvidencePages.page(json.readTree("{\"page\":"+value+"}")));
        }
        assertNull(QuizEvidencePages.page(null));
        assertNull(QuizEvidencePages.page(json.readTree("{}")));
    }
    @Test void preservesPageOrderDuplicatesAndReturnsImmutableList() {
        var focus=new QuizFocus(QuizFocus.Kind.WRONG_REVIEW,List.of(),List.of(
            new QuizFocus.WrongItem("Q","T",null,json.readTree("{\"page\":3}")),
            new QuizFocus.WrongItem("Q","T",null,null),
            new QuizFocus.WrongItem("Q","T",null,json.readTree("{\"page\":\"3\"}")),
            new QuizFocus.WrongItem("Q","T",null,json.readTree("{\"page\":1}"))));
        assertEquals(List.of(3d,3d,1d),QuizEvidencePages.from(focus));
        assertThrows(UnsupportedOperationException.class,()->QuizEvidencePages.from(focus).clear());
    }
}
