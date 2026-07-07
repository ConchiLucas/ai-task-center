package com.aitaskcenter.dto;

import java.util.List;

public class BatchDeleteRequest {
    private List<Long> ids;

    // 方法：getIds
    public List<Long> getIds() {
        return ids;
    }

    // 方法：setIds
    public void setIds(List<Long> ids) {
        this.ids = ids;
    }
}
