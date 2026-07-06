package com.aitaskcenter.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public class DeleteByIdRequest {
    @JsonAlias({"ID", "id"})
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
