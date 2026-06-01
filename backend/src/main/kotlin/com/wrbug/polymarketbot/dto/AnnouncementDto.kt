package com.wrbug.polymarketbot.dto

/**
 * 公告列表请求
 */
data class AnnouncementListRequest(
    val forceRefresh: Boolean = false  // 是否强制刷新缓存
)

/**
 * 公告详情请求
 */
data class AnnouncementDetailRequest(
    val id: Long? = null,  // 评论ID，如果为空则返回最新一条
    val forceRefresh: Boolean = false  // 是否强制刷新缓存
)

/**
 * Reactions 信息
 */
data class ReactionsDto(
    val plusOne: Int = 0,  // 👍 +1 数量
    val minusOne: Int = 0,  // 👎 -1 数量
    val laugh: Int = 0,  // 😄 数量
    val confused: Int = 0,  // 😕 数量
    val heart: Int = 0,  // ❤️ 数量
    val hooray: Int = 0,  // 🎉 数量
    val eyes: Int = 0,  // 👀 数量
    val rocket: Int = 0,  // 🚀 数量
    val total: Int = 0  // 总数量
)

/**
 * 公告信息响应
 */
data class AnnouncementDto(
    val id: Long,  // GitHub 评论 ID
    val title: String,  // 标题（从评论第一行提取，已移除 Markdown 格式）
    val body: String,  // Markdown 内容（完整内容）
    val author: String,  // 作者用户名
    val authorAvatarUrl: String?,  // 作者头像 URL
    val createdAt: Long,  // 创建时间（时间戳，毫秒）
    val updatedAt: Long,  // 更新时间（时间戳，毫秒）
    val reactions: ReactionsDto? = null  // Reactions 数据
)

/**
 * 公告列表响应
 */
data class AnnouncementListResponse(
    val list: List<AnnouncementDto>,
    val hasMore: Boolean,  // 是否还有更多（总数 > 10）
    val total: Int  // 总数
)

