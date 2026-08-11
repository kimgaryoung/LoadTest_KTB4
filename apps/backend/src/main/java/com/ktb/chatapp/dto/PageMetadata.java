package com.ktb.chatapp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageMetadata {
    private Long total;
    private Integer page;
    private Integer pageSize;
    private Long totalPages;
    private boolean hasMore;
    private int currentCount;
    private String nextCursor;
    private SortInfo sort;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SortInfo {
        private String field;
        private String order;
    }
}
