package com.zucham.qbsmarter.domain.user

/** Single local user record. Multi-user support can be layered on later. */
data class UserProfile(
    val id: String,
    val displayName: String?,
    val createdAt: Long,
)
