package com.aitaskcenter.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public class DeleteByIdRequest {
    @JsonAlias({"ID", "id"})
    private Long id;

    // 方法：getId
    public Long getId() {
        return id;
    }

    // 方法：setId
    public void setId(Long id) {
        this.id = id;
    }
}
