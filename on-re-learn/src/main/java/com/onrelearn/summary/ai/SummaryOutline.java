package com.onrelearn.summary.ai;

import com.onrelearn.ai.SourceEvidence;
import com.onrelearn.summary.service.SummaryInput.Page;
import java.util.*;
import java.util.regex.Pattern;

public final class SummaryOutline {
    private static final Pattern NUMBERED=Pattern.compile("^([1-9][0-9]?)\\s*[.)]\\s+(.{2,90})$");
    private static final Pattern CONTENTS=Pattern.compile("(?i)^(outline|table of contents|contents|목차|차례)\\s*[:：]?$");

    private SummaryOutline() {}

    public record Section(int number, String title, List<Page> pages) {}
    private record Heading(int index, int number, String title) {}

    public static List<Section> find(List<Page> pages) {
        Map<Integer, Set<String>> contents=new HashMap<>();
        List<Heading> candidates=new ArrayList<>();
        for (int i=0; i<pages.size(); i++) {
            var lines=pages.get(i).pdfText().lines().map(SourceEvidence::normalize)
                    .filter(line -> !line.isBlank() && !line.matches("[0-9]+")).toList();
            if (lines.isEmpty()) continue;
            if (CONTENTS.matcher(lines.get(0)).matches()) {
                for (String line : lines.subList(1,lines.size())) {
                    var entry=NUMBERED.matcher(line);
                    if (entry.matches()) contents.computeIfAbsent(Integer.parseInt(entry.group(1)),
                            key -> new HashSet<>()).add(titleKey(entry.group(2)));
                }
                continue;
            }
            var heading=NUMBERED.matcher(lines.get(0));
            if (heading.matches()) candidates.add(new Heading(i,Integer.parseInt(heading.group(1)),heading.group(2)));
        }
        List<Heading> boundaries=new ArrayList<>();
        for (Heading heading : candidates) {
            // * Match the displayed title on a body page. TOC page numbers can differ from PDF page indexes.
            if (heading.number()!=boundaries.size()+1) continue;
            if (!contents.isEmpty() && !contents.getOrDefault(heading.number(),Set.of()).contains(titleKey(heading.title()))) continue;
            boundaries.add(heading);
        }
        if (boundaries.size()<(contents.isEmpty() ? 2 : 1)) return List.of();
        List<Section> sections=new ArrayList<>();
        if (boundaries.get(0).index()>0) sections.add(new Section(0,"도입",
                List.copyOf(pages.subList(0,boundaries.get(0).index()))));
        for (int i=0; i<boundaries.size(); i++) {
            Heading start=boundaries.get(i);
            int end=i+1<boundaries.size() ? boundaries.get(i+1).index() : pages.size();
            sections.add(new Section(start.number(),start.title(),List.copyOf(pages.subList(start.index(),end))));
        }
        return List.copyOf(sections);
    }

    private static String titleKey(String title) {
        return title.replaceFirst("\\s*(?:[.·…]{2,}\\s*|\\s+)[0-9]+$", "")
                .replaceFirst("\\s*\\([^()]+\\)\\s*$","").strip().toLowerCase(Locale.ROOT);
    }
}
