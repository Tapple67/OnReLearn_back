package com.onrelearn.summary.service;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

// * Generation runs apart from the request so a closed window does not stop it; a request only watches a job.
// ! Jobs live in memory: a server restart loses running work, and the saved summary stays as it was.
@Service
public class SummaryJobs {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SummaryJobs.class);
    private static final int MAX_RUNNING = 4;
    private final MaterialSummaryService summaries;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_RUNNING, work -> {
        var thread = new Thread(work, "summary-job");
        thread.setDaemon(true);
        return thread;
    });

    public SummaryJobs(MaterialSummaryService summaries) { this.summaries = summaries; }

    public Job start(String memberId, String materialId) {
        // ! Check ownership first: the key alone must never let a request reach another member's work.
        summaries.get(memberId, materialId);
        String id = materialId.trim();
        String key = memberId + ":" + id;
        return jobs.compute(key, (ignored, current) -> {
            if (current != null && current.running()) return current;
            var job = new Job();
            pool.execute(() -> run(key, job, memberId, id));
            return job;
        });
    }

    public Status status(String memberId, String materialId) {
        summaries.get(memberId, materialId);
        var job = jobs.get(memberId + ":" + materialId.trim());
        if (job == null) return new Status("idle", "");
        return job.running() ? new Status("running", job.draft()) : new Status("failed", "");
    }

    private void run(String key, Job job, String memberId, String materialId) {
        try {
            var view = summaries.ensure(memberId, materialId, job::append);
            // * The summary is saved, so nothing remains to report; a watcher already holds the job.
            jobs.remove(key, job);
            job.finish(view, null);
        } catch (RuntimeException failure) {
            LOG.warn("Summary job failed: type={}", failure.getClass().getSimpleName());
            job.finish(null, failure);
        }
    }

    @PreDestroy public void shutdown() { pool.shutdownNow(); }

    public record Status(String status, String draft) {
        @Override public String toString() { return "SummaryJobStatus[" + status + "]"; }
    }

    public static final class Job {
        private final StringBuilder draft = new StringBuilder();
        private boolean running = true;
        private MaterialSummaryService.View result;
        private RuntimeException failure;

        private synchronized void append(String text) { draft.append(text); notifyAll(); }
        private synchronized void finish(MaterialSummaryService.View view, RuntimeException problem) {
            result = view; failure = problem; running = false;
            notifyAll();
        }
        private synchronized boolean running() { return running; }
        private synchronized String draft() { return draft.toString(); }

        // * Replays everything so far and then follows. A throwing consumer ends only this watcher, not the job.
        public MaterialSummaryService.View follow(Consumer<String> delta) {
            int position = 0;
            while (true) {
                String chunk;
                boolean finished;
                synchronized (this) {
                    while (position == draft.length() && running) {
                        try { wait(); }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Summary watcher interrupted");
                        }
                    }
                    chunk = draft.substring(position);
                    position = draft.length();
                    finished = !running;
                }
                if (!chunk.isEmpty()) delta.accept(chunk);
                if (!finished) continue;
                synchronized (this) {
                    if (failure != null) throw failure;
                    return result;
                }
            }
        }
    }
}
