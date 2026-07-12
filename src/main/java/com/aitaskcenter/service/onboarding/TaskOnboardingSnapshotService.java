package com.aitaskcenter.service.onboarding;

import com.aitaskcenter.repository.TaskResultRepository;
import com.aitaskcenter.repository.TaskRunRepository;
import com.aitaskcenter.repository.TaskRunResultRepository;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TaskOnboardingSnapshotService {
    private final TaskResultRepository taskResultRepository;
    private final TaskRunRepository taskRunRepository;
    private final TaskRunResultRepository taskRunResultRepository;

    public TaskOnboardingSnapshotService(
            TaskResultRepository taskResultRepository,
            TaskRunRepository taskRunRepository,
            TaskRunResultRepository taskRunResultRepository) {
        this.taskResultRepository = taskResultRepository;
        this.taskRunRepository = taskRunRepository;
        this.taskRunResultRepository = taskRunResultRepository;
    }

    public void capture(Long taskConfigId, String stage, TaskOnboardingContext context) {
        Snapshot snapshot = snapshot(
                taskResultRepository.findFingerprintRowsByTaskConfigId(taskConfigId),
                taskRunRepository.findFingerprintRowsByTaskConfigId(taskConfigId),
                taskRunResultRepository.findFingerprintRowsByTaskConfigId(taskConfigId));
        context.setBaselineStage(stage);
        context.setBaselineResultCount(snapshot.results().count());
        context.setBaselineResultFingerprint(snapshot.results().fingerprint());
        context.setBaselineRunCount(snapshot.runs().count());
        context.setBaselineRunFingerprint(snapshot.runs().fingerprint());
        context.setBaselineLinkCount(snapshot.links().count());
        context.setBaselineLinkFingerprint(snapshot.links().fingerprint());
    }

    public void validateResultCallback(
            Long taskConfigId, List<Long> submittedResultIds, TaskOnboardingContext context) {
        requireStage(context, "RESULT");
        Snapshot current = snapshot(
                taskResultRepository.findFingerprintRowsByTaskConfigIdAndIdNotIn(
                        taskConfigId, submittedResultIds),
                taskRunRepository.findFingerprintRowsByTaskConfigId(taskConfigId),
                taskRunResultRepository.findFingerprintRowsByTaskConfigId(taskConfigId));
        requireMatch(context, current);
    }

    public void validateBatchCallback(
            Long taskConfigId, Long submittedRunId, TaskOnboardingContext context) {
        requireStage(context, "BATCH");
        Snapshot current = snapshot(
                taskResultRepository.findFingerprintRowsByTaskConfigId(taskConfigId),
                taskRunRepository.findFingerprintRowsByTaskConfigIdAndIdNot(taskConfigId, submittedRunId),
                taskRunResultRepository.findFingerprintRowsByTaskConfigIdAndTaskRunIdNot(
                        taskConfigId, submittedRunId));
        requireMatch(context, current);
    }

    public void validateBaselineShape(TaskOnboardingContext context, String expectedStage) {
        requireStage(context, expectedStage);
        if (context.getBaselineResultCount() < 0
                || context.getBaselineRunCount() < 0
                || context.getBaselineLinkCount() < 0
                || !isSha256(context.getBaselineResultFingerprint())
                || !isSha256(context.getBaselineRunFingerprint())
                || !isSha256(context.getBaselineLinkFingerprint())) {
            throw new TaskOnboardingStateException("Task onboarding baseline is incomplete or corrupt");
        }
    }

    private void requireMatch(TaskOnboardingContext expected, Snapshot current) {
        if (expected.getBaselineResultCount() != current.results().count()
                || !expected.getBaselineResultFingerprint().equals(current.results().fingerprint())
                || expected.getBaselineRunCount() != current.runs().count()
                || !expected.getBaselineRunFingerprint().equals(current.runs().fingerprint())
                || expected.getBaselineLinkCount() != current.links().count()
                || !expected.getBaselineLinkFingerprint().equals(current.links().fingerprint())) {
            throw new IllegalArgumentException("Forbidden database side effects changed the protected baseline");
        }
    }

    private void requireStage(TaskOnboardingContext context, String expectedStage) {
        if (!expectedStage.equals(context.getBaselineStage())) {
            throw new TaskOnboardingStateException(
                    "Task onboarding baseline stage must be " + expectedStage);
        }
    }

    private Snapshot snapshot(List<String> resultRows, List<String> runRows, List<String> linkRows) {
        return new Snapshot(fingerprint(resultRows), fingerprint(runRows), fingerprint(linkRows));
    }

    private Fingerprint fingerprint(List<String> rows) {
        if (rows == null) {
            throw new TaskOnboardingStateException("Fingerprint projection returned null");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new TaskOnboardingStateException("SHA-256 is unavailable", ex);
        }
        for (String row : rows) {
            if (row == null) {
                throw new TaskOnboardingStateException("Fingerprint projection contained null");
            }
            byte[] bytes = row.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return new Fingerprint(rows.size(), HexFormat.of().formatHex(digest.digest()));
    }

    private boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private record Fingerprint(long count, String fingerprint) {
    }

    private record Snapshot(Fingerprint results, Fingerprint runs, Fingerprint links) {
    }
}
