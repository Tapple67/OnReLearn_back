package com.onrelearn.summary.service;

import com.onrelearn.summary.ai.*;
import com.onrelearn.summary.entity.MaterialSummary;
import com.onrelearn.summary.repository.MaterialSummaryRepository;
import com.onrelearn.material.repository.MaterialRepository;
import com.onrelearn.subject.repository.SubjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MaterialSummaryService {
    private final SummaryInputService inputs;
    private final SummaryProvider provider;
    private final MaterialSummaryRepository summaries;
    private final MaterialRepository materials;
    private final SubjectRepository subjects;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public MaterialSummaryService(SummaryInputService inputs, SummaryProvider provider,
            MaterialSummaryRepository summaries, MaterialRepository materials, SubjectRepository subjects,
            PlatformTransactionManager transactions) {
        this.inputs = inputs; this.provider = provider; this.summaries = summaries;
        this.materials = materials; this.subjects = subjects;
        read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write = new TransactionTemplate(transactions);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Transactional(readOnly = true)
    public View get(String memberId, String materialId) {
        if (memberId == null || memberId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (materialId == null || materialId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Material ID is required");
        }
        // ! Check ownership even when no summary exists; reads never invoke the provider.
        var material = materials.findByIdAndSubject_Grade_User_Id(materialId.trim(), memberId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Material unavailable"));
        return summaries.findByMaterial_Id(material.getId()).filter(summary -> SummaryPrompt.VERSION.equals(summary.getPromptVersion()))
                .map(summary -> view(summary, true)).orElse(null);
    }

    // ! Suspend any caller transaction so provider latency never holds database locks/connections.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public View ensure(String memberId, String materialId) {
        return ensure(memberId, materialId, null);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public View ensure(String memberId, String materialId, java.util.function.Consumer<String> delta) {
        Prepared prepared = read.execute(status -> {
            var input = inputs.build(memberId, materialId, SummaryPrompt.VERSION);
            var fingerprint = input.fingerprint();
            var cached = summaries.findByMaterial_Id(input.materialId())
                    .filter(summary -> fingerprint.matches(summary.getSourceHash(), summary.getSignalsDigest()))
                    .filter(summary -> SummaryPrompt.VERSION.equals(summary.getPromptVersion()))
                .map(summary -> view(summary, true)).orElse(null);
            return new Prepared(input, fingerprint, cached);
        });
        if (prepared.cached() != null) return prepared.cached();
        var result = delta == null ? provider.generate(prepared.input()) : provider.generate(prepared.input(), delta);
        if (result == null || !prepared.input().promptVersion().equals(result.promptVersion())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid summary response");
        }
        return write.execute(status -> {
            // ! Share the note-save/material-delete lock and recheck ownership before writing.
            subjects.findOwnedForUpdate(prepared.input().subjectId(), memberId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Material unavailable"));
            var current = inputs.build(memberId, prepared.input().materialId(), SummaryPrompt.VERSION);
            if (!prepared.fingerprint().equals(current.fingerprint())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Summary source changed; retry generation");
            }
            var existing = summaries.findByMaterial_Id(current.materialId());
            // * Concurrent requests for the same source reuse the first committed summary.
            if (existing.isPresent() && prepared.fingerprint().matches(existing.get().getSourceHash(), existing.get().getSignalsDigest())) {
                return view(existing.get(), true);
            }
            var material = materials.findByIdAndSubject_Grade_User_Id(current.materialId(), memberId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Material unavailable"));
            var summary = existing.orElseGet(() -> MaterialSummary.builder().material(material)
                    .sourceHash(prepared.fingerprint().sourceHash()).content(result.canonical()).build());
            summary.replace(prepared.fingerprint().sourceHash(), result.canonical(), result.canonical(), result.adaptive(),
                    "both", prepared.fingerprint().signalsDigest(), result.provider(), result.model(), result.promptVersion());
            return view(summaries.saveAndFlush(summary), false);
        });
    }

    private static View view(MaterialSummary summary, boolean reused) {
        return new View(summary.getId(), summary.getSourceHash(),
                summary.getCanonical() == null ? summary.getContent() : summary.getCanonical(),
                summary.getAdaptive() == null ? summary.getContent() : summary.getAdaptive(), reused);
    }
    private record Prepared(SummaryInput input, SummaryInput.Fingerprint fingerprint, View cached) {}
    public record View(String summaryId, String sourceHash, String content, String adaptive, boolean reused) {
        @Override public String toString() { return "SummaryView[content omitted]"; }
    }
}
