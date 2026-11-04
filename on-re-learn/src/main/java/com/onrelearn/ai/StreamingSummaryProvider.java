package com.onrelearn.ai;

import com.onrelearn.summary.ai.*;
import com.onrelearn.summary.service.SummaryInput;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

public final class StreamingSummaryProvider implements SummaryProvider {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(StreamingSummaryProvider.class);
    private static final int SPARSE_PAGE_CHARS = 100;
    private final String provider, endpoint, key, model, reasoning;
    private final Duration idle;
    private final int batchPages, batchChars, concurrency;
    private final String thinkingLevel;
    private final boolean overview;
    private final SummaryPrompt prompt = new SummaryPrompt();
    private final JsonMapper json = JsonMapper.builder().build();
    public StreamingSummaryProvider(String provider, String endpoint, String key, String model, String reasoning, Duration idle) {
        this(provider,endpoint,key,model,reasoning,idle,8,16000);
    }
    public StreamingSummaryProvider(String provider, String endpoint, String key, String model, String reasoning, Duration idle, int batchPages, int batchChars) {
        this(provider,endpoint,key,model,reasoning,idle,batchPages,batchChars,"");
    }
    public StreamingSummaryProvider(String provider, String endpoint, String key, String model, String reasoning, Duration idle, int batchPages, int batchChars, String thinkingLevel) {
        this(provider,endpoint,key,model,reasoning,idle,batchPages,batchChars,thinkingLevel,1);
    }
    public StreamingSummaryProvider(String provider, String endpoint, String key, String model, String reasoning, Duration idle, int batchPages, int batchChars, String thinkingLevel, int concurrency) {
        this(provider,endpoint,key,model,reasoning,idle,batchPages,batchChars,thinkingLevel,concurrency,false);
    }
    public StreamingSummaryProvider(String provider, String endpoint, String key, String model, String reasoning, Duration idle, int batchPages, int batchChars, String thinkingLevel, int concurrency, boolean overview) {
        if (concurrency<1 || concurrency>10) throw new IllegalArgumentException("Invalid summary concurrency");
        this.concurrency=concurrency;
        this.overview=overview;
        this.provider=provider; this.endpoint=endpoint; this.key=key; this.model=model; this.reasoning=reasoning; this.idle=idle;
        this.thinkingLevel=thinkingLevel.strip().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("","MINIMAL","LOW","MEDIUM","HIGH").contains(this.thinkingLevel)) throw new IllegalArgumentException("Invalid summary thinking level");
        if (batchPages<1 || batchPages>50 || batchChars<1000 || batchChars>100000) throw new IllegalArgumentException("Invalid summary batch limits");
        this.batchPages=batchPages; this.batchChars=batchChars;
    }
    @Override public SummaryResult generate(SummaryInput input) { return generate(input, text -> {}); }
    @Override public SummaryResult generate(SummaryInput input, Consumer<String> delta) {
        var sections=SummaryOutline.find(input.pages());
        var batches=new java.util.ArrayList<Batch>();
        var notesWithoutPdf=new StringBuilder();
        if (sections.isEmpty()) {
            for (var pages : split(input.pages())) batches.add(new Batch(null,pages));
        } else {
            for (var section : sections) {
                var chunks=split(section.pages());
                if (chunks.isEmpty()) notesWithoutPdf.append(new SummaryNoteLinks(section.pages(),false).finish());
                for (var pages : chunks) batches.add(new Batch(section,pages));
            }
        }
        if (batches.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"요약할 PDF 원문을 찾을 수 없습니다.");
        long deadline=System.nanoTime()+Duration.ofMinutes(15).toNanos();
        StringBuilder combined=new StringBuilder();
        SummaryOutline.Section activeSection=null;
        var continuation=GroundedSummary.Continuation.start();
        SummaryNoteLinks noteLinks=sections.isEmpty() ? new SummaryNoteLinks(input.pages(),false) : null;
        var parts=new java.util.ArrayList<SummaryInput>();
        for (var batch : batches) {
            var pages=batch.pages();
            String title=batch.section()==null ? input.title() : input.title()+" / "+batch.section().title();
            if (batches.size()>1) title += " (p."+pages.get(0).page()+"–"+pages.get(pages.size()-1).page()+")";
            parts.add(new SummaryInput(input.materialId(),input.subjectId(),input.week(),title,input.promptVersion(),pages));
        }
        // * Only the model calls overlap. Verification stays in order because headings and memo links depend on it.
        var fetches=new Fetch[batches.size()];
        var workers=Executors.newFixedThreadPool(concurrency, work -> {
            var thread=new Thread(work,"summary-batch");
            thread.setDaemon(true);
            return thread;
        });
        try {
        for (int index=0; index<batches.size(); index++) {
            var batch=batches.get(index);
            if (batch.section()!=activeSection) {
                activeSection=batch.section();
                // * A section from the document's own outline starts fresh, but repeated sentences stay removed.
                continuation=GroundedSummary.Continuation.start(continuation.claims());
                noteLinks=new SummaryNoteLinks(activeSection.pages(),true);
            }
            if (System.nanoTime()>=deadline) throw failure(504);
            for (int ahead=index; ahead<Math.min(index+concurrency,batches.size()); ahead++) {
                if (fetches[ahead]==null) fetches[ahead]=fetch(parts.get(ahead),deadline,workers);
            }
            var part=parts.get(index);
            var verification=new GroundedSummary(part,delta,activeSection,continuation);
            verification.linkNotes(noteLinks);
            boolean retried=false;
            try {
                drain(fetches[index],verification);
                verification.finishLine();
            } catch (ResponseStatusException failure) {
                if (!(failure instanceof RetryableStreamFailure) || System.nanoTime()>=deadline
                        || Thread.currentThread().isInterrupted()) throw failure;
                LOG.warn("Retrying summary batch {}/{} after status {}", index+1, batches.size(), failure.getStatusCode().value());
                verification.discardIncompleteLine();
                streamBatch(part,verification::append,deadline);
                verification.finishLine();
                retried=true;
            }
            if (!verification.hasVerifiedExcerpts() && !retried && System.nanoTime()<deadline) {
                LOG.warn("Retrying summary batch {}/{} after no verified excerpts", index+1, batches.size());
                streamBatch(part,verification::append,deadline);
                verification.finishLine();
            }
            LOG.info("Summary batch {}/{}: {}", index+1, batches.size(), verification.statistics());
            if (!verification.hasVerifiedExcerpts()) {
                LOG.warn("Selecting a verified source excerpt for summary batch {}/{}", index+1, batches.size());
                verification.publishSourceFallback();
            }
            try {
                combined.append(verification.finish());
                continuation=verification.continuation();
            } catch (ResponseStatusException failure) {
                LOG.warn("Summary batch {}/{} ended without verified excerpts", index+1, batches.size());
                throw failure;
            }
            if (index+1==batches.size() || batches.get(index+1).section()!=activeSection) {
                String notes=noteLinks.finish();
                if (!notes.isEmpty()) {
                    combined.append(notes);
                    delta.accept(notes);
                }
            }
        }
        } finally { workers.shutdownNow(); }
        if (!notesWithoutPdf.isEmpty()) {
            combined.append(notesWithoutPdf);
            delta.accept(notesWithoutPdf.toString());
        }
        String sparse=sparsePages(input.pages());
        if (!sparse.isEmpty()) delta.accept(sparse);
        // * Goals need the finished summary, so they appear once it is saved rather than in the live preview.
        if (overview && batches.size()>1) combined.insert(0,goals(input,combined.toString(),deadline));
        combined.append(sparse);
        return new SummaryResult(combined.toString(),null,null,provider,model,input.promptVersion());
    }
    private record Batch(SummaryOutline.Section section, java.util.List<SummaryInput.Page> pages) {}

    // * Only text is stored per page, so a title slide is listed too. The wording says "may" for that reason.
    private static String sparsePages(java.util.List<SummaryInput.Page> pages) {
        var chips=new java.util.StringJoiner(" ");
        for (var page : pages) {
            if (page.pdfText().replaceAll("\\s","").length()<SPARSE_PAGE_CHARS) chips.add("(p."+page.page()+")");
        }
        if (chips.length()==0) return "";
        return "## 원문에서 확인할 쪽\n\n글자가 적은 쪽입니다. 그림이나 표의 내용이 요약에 빠졌을 수 있으니 원문에서 확인해 주세요.\n\n"+chips+"\n\n";
    }

    // * Text waits here until earlier batches are published, so the reader always sees the document order.
    private static final class Fetch {
        private static final Object END=new Object();
        private final BlockingQueue<Object> events=new LinkedBlockingQueue<>();
    }

    private Fetch fetch(SummaryInput part, long deadline, ExecutorService workers) {
        var fetch=new Fetch();
        workers.execute(() -> {
            try {
                streamBatch(part,fetch.events::add,deadline);
                fetch.events.add(Fetch.END);
            } catch (RuntimeException problem) { fetch.events.add(problem); }
        });
        return fetch;
    }

    private void drain(Fetch fetch, GroundedSummary verification) {
        try {
            while (true) {
                Object event=fetch.events.take();
                if (event==Fetch.END) return;
                if (event instanceof RuntimeException problem) throw problem;
                verification.append((String) event);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure(503);
        }
    }

    private java.util.List<java.util.List<SummaryInput.Page>> split(java.util.List<SummaryInput.Page> pages) {
        var batches=new java.util.ArrayList<java.util.List<SummaryInput.Page>>();
        var current=new java.util.ArrayList<SummaryInput.Page>();
        long chars=0;
        for (var page : pages) {
            if (page.pdfText().isBlank()) continue;
            long size=(long)page.pdfText().length()+page.note().length();
            if (!current.isEmpty() && (current.size()>=batchPages || chars+size>batchChars)) {
                batches.add(java.util.List.copyOf(current));
                current.clear();
                chars=0;
            }
            current.add(page);
            chars+=size;
        }
        if (!current.isEmpty()) batches.add(java.util.List.copyOf(current));
        return batches;
    }

    private String goals(SummaryInput input, String summary, long deadline) {
        try {
            var raw=new StringBuilder();
            streamText(prompt.overview(input.title(),summary),raw::append,deadline);
            return GroundedSummary.overview(raw.toString(),summary);
        } catch (RuntimeException problem) {
            // ! The summary is already complete; losing the goals must not lose it.
            LOG.warn("Summary goals skipped: type={}", problem.getClass().getSimpleName());
            return "";
        }
    }

    private void streamBatch(SummaryInput input, Consumer<String> sink, long deadline) {
        streamText(prompt.render(input),sink,deadline);
    }

    private void streamText(String text, Consumer<String> sink, long deadline) {
        if (key.isBlank() || key.contains("\n") || key.contains("\r") || !model.matches("[A-Za-z0-9._-]+") || idle.toMillis()<1)
            throw failure(503);
        boolean gemini=provider.equals("gemini");
        String address=endpoint.replace("{model}",model);
        if (gemini) address=address.replace(":generateContent",":streamGenerateContent")+"?alt=sse";
        URI uri;
        try { uri=URI.create(address); } catch (RuntimeException exception) { throw failure(503); }
        if (uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null ||
                !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))))) throw failure(503);
        var body=json.createObjectNode();
        if (gemini) body.putArray("contents").addObject().put("role","user").putArray("parts").addObject().put("text",text);
        else {
            body.put("model",model).put("store",false).put("stream",true).put("input",text);
            if (!reasoning.isBlank()) body.putObject("reasoning").put("effort",reasoning);
        }
        if (gemini && !thinkingLevel.isEmpty()) body.putObject("generationConfig").putObject("thinkingConfig").put("thinkingLevel",thinkingLevel);
        var remaining=Duration.ofNanos(Math.max(1,deadline-System.nanoTime()));
        var request=HttpRequest.newBuilder(uri).timeout(idle.compareTo(remaining)<0 ? idle : remaining).header("Content-Type","application/json")
                .header(gemini ? "x-goog-api-key" : "Authorization", gemini ? key : "Bearer "+key)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var timer=Executors.newSingleThreadScheduledExecutor();
        AtomicLong last=new AtomicLong(System.nanoTime());
        var expired=new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            var response=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(idle).build()
                    .send(request,HttpResponse.BodyHandlers.ofInputStream());
            try (var stream=response.body()) {
                if (response.statusCode()!=200) LOG.warn("Summary provider returned HTTP {}", response.statusCode());
                if (response.statusCode()==429 || response.statusCode()>=500) throw new RetryableStreamFailure(502);
                if (response.statusCode()!=200) throw failure(502);
                // ! Bound inactivity and total runtime; partial output is never persisted as a success.
                timer.scheduleAtFixedRate(() -> {
                    long now=System.nanoTime();
                    if (now-last.get()>idle.toNanos() || now>=deadline) {
                        expired.set(true); try { stream.close(); } catch(IOException ignored) {}
                    }
                },100,100,TimeUnit.MILLISECONDS);
                var reader=new BufferedReader(new InputStreamReader(stream,java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder data=new StringBuilder(); boolean completed=false;
                for (String line; (line=reader.readLine())!=null;) {
                    if (line.startsWith("data:")) data.append(line.substring(5).stripLeading()).append('\n');
                    if (!line.isEmpty() || data.isEmpty()) continue;
                    last.set(System.nanoTime());
                    String event=data.toString().strip(); data.setLength(0);
                    if (event.equals("[DONE]")) continue;
                    var node=json.readTree(event);
                    String chunk="";
                    if (gemini) {
                        if (node.path("promptFeedback").has("blockReason") || node.has("error")) throw failure(502);
                        var candidate=node.path("candidates").path(0);
                        String finish=candidate.path("finishReason").asString("");
                        if (finish.equals("MAX_TOKENS")) throw new RetryableStreamFailure(502);
                        if (!finish.isEmpty() && !finish.equals("STOP")) {
                            LOG.warn("Summary provider ended without a successful finish");
                            throw failure(502);
                        }
                        completed |= finish.equals("STOP");
                        for (var part:candidate.path("content").path("parts")) if (!part.path("thought").asBoolean(false)) chunk+=part.path("text").asString("");
                    } else {
                        String type=node.path("type").asString("");
                        if (type.equals("response.output_text.delta")) chunk=node.path("delta").asString("");
                        if (type.equals("response.completed")) completed=true;
                        if (type.equals("error") || type.equals("response.failed") || type.equals("response.incomplete") || type.contains("refusal")) throw failure(502);
                    }
                    if (!chunk.isEmpty()) sink.accept(chunk);
                    if (completed) break;
                }
                if (!completed) throw new RetryableStreamFailure(expired.get() ? 504 : 502);
            }
        } catch(HttpTimeoutException exception) { throw new RetryableStreamFailure(504); }
        catch(IOException exception) { throw new RetryableStreamFailure(expired.get() ? 504 : 502); }
        catch(InterruptedException exception) { Thread.currentThread().interrupt(); throw failure(503); }
        finally { timer.shutdownNow(); }
    }
    private static final class RetryableStreamFailure extends ResponseStatusException {
        RetryableStreamFailure(int status) {
            super(HttpStatus.valueOf(status), status==504 ? "Summary provider timed out" : "Summary stream interrupted");
        }
    }
    private ResponseStatusException failure(int status) { return new ResponseStatusException(HttpStatus.valueOf(status), status==504 ? "Summary provider timed out" : "Summary provider unavailable"); }
}
