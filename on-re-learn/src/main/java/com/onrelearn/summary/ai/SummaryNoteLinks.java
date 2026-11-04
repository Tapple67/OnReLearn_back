package com.onrelearn.summary.ai;

import com.onrelearn.ai.SourceEvidence;
import com.onrelearn.summary.service.SummaryInput.Page;
import java.text.BreakIterator;
import java.util.*;
import java.util.regex.Pattern;

/** Links saved note excerpts to actual PDF text in the same section. */
public final class SummaryNoteLinks {
    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}\\p{N}]+");
    private static final Pattern INSTRUCTION = Pattern.compile("(?i)(무시|프롬프트|시스템\\s*(지시|명령)|명령.{0,8}실행|ignore|prompt|https?://)");
    private static final int MIN_DISTINCTIVE_TERM = 3, MIN_SENTENCE = 12, MIN_SLIDE_LINE = 8, MAX_PASSAGE = 240;
    private static final Set<String> COMMON = Set.of("이해", "어렵", "무엇", "어떻", "왜", "질문",
            "관련", "설명", "개념", "내용", "페이지", "공부", "헷갈", "한다", "있는", "있다",
            "쉽게", "쉬운", "쉽게 설명", "알려줘", "설명해줘", "모르겠어", "비교", "차이", "예시",
            "예제", "이유", "중요", "시험", "다시", "부탁", "어려워", "어렵다", "어려운", "복습",
            "부분", "무슨", "말인지", "여기", "이거", "이게", "이건", "복잡", "복잡해", "복잡하다",
            "the", "and", "what", "how", "this", "that", "with", "about");
    private record Passage(int page, String text, Set<String> terms) {}
    private record Link(int notePage, String note, Passage evidence, Set<String> terms) {}
    private enum Intent {
        EASY("핵심 표현부터 읽어 보세요"), COMPARE("두 개념의 차이를 확인해 보세요"),
        EXAMPLE("원문에서 예시나 적용 상황을 찾아보세요"), REASON("원문에서 이유와 결과를 확인해 보세요"),
        REVIEW("복습할 핵심 표현을 확인해 보세요"), GENERAL("메모와 관련된 원문을 확인해 보세요"), INSTRUCTION("");

        private final String guide;
        Intent(String guide) { this.guide = guide; }
    }

    public record Focus(int page, String quote, Set<String> concepts) {
        public boolean matches(int evidencePage, String evidence) {
            return page == evidencePage && score(concepts, terms(evidence)) > 0;
        }
        @Override public String toString() { return "MemoFocus[content omitted]"; }
    }

    public static List<Focus> quizFocus(List<Page> pages) {
        var sections = SummaryOutline.find(pages);
        var links = sections.isEmpty() ? java.util.stream.Stream.of(new SummaryNoteLinks(pages, false))
                : sections.stream().map(section -> new SummaryNoteLinks(section.pages(), true));
        return links.flatMap(group -> group.pending.stream()).filter(link -> link.evidence() != null)
                .map(link -> new Focus(link.evidence().page(), link.evidence().text(),
                        matchedTerms(link))).distinct().toList();
    }

    private static Set<String> matchedTerms(Link link) {
        return Collections.unmodifiableSet(new TreeSet<>(shared(link.terms(),link.evidence().terms())));
    }

    private static Intent intent(String note) {
        if (INSTRUCTION.matcher(note).find()) return Intent.INSTRUCTION;
        if (note.matches(".*(쉽게|쉬운|초보|헷갈|모르겠|어려|복잡|풀어\\s*(줘|서|설명)|이해\\s*(가|를|이)?\\s*(안|못)|무슨\\s*(말|소리)|뭔\\s*(말|소리)).*")) return Intent.EASY;
        if (note.matches(".*(비교|차이|구분).*")) return Intent.COMPARE;
        if (note.matches(".*(예시|예제|사례).*")) return Intent.EXAMPLE;
        if (note.matches(".*(왜|이유|원인|어떻게|누가).*")) return Intent.REASON;
        if (note.matches(".*(복습|중요|시험|다시 볼).*")) return Intent.REVIEW;
        return Intent.GENERAL;
    }

    // ! Memos reach the model as study data; lines that look like instructions never do.
    public static String promptSafe(String note) {
        return note.lines().filter(line -> !INSTRUCTION.matcher(line).find())
                .collect(java.util.stream.Collectors.joining("\n"));
    }
    private final List<Link> pending = new ArrayList<>();

    public SummaryNoteLinks(List<Page> pages, boolean searchSection) {
        if (pages.stream().allMatch(page -> page.note().isBlank())) return;
        var passages = pages.stream().flatMap(page -> passages(page.pdfText()).stream()
                .map(text -> new Passage(page.page(),text,terms(text)))).toList();
        for (var page : pages) {
            for (String note : excerpts(page.note().split("\\R"))) {
                var intent = intent(note);
                if (intent == Intent.INSTRUCTION) continue;
                var keywords = terms(note);
                var evidence = best(passages,keywords,page.page(),true);
                if (evidence==null && searchSection) evidence=best(passages,keywords,page.page(),false);
                // * The model already reads requests like "핵심만 정리해줘"; only real questions stay listed.
                if (intent == Intent.GENERAL && evidence == null) continue;
                pending.add(new Link(page.page(),note,evidence,keywords));
            }
        }
    }

    private static Passage best(List<Passage> passages, Set<String> terms, int page, boolean samePage) {
        Passage best=null;
        int bestScore=0;
        for (Passage passage : passages) {
            if ((passage.page()==page)!=samePage) continue;
            int score=score(terms,passage.terms());
            if (score>bestScore) { best=passage; bestScore=score; }
        }
        return best;
    }

    private static int score(Set<String> note, Set<String> source) {
        var matches=shared(note,source);
        boolean distinctive=matches.stream().anyMatch(term -> term.length()>=MIN_DISTINCTIVE_TERM);
        return matches.size()>=2 || distinctive ? matches.size() : 0;
    }

    // * Prefix matching absorbs Korean particles that the ending list misses, such as 대칭키랑 and 대칭키.
    private static Set<String> shared(Set<String> note, Set<String> source) {
        var matches=new LinkedHashSet<String>();
        for (String term : note) {
            if (source.stream().anyMatch(word -> word.startsWith(term) || term.startsWith(word))) matches.add(term);
        }
        return matches;
    }

    // * PDF text keeps single line breaks only, so an unpunctuated slide page is compared line by line.
    private static List<String> passages(String pdfText) {
        var passages=new LinkedHashSet<String>();
        for (String block : pdfText.split("\\R\\s*\\R")) {
            for (String sentence : excerpts(new String[]{block})) {
                if (sentence.length()<=MAX_PASSAGE) {
                    if (sentence.length()>=MIN_SENTENCE) passages.add(sentence);
                    continue;
                }
                block.lines().map(SourceEvidence::normalize)
                        .filter(line -> line.length()>=MIN_SLIDE_LINE && line.length()<=MAX_PASSAGE).forEach(passages::add);
            }
        }
        return List.copyOf(passages);
    }

    private static Set<String> terms(String text) {
        var words=new LinkedHashSet<String>();
        var matcher=WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word=matcher.group().replaceFirst("(?<=.{2})(으로|에서|에게|까지|은|는|이|가|을|를|에|의|와|과|도|만)$","");
            if (!COMMON.contains(word)) words.add(word);
        }
        return words;
    }

    private static List<String> excerpts(String[] blocks) {
        var excerpts=new LinkedHashSet<String>();
        var sentences=BreakIterator.getSentenceInstance(Locale.KOREAN);
        for (String block : blocks) {
            String normalized=SourceEvidence.normalize(block);
            sentences.setText(normalized);
            for (int start=sentences.first(), end=sentences.next(); end!=BreakIterator.DONE; start=end,end=sentences.next()) {
                String excerpt=normalized.substring(start,end).strip();
                if (!excerpt.isEmpty()) excerpts.add(excerpt);
            }
        }
        return List.copyOf(excerpts);
    }

    String takeFor(int page, String concept) {
        var keywords=terms(concept);
        var output=new StringBuilder();
        var iterator=pending.iterator();
        while (iterator.hasNext()) {
            var link=iterator.next();
            if (link.evidence()==null || link.evidence().page()!=page || score(link.terms(),keywords)==0) continue;
            append(output,link);
            iterator.remove();
        }
        return output.toString();
    }

    // ! Only a saved difficulty memo unlocks a model-written explanation; each memo unlocks one.
    List<String> takeDifficulties(int page, String concept) {
        var keywords=terms(concept);
        var memos=new ArrayList<String>();
        var iterator=pending.iterator();
        while (iterator.hasNext()) {
            var link=iterator.next();
            if (intent(link.note())!=Intent.EASY) continue;
            boolean pageWide=link.terms().isEmpty() && link.notePage()==page;
            if (!pageWide && score(link.terms(),keywords)==0) continue;
            memos.add("> 내 메모 · "+link.note()+" (p."+link.notePage()+")\n\n");
            iterator.remove();
        }
        return memos;
    }

    public String finish() {
        var output=new StringBuilder();
        var questions=new StringBuilder();
        for (Link link : pending) {
            if (link.evidence()==null) {
                questions.append("> 내 메모 · ").append(link.note()).append(" (p.").append(link.notePage()).append(")\n\n");
                continue;
            }
            String topic=matchedTerms(link).stream().max(Comparator.comparingInt(String::length)).orElseThrow();
            output.append("### ").append(topic).append(" · 메모 보충\n\n");
            append(output,link);
        }
        if (!questions.isEmpty()) output.append("### 다시 볼 질문\n\n").append(questions);
        pending.clear();
        return output.toString();
    }

    private static void append(StringBuilder output, Link link) {
        output.append("> 추가 설명 · ");
        var intent = intent(link.note());
        if (intent == Intent.EASY) {
            String concept = matchedTerms(link).stream().max(Comparator.comparingInt(String::length)).orElseThrow();
            output.append("‘").append(concept).append("’부터 보면 이해하기 쉬워요. ");
        } else if (!intent.guide.isEmpty()) {
            output.append(intent.guide).append(". ");
        }
        output.append(link.evidence().text()).append(" (p.").append(link.evidence().page()).append(")\n\n");
    }
}
