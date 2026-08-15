package com.connectx.message.dto;

import java.util.List;

public class PagedMessageResponseDto {

    private List<MessageDto> messages;
    private boolean hasMore;
    private Long nextCursor;
    private Integer limit;

    public PagedMessageResponseDto() {}

    public PagedMessageResponseDto(List<MessageDto> messages, boolean hasMore, Long nextCursor, Integer limit) {
        this.messages = messages;
        this.hasMore = hasMore;
        this.nextCursor = nextCursor;
        this.limit = limit;
    }

    public List<MessageDto> getMessages() {
        return messages;
    }

    public void setMessages(List<MessageDto> messages) {
        this.messages = messages;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }

    public Long getNextCursor() {
        return nextCursor;
    }

    public void setNextCursor(Long nextCursor) {
        this.nextCursor = nextCursor;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
