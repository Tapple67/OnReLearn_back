package com.onrelearn.summary.ai;

import com.onrelearn.ai.SourceEvidence;
import com.onrelearn.summary.service.SummaryInput;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// * Publish rewritten sentences that pass the source checks; a line without one falls back to a verified excerpt.
public final class GroundedSummary {
    private static final int MAX_ITEMS_PER_BATCH=8;
    private static final int MAX_CLAIMS_PER_BATCH=60;
    private static final int MAX_SUB_POINTS=6;
    private static final int MIN_SENTENCE_LENGTH=8;
    private static final int MAX_SENTENCE_LENGTH=300;
    private static final int MAX_EXCERPT_LENGTH=360;
    private static final int MAX_EXPLANATION_LENGTH=600;
    private static final Pattern PAGE_MARK=Pattern.compile("\\s*\\(?\\bp\\.?\\s*\\d+\\)?");
    private static final Pattern LEADING_MARK=Pattern.compile("^(?:#{1,6}|>|[-•]|\\*(?!\\*)|\\d+[.)]|[a-zA-Z][.)])\\s+");
    private final SummaryInput input;
    private final Consumer<String> publish;
    private final int sectionNumber;
    private final SummaryOutline.Section plannedSection;
    private int subsectionNumber;
    private int claimNumber;
    private SummaryNoteLinks noteLinks;
    private String previousTopic="";
    private final StringBuilder pending=new StringBuilder(), accepted=new StringBuilder();
    private final Set<String> seen=new HashSet<>();
    private final Map<String,Integer> statistics=new HashMap<>();
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
            if (value.path("claim").isString()) { publishClaim(value,source); return; }
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
    // * A rewritten sentence is shown only with evidence on its page. A rejected point takes its sub-points with it.
    private void publishClaim(JsonNode value, SummaryInput.Page source) {
        String claim=sentence(value.path("claim").asString(""));
        if (!grounded(claim,source,value.path("quote").asString(""))) return;
        if (seen.size()>=MAX_CLAIMS_PER_BATCH || !seen.add(SourceEvidence.compact(claim))) { count("duplicate"); return; }
        count("accepted");
        String section=title(value.path("section").asString(""),70,input.pages());
        String topic=title(value.path("topic").asString(""),60,List.of(source));
        var text=new StringBuilder(heading(section,topic.isEmpty() ? "핵심 내용" : topic));
        text.append(++claimNumber).append(". ").append(claim).append(" (p.").append(source.page()).append(")\n\n");
        int letters=0;
        for (var sub : value.path("sub")) {
            if (letters>=MAX_SUB_POINTS) break;
            int subPage=sub.path("page").canConvertToInt() ? sub.path("page").asInt() : source.page();
            var subSource=input.pages().stream().filter(p -> p.page()==subPage).findFirst().orElse(null);
            String point=sentence(sub.path("claim").asString(""));
            if (subSource==null) { count("quote"); continue; }
            if (!grounded(point,subSource,sub.path("quote").asString(""))) continue;
            if (!seen.add(SourceEvidence.compact(point))) { count("duplicate"); continue; }
            count("accepted");
            text.append("  ").append((char) ('a'+letters++)).append(". ").append(point).append(" (p.").append(subPage).append(")\n\n");
        }
        if (noteLinks!=null) {
            String concept=topic+" "+claim+" "+SourceEvidence.normalize(value.path("quote").asString(""));
            text.append(explanation(source.page(),concept,SourceEvidence.normalize(value.path("easy").asString(""))));
            text.append(noteLinks.takeFor(source.page(),concept));
        }
        accepted.append(text);
        publish.accept(text.toString());
    }

    // ! Overlap shows the sentence comes from this page, not that it is true; the page chip lets the learner check it.
    private boolean grounded(String claim, SummaryInput.Page source, String quote) {
        String reason=claim.isEmpty() ? "length"
                : !SourceEvidence.quoted(source.pdfText(),quote) ? "quote"
                : !SourceEvidence.supports(claim,source.pdfText()) || !SourceEvidence.related(claim,quote) ? "terms"
                : !SourceEvidence.numbersAppear(claim,source.pdfText()) ? "number"
                : !SourceEvidence.negationKept(claim,source.pdfText(),quote) ? "negation" : null;
        if (reason!=null) count(reason);
        return reason==null;
    }

    private void count(String outcome) { statistics.merge(outcome,1,Integer::sum); }

    // * Counts only, so the log can show how strict the checks are without keeping any study text.
    public Map<String,Integer> statistics() { return new TreeMap<>(statistics); }

    // * The stored text is parsed line by line, so a sentence keeps only bold and cannot start its own structure.
    private static String sentence(String raw) {
        String text=PAGE_MARK.matcher(SourceEvidence.normalize(raw)).replaceAll("").strip();
        while (LEADING_MARK.matcher(text).find()) text=LEADING_MARK.matcher(text).replaceFirst("");
        text=text.replace("`","").replaceAll("(?<!\\*)\\*(?!\\*)","");
        if ((text.length()-text.replace("**","").length())%4!=0) text=text.replace("**","");
        return text.length()<MIN_SENTENCE_LENGTH || text.length()>MAX_SENTENCE_LENGTH ? "" : text;
    }

    private static String title(String raw, int limit, List<SummaryInput.Page> sources) {
        String text=SourceEvidence.normalize(raw).replace("*","").replace("`","");
        while (LEADING_MARK.matcher(text).find()) text=LEADING_MARK.matcher(text).replaceFirst("");
        if (text.length()<2 || text.length()>limit) return "";
        String candidate=text;
        return sources.stream().anyMatch(page -> SourceEvidence.related(candidate,page.pdfText())) ? candidate : "";
    }

    private void publish(int page, String paragraph) {
        publish(page, paragraph, "", "", "", "", "");
    }

    private String heading(String section, String subheading) {
        String heading="";
        if (accepted.isEmpty() && subsectionNumber==0) {
            var sectionPages=plannedSection==null ? input.pages() : plannedSection.pages();
            int first=sectionPages.get(0).page();
            int last=sectionPages.get(sectionPages.size()-1).page();
            String range=first==last ? String.valueOf(first) : first+"–"+last;
            String title=plannedSection!=null ? plannedSection.title() : section.isEmpty() ? "핵심 정리" : section;
            heading="## "+sectionNumber+". "+title+" · "+range+"쪽\n\n";
        }
        if (!subheading.equals(previousTopic)) {
            previousTopic=subheading;
            claimNumber=0;
            heading+="### "+sectionNumber+"."+(++subsectionNumber)+" "+subheading+"\n\n";
        }
        return heading;
    }

    // * The excerpt stays source-verified. The explanation below it is labeled as AI-written, not as source text.
    private String explanation(int page, String concept, String easy) {
        if (easy.isEmpty() || easy.length()>MAX_EXPLANATION_LENGTH) return "";
        var memos=noteLinks.takeDifficulties(page,concept);
        return memos.isEmpty() ? "" : String.join("",memos)+"> 쉽게 풀어보면 · "+easy+"\n\n";
    }

    private void publish(int page, String paragraph, String label, String note, String section, String topic, String easy) {
        if (seen.size()>=MAX_ITEMS_PER_BATCH || !seen.add(page+":"+paragraph)) return;
        String subheading=topic.isEmpty() ? label.replace(" · ","") : topic;
        if (subheading.isEmpty()) subheading="원문 핵심";
        String verified=heading(section,subheading)+"- "+(topic.isEmpty() ? label : "")+paragraph+" (p."+page+")\n\n";
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
