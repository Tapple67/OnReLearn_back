package com.onrelearn.summary.ai;

import com.onrelearn.ai.SourceEvidence;
import com.onrelearn.summary.service.SummaryInput;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

// * Publish only concise excerpts reconstructed from complete source sentences.
public final class GroundedSummary {
    private static final int MAX_ITEMS_PER_BATCH=8;
    private static final int MAX_EXCERPT_LENGTH=360;
    private static final int MAX_EXPLANATION_LENGTH=600;
    private final SummaryInput input;
    private final Consumer<String> publish;
    private final int sectionNumber;
    private final SummaryOutline.Section plannedSection;
    private int subsectionNumber;
    private SummaryNoteLinks noteLinks;
    private String previousTopic="";
    private final StringBuilder pending=new StringBuilder(), accepted=new StringBuilder();
    private final Set<String> seen=new HashSet<>();
    private final JsonMapper json=JsonMapper.builder().build();
    public GroundedSummary(SummaryInput input, Consumer<String> publish) {
        this(input,publish,1);
    }

    public GroundedSummary(SummaryInput input, Consumer<String> publish, int sectionNumber) {
        this(input,publish,sectionNumber,null,0);
    }

    public GroundedSummary(SummaryInput input, Consumer<String> publish, SummaryOutline.Section section, int subsectionCount) {
        this(input,publish,section.number(),section,subsectionCount);
    }

    private GroundedSummary(SummaryInput input, Consumer<String> publish, int sectionNumber,
            SummaryOutline.Section plannedSection, int subsectionCount) {
        this.input=input;
        this.publish=publish;
        this.sectionNumber=sectionNumber;
        this.plannedSection=plannedSection;
        this.subsectionNumber=subsectionCount;
    }

    public void linkNotes(SummaryNoteLinks links) { this.noteLinks=links; }

    public int subsectionCount() { return subsectionNumber; }
    public void append(String delta) {
        pending.append(delta);
        if (pending.length()>200000) throw invalid();
        int end;
        while ((end=pending.indexOf("\n"))>=0) {
            String line=pending.substring(0,end);pending.delete(0,end+1);verify(line);
        }
    }
    private void verify(String line) {
        try {
            var value=json.readTree(line);
            if (value==null || !value.isObject() || !value.path("page").isIntegralNumber() || !value.path("page").canConvertToInt() || !value.path("quote").isString()) return;
            int page=value.path("page").asInt();
            var source=input.pages().stream().filter(p -> p.page()==page).findFirst().orElse(null);
            if (source==null || source.pdfText().isBlank()) return;
            String quote=SourceEvidence.normalize(value.path("quote").asString());
            String paragraph=SourceEvidence.paragraph(source.pdfText(),quote);
            if (paragraph==null) return;
            // * Keep the complete source sentence, including qualifiers outside the chosen excerpt.
            var sentences=java.text.BreakIterator.getSentenceInstance(Locale.KOREAN);
            sentences.setText(paragraph);
            int offset=paragraph.indexOf(quote);
            if (offset!=paragraph.lastIndexOf(quote)) return;
            int start=sentences.preceding(offset+1);
            int end=sentences.following(offset+quote.length()-1);
            if (start<0 || end<0 || end-start>MAX_EXCERPT_LENGTH) return;
            paragraph=paragraph.substring(start,end).strip();
            String category=switch (value.path("kind").asString("")) {
                case "concept" -> "핵심 개념 · ";
                case "mechanism" -> "작동 방식 · ";
                case "caution" -> "비교와 주의점 · ";
                default -> "";
            };
            String note=SourceEvidence.normalize(value.path("noteQuote").asString(""));
            boolean verifiedNote=noteLinks==null && !note.isEmpty() && note.length()<=160
                    && source.note().lines().map(SourceEvidence::normalize).anyMatch(note::equals);
            String proposedSection=SourceEvidence.normalize(value.path("section").asString(""));
            String section=proposedSection.length()>=2 && proposedSection.length()<=70 && input.pages().stream()
                    .anyMatch(candidate -> SourceEvidence.normalize(candidate.pdfText()).contains(proposedSection)) ? proposedSection : "";
            String topic=SourceEvidence.normalize(value.path("topic").asString(""));
            if (topic.length()<2 || topic.length()>60 || !SourceEvidence.normalize(source.pdfText()).contains(topic)) topic="";
            publish(page,paragraph,category,verifiedNote ? note : "",section,topic,SourceEvidence.normalize(value.path("easy").asString("")));
        } catch (tools.jackson.core.JacksonException ignored) { /* ! Malformed/unsupported paragraphs are excluded, never displayed. */ }
    }
    private void publish(int page, String paragraph) {
        publish(page, paragraph, "", "", "", "", "");
    }

    // * The excerpt stays source-verified. The explanation below it is labeled as AI-written, not as source text.
    private String explanation(int page, String concept, String easy) {
        if (easy.isEmpty() || easy.length()>MAX_EXPLANATION_LENGTH) return "";
        var memos=noteLinks.takeDifficulties(page,concept);
        return memos.isEmpty() ? "" : String.join("",memos)+"> 쉽게 풀어보면 · "+easy+"\n\n";
    }

    private void publish(int page, String paragraph, String label, String note, String section, String topic, String easy) {
        if (seen.size()>=MAX_ITEMS_PER_BATCH || !seen.add(page+":"+paragraph)) return;
        String heading="";
        if (accepted.isEmpty() && subsectionNumber==0) {
            var sectionPages=plannedSection==null ? input.pages() : plannedSection.pages();
            int first=sectionPages.get(0).page();
            int last=sectionPages.get(sectionPages.size()-1).page();
            String range=first==last ? String.valueOf(first) : first+"–"+last;
            String title=plannedSection!=null ? plannedSection.title() : section.isEmpty() ? "핵심 정리" : section;
            heading="## "+sectionNumber+". "+title+" · "+range+"쪽\n\n";
        }
        String subheading=topic.isEmpty() ? label.replace(" · ","") : topic;
        if (subheading.isEmpty()) subheading="원문 핵심";
        if (!subheading.equals(previousTopic)) {
            previousTopic=subheading;
            heading+="### "+sectionNumber+"."+(++subsectionNumber)+" "+subheading+"\n\n";
        }
        String verified=heading+"- "+(topic.isEmpty() ? label : "")+paragraph+" (p."+page+")\n\n";
        if (noteLinks!=null) {
            verified+=explanation(page,topic+" "+paragraph,easy);
            verified+=noteLinks.takeFor(page,topic+" "+paragraph);
        }
        else if (!note.isEmpty()) verified+="> 학습 메모 · "+note+" (p."+page+")\n\n";
        accepted.append(verified);
        publish.accept(verified);
    }

    public boolean hasVerifiedExcerpts() {
        return !accepted.isEmpty();
    }

    public void publishSourceFallback() {
        // * Select only complete original sentences. The fallback never constructs a new claim.
        for (var page : input.pages()) {
            for (String block : page.pdfText().split("\\R\\s*\\R")) {
                String source=SourceEvidence.normalize(block);
                var sentences=java.text.BreakIterator.getSentenceInstance(Locale.KOREAN);
                sentences.setText(source);
                for (int start=sentences.first(), end=sentences.next(); end!=java.text.BreakIterator.DONE; start=end, end=sentences.next()) {
                    String excerpt=source.substring(start,end).strip();
                    if (excerpt.length()>=24 && excerpt.length()<=240 && excerpt.matches("(?s).*[.!?。！？]$")) {
                        publish(page.page(),excerpt);
                        break;
                    }
                }
                if (hasVerifiedExcerpts()) return;
            }
        }
        // Slide text often has list lines without sentence-ending punctuation.
        for (var page : input.pages()) {
            for (String line : page.pdfText().lines().toList()) {
                String excerpt=SourceEvidence.normalize(line);
                if (excerpt.length()>=16 && excerpt.length()<=240
                        && excerpt.codePoints().filter(Character::isLetter).count()>=8) {
                    publish(page.page(),excerpt,"원문 발췌 · ","","","","");
                    return;
                }
            }
        }
    }

    public void discardIncompleteLine() { pending.setLength(0); }
    public void finishLine() {
        if (!pending.isEmpty()) { verify(pending.toString());pending.setLength(0); }
    }
    public String finish() {
        finishLine();
        if (accepted.isEmpty()) throw invalid();
        return accepted.toString();
    }
    public static SummaryResult verify(SummaryInput input, SummaryResult result) {
        var verification=new GroundedSummary(input,text -> {});verification.append(result.content());
        return new SummaryResult(verification.finish(),null,null,result.provider(),result.model(),input.promptVersion());
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"원문 근거를 확인할 수 없어 요약을 제공하지 않았습니다."); }
}
