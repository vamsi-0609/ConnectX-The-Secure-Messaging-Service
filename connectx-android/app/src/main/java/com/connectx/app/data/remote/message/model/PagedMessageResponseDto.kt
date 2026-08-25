package com.connectx.app.data.remote.message.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.message.dto.PagedMessageResponseDto exactly -- the
 * backend's custom cursor-based pagination structure for message history
 * (GET /conversations/{id}/messages), NOT a Spring Page/Slice and NOT
 * offset/limit. `nextCursor` is the message ID to pass as the `before` query
 * parameter for the next page; `hasMore` indicates whether one exists.
 */
@Serializable
data class PagedMessageResponseDto(
    val messages: List<MessageDto> = emptyList(),
    val hasMore: Boolean = false,
    val nextCursor: Long? = null,
    val limit: Int? = null
)
