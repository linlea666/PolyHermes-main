package com.wrbug.polymarketbot.api

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * GitHub API 接口
 */
interface GitHubApi {
    /**
     * 获取 Issue 信息
     * @param owner 仓库所有者
     * @param repo 仓库名
     * @param issueNumber Issue 编号
     * @return Issue 信息响应
     */
    @GET("repos/{owner}/{repo}/issues/{issue_number}")
    suspend fun getIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("issue_number") issueNumber: Int
    ): Response<GitHubIssueResponse>
    
    /**
     * 获取 Issue 评论列表
     * @param owner 仓库所有者
     * @param repo 仓库名
     * @param issueNumber Issue 编号
     * @return 评论列表响应
     */
    @GET("repos/{owner}/{repo}/issues/{issue_number}/comments")
    suspend fun getIssueComments(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("issue_number") issueNumber: Int
    ): Response<List<GitHubCommentResponse>>
}

/**
 * GitHub Issue 响应
 */
data class GitHubIssueResponse(
    val id: Long,
    val number: Int,
    val assignees: List<GitHubUser>
)

/**
 * GitHub 评论响应
 */
data class GitHubCommentResponse(
    val id: Long,
    val body: String,
    val user: GitHubUser,
    val created_at: String,
    val updated_at: String,
    val issue_url: String? = null,  // Issue URL，格式：https://api.github.com/repos/owner/repo/issues/3703128976
    val reactions: GitHubReactions? = null  // Reactions 数据
)

/**
 * GitHub Reactions 数据
 */
data class GitHubReactions(
    val url: String,
    val total_count: Int,
    val `+1`: Int = 0,  // +1 数量
    val `-1`: Int = 0,  // -1 数量
    val laugh: Int = 0,  // 😄 数量
    val confused: Int = 0,  // 😕 数量
    val heart: Int = 0,  // ❤️ 数量
    val hooray: Int = 0,  // 🎉 数量
    val eyes: Int = 0,  // 👀 数量
    val rocket: Int = 0  // 🚀 数量
)

/**
 * GitHub 用户信息
 */
data class GitHubUser(
    val login: String,
    val id: Long,
    val avatar_url: String? = null
)

