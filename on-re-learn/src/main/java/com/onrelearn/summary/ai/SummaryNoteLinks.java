package com.onrelearn.summary.ai;

import com.onrelearn.ai.SourceEvidence;
import com.onrelearn.summary.service.SummaryInput.Page;
import java.text.BreakIterator;
import java.util.*;
import java.util.regex.Pattern;

/** Links saved note excerpts to actual PDF text in the same section. */
public final class SummaryNoteLinks {
    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}\\p{N}]+");
    private static final Set<String> COMMON = Set.of("이해", "어렵", "무엇", "어떻", "왜", "질문",
            "관련", "설명", "개념", "내용", "페이지", "공부", "헷갈", "한다", "있는", "있다",
            "쉽게", "쉬운", "쉽게 설명", "알려줘", "설명해줘", "모르겠어", "비교", "차이", "예시",
            "예제", "이유", "중요", "시험", "다시", "부탁", "어려워", "어렵다", "복습",
            "the", "and", "what", "how", "this", "that", "with", "about");
    private record Passage(int page, String text, Set<String> terms) {}
    private record Link(int notePage, String note, Passage evidence, Set<String> terms) {}
    private enum Intent {
        EASY("핵심 표현부터 읽어 보세요"), COMPARE("두 개념의 차이를 확인해 보세요"),
        EXAMPLE("원문에서 예시나 적용 상황을 찾아보세요"), REASON("원문에서 이유와 결과를 확인해 보세요"),
        REVIEW("복습할 핵심 표현을 확인해 보세요"), UNSUPPORTED("");

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
        var concepts = new TreeSet<>(link.terms());
        concepts.retainAll(link.evidence().terms());
        return Collections.unmodifiableSet(concepts);
    }

    private static Intent intent(String note) {
        if (note.matches("(?i).*(무시|프롬프트|시스템\\s*(지시|명령)|명령.{0,8}실행|ignore|prompt|https?://).*"))
            return Intent.UNSUPPORTED;
        if (note.matches(".*(쉽게|쉬운|초보|헷갈|모르겠|어렵|어려워).*")) return Intent.EASY;
        if (note.matches(".*(비교|차이|구분).*")) return Intent.COMPARE;
        if (note.matches(".*(예시|예제|사례).*")) return Intent.EXAMPLE;
        if (note.matches(".*(왜|이유|원인|어떻게|누가).*")) return Intent.REASON;
        if (note.matches(".*(복습|중요|시험|다시 볼).*")) return Intent.REVIEW;
        return Intent.UNSUPPORTED;
    }
    private final List<Link> pending = new ArrayList<>();

    public SummaryNoteLinks(List<Page> pages, boolean searchSection) {
        if (pages.stream().allMatch(page -> page.note().isBlank())) return;
        var passages = pages.stream().flatMap(page -> excerpts(page.pdfText().split("\\R\\s*\\R")).stream()
                .filter(text -> text.length()>=12 && text.length()<=240)
                .map(text -> new Passage(page.page(),text,terms(text)))).toList();
        for (var page : pages) {
            for (String note : excerpts(page.note().split("\\R"))) {
                if (intent(note) == Intent.UNSUPPORTED) continue;
                var keywords = terms(note);
                var evidence = best(passages,keywords,page.page(),true);
                if (evidence==null && searchSection) evidence=best(passages,keywords,page.page(),false);
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
        int matches=0;
        boolean distinctive=false;
        for (String term : note) {
            if (source.contains(term)) {
                matches++;
                distinctive|=term.length()>=4;
            }
        }
        return matches>=2 || distinctive ? matches : 0;
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

    public String finish() {
        var output=new StringBuilder();
        var questions=new StringBuilder();
        for (Link link : pending) {
            if (link.evidence()==null) {
                questions.append("> 내 메모 · ").append(link.note()).append(" (p.").append(link.notePage()).append(")\n\n");
                continue;
            }
            String topic=link.terms().stream().filter(link.evidence().terms()::contains)
                    .max(Comparator.comparingInt(String::length)).orElseThrow();
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
