package com.connectx.app.data.remote.group.model

import kotlinx.serialization.Serializable

/**
 * Mirrors com.connectx.group.dto.GroupKeyRequestResultDto exactly. Response for
 * POST .../keys/request (throttled reconciliation broadcast) -- carries only the
 * server-authoritative key version and whether a broadcast was actually sent,
 * never key material.
 */
@Serializable
data class GroupKeyRequestResultDto(
    val keyVersion: Int,
    val broadcastSent: Boolean = false
)
