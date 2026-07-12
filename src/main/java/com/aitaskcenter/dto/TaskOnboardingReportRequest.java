package com.aitaskcenter.dto;

import java.util.ArrayList;
import java.util.List;

public class TaskOnboardingReportRequest {
    private String stage;
    private String token;
    private String artifact;
    private String artifactHash;
    private List<Long> entityIds = new ArrayList<>();

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getArtifact() {
        return artifact;
    }

    public void setArtifact(String artifact) {
        this.artifact = artifact;
    }

    public String getArtifactHash() {
        return artifactHash;
    }

    public void setArtifactHash(String artifactHash) {
        this.artifactHash = artifactHash;
    }

    public List<Long> getEntityIds() {
        return entityIds;
    }

    public void setEntityIds(List<Long> entityIds) {
        this.entityIds = entityIds == null ? new ArrayList<>() : new ArrayList<>(entityIds);
    }
}
