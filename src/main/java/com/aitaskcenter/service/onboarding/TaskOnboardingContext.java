package com.aitaskcenter.service.onboarding;

import java.util.ArrayList;
import java.util.List;

public class TaskOnboardingContext {
    private String resultValidationRunId = "";
    private String resultReportToken = "";
    private List<Long> resultValidationIds = new ArrayList<>();
    private String resultArtifactPath = "";
    private String resultArtifactHash = "";
    private String resultCleanupCompletedFor = "";
    private boolean overwriteExistingFormalResults;
    private String completedResultGenerationId = "";
    private long completedResultCount;
    private String batchValidationMarker = "";
    private String batchReportToken = "";
    private Long batchValidationTaskRunId;
    private List<Long> batchValidationResultIds = new ArrayList<>();
    private String batchArtifactPath = "";
    private String batchArtifactHash = "";
    private String batchCleanupCompletedFor = "";
    private long batchExpectedResultCount = -1;
    private String batchExpectedResultFingerprint = "";
    private String completedBatchGenerationId = "";
    private long completedBatchRunCount;
    private long completedBatchLinkCount;
    private String errorMessage = "";
    private String baselineStage = "";
    private long baselineResultCount = -1;
    private String baselineResultFingerprint = "";
    private long baselineRunCount = -1;
    private String baselineRunFingerprint = "";
    private long baselineLinkCount = -1;
    private String baselineLinkFingerprint = "";

    public String getResultValidationRunId() {
        return resultValidationRunId;
    }

    public void setResultValidationRunId(String resultValidationRunId) {
        this.resultValidationRunId = resultValidationRunId;
    }

    public String getResultReportToken() {
        return resultReportToken;
    }

    public void setResultReportToken(String resultReportToken) {
        this.resultReportToken = resultReportToken;
    }

    public List<Long> getResultValidationIds() {
        return resultValidationIds;
    }

    public void setResultValidationIds(List<Long> resultValidationIds) {
        this.resultValidationIds = resultValidationIds;
    }

    public String getResultArtifactPath() {
        return resultArtifactPath;
    }

    public void setResultArtifactPath(String resultArtifactPath) {
        this.resultArtifactPath = resultArtifactPath;
    }

    public String getResultArtifactHash() {
        return resultArtifactHash;
    }

    public void setResultArtifactHash(String resultArtifactHash) {
        this.resultArtifactHash = resultArtifactHash;
    }

    public String getResultCleanupCompletedFor() {
        return resultCleanupCompletedFor;
    }

    public void setResultCleanupCompletedFor(String resultCleanupCompletedFor) {
        this.resultCleanupCompletedFor = resultCleanupCompletedFor;
    }

    public boolean isOverwriteExistingFormalResults() {
        return overwriteExistingFormalResults;
    }

    public void setOverwriteExistingFormalResults(boolean overwriteExistingFormalResults) {
        this.overwriteExistingFormalResults = overwriteExistingFormalResults;
    }

    public String getCompletedResultGenerationId() {
        return completedResultGenerationId;
    }

    public void setCompletedResultGenerationId(String value) {
        this.completedResultGenerationId = value;
    }

    public long getCompletedResultCount() {
        return completedResultCount;
    }

    public void setCompletedResultCount(long value) {
        this.completedResultCount = value;
    }

    public String getBatchValidationMarker() {
        return batchValidationMarker;
    }

    public void setBatchValidationMarker(String batchValidationMarker) {
        this.batchValidationMarker = batchValidationMarker;
    }

    public String getBatchReportToken() {
        return batchReportToken;
    }

    public void setBatchReportToken(String batchReportToken) {
        this.batchReportToken = batchReportToken;
    }

    public Long getBatchValidationTaskRunId() {
        return batchValidationTaskRunId;
    }

    public void setBatchValidationTaskRunId(Long batchValidationTaskRunId) {
        this.batchValidationTaskRunId = batchValidationTaskRunId;
    }

    public List<Long> getBatchValidationResultIds() {
        return batchValidationResultIds;
    }

    public void setBatchValidationResultIds(List<Long> batchValidationResultIds) {
        this.batchValidationResultIds = batchValidationResultIds;
    }

    public String getBatchArtifactPath() {
        return batchArtifactPath;
    }

    public void setBatchArtifactPath(String batchArtifactPath) {
        this.batchArtifactPath = batchArtifactPath;
    }

    public String getBatchArtifactHash() {
        return batchArtifactHash;
    }

    public void setBatchArtifactHash(String batchArtifactHash) {
        this.batchArtifactHash = batchArtifactHash;
    }

    public String getBatchCleanupCompletedFor() {
        return batchCleanupCompletedFor;
    }

    public void setBatchCleanupCompletedFor(String batchCleanupCompletedFor) {
        this.batchCleanupCompletedFor = batchCleanupCompletedFor;
    }

    public long getBatchExpectedResultCount() {
        return batchExpectedResultCount;
    }

    public void setBatchExpectedResultCount(long value) {
        this.batchExpectedResultCount = value;
    }

    public String getBatchExpectedResultFingerprint() {
        return batchExpectedResultFingerprint;
    }

    public void setBatchExpectedResultFingerprint(String value) {
        this.batchExpectedResultFingerprint = value;
    }

    public String getCompletedBatchGenerationId() {
        return completedBatchGenerationId;
    }

    public void setCompletedBatchGenerationId(String value) {
        this.completedBatchGenerationId = value;
    }

    public long getCompletedBatchRunCount() {
        return completedBatchRunCount;
    }

    public void setCompletedBatchRunCount(long value) {
        this.completedBatchRunCount = value;
    }

    public long getCompletedBatchLinkCount() {
        return completedBatchLinkCount;
    }

    public void setCompletedBatchLinkCount(long value) {
        this.completedBatchLinkCount = value;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getBaselineStage() {
        return baselineStage;
    }

    public void setBaselineStage(String baselineStage) {
        this.baselineStage = baselineStage;
    }

    public long getBaselineResultCount() {
        return baselineResultCount;
    }

    public void setBaselineResultCount(long baselineResultCount) {
        this.baselineResultCount = baselineResultCount;
    }

    public String getBaselineResultFingerprint() {
        return baselineResultFingerprint;
    }

    public void setBaselineResultFingerprint(String baselineResultFingerprint) {
        this.baselineResultFingerprint = baselineResultFingerprint;
    }

    public long getBaselineRunCount() {
        return baselineRunCount;
    }

    public void setBaselineRunCount(long baselineRunCount) {
        this.baselineRunCount = baselineRunCount;
    }

    public String getBaselineRunFingerprint() {
        return baselineRunFingerprint;
    }

    public void setBaselineRunFingerprint(String baselineRunFingerprint) {
        this.baselineRunFingerprint = baselineRunFingerprint;
    }

    public long getBaselineLinkCount() {
        return baselineLinkCount;
    }

    public void setBaselineLinkCount(long baselineLinkCount) {
        this.baselineLinkCount = baselineLinkCount;
    }

    public String getBaselineLinkFingerprint() {
        return baselineLinkFingerprint;
    }

    public void setBaselineLinkFingerprint(String baselineLinkFingerprint) {
        this.baselineLinkFingerprint = baselineLinkFingerprint;
    }
}
