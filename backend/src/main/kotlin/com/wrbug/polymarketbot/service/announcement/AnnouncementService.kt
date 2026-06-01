package com.wrbug.polymarketbot.service.announcement

import com.wrbug.polymarketbot.api.GitHubApi
import com.wrbug.polymarketbot.api.GitHubCommentResponse
import com.wrbug.polymarketbot.dto.AnnouncementDto
import com.wrbug.polymarketbot.dto.AnnouncementListResponse
import com.wrbug.polymarketbot.util.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 公告服务
 * 从 GitHub Issues API 获取公告信息
 */
@Service
class AnnouncementService(
    private val retrofitFactory: RetrofitFactory,
    @Value("\${github.repo.owner:WrBug}")
    private val repoOwner: String,
    @Value("\${github.repo.name:PolyHermes}")
    private val repoName: String,
    @Value("\${github.announcement.issue.number:1}")
    private val issueNumber: Int
) {

    private val logger = LoggerFactory.getLogger(AnnouncementService::class.java)

    // GitHub API 客户端（懒加载）
    private val githubApi: GitHubApi by lazy {
        retrofitFactory.createGitHubApi()
    }

    // 需要排除的 Issue ID（从 issue_url 中提取）
    private val excludedIssueId = "3703128976"

    // 缓存数据（1分钟有效期）
    private data class CachedData<T>(
        val data: T,
        val timestamp: Long
    )

    private var cachedList: CachedData<AnnouncementListResponse>? = null
    private var cachedAssignees: CachedData<List<String>>? = null
    private var cachedComments: CachedData<List<com.wrbug.polymarketbot.api.GitHubCommentResponse>>? = null

    // 缓存有效期：10分钟（毫秒）
    private val cacheExpiryTime = 10 * 60 * 1000L

    /**
     * 检查缓存是否有效
     */
    private fun <T> isCacheValid(cached: CachedData<T>?): Boolean {
        if (cached == null) return false
        val now = System.currentTimeMillis()
        return (now - cached.timestamp) < cacheExpiryTime
    }

    /**
     * 检查是否被限流
     */
    private fun isRateLimited(response: retrofit2.Response<*>): Boolean {
        // HTTP 403 通常表示限流
        if (response.code() == 403) {
            return true
        }
        // 检查响应头中的限流信息
        val remaining = response.headers()["X-RateLimit-Remaining"]
        return remaining == "0"
    }

    /**
     * 获取 Issue 的 assignees 列表（通过 API 获取，带缓存）
     * @return Pair<assignees列表, 是否使用了缓存>
     */
    private suspend fun getAssignees(forceRefresh: Boolean = false): Pair<List<String>, Boolean> {
        // 检查缓存
        if (!forceRefresh && isCacheValid(cachedAssignees)) {
            logger.debug("使用缓存的 assignees")
            return Pair(cachedAssignees!!.data, true)
        }

        return try {
            val response = githubApi.getIssue(
                owner = repoOwner,
                repo = repoName,
                issueNumber = issueNumber
            )

            // 如果被限流，使用缓存数据，不更新缓存
            if (isRateLimited(response)) {
                logger.warn("GitHub API 被限流，使用缓存的 assignees（不更新缓存）")
                if (cachedAssignees != null) {
                    return Pair(cachedAssignees!!.data, true)  // 返回缓存数据，标记为使用了缓存
                }
                // 如果没有缓存，使用默认值
                return Pair(listOf("WrBug"), false)
            }

            val assignees = if (response.isSuccessful && response.body() != null) {
                response.body()!!.assignees.map { it.login }
            } else {
                logger.warn("获取 Issue assignees 失败，使用默认值: code=${response.code()}")
                listOf("WrBug")  // 默认值
            }

            // 更新缓存
            cachedAssignees = CachedData(assignees, System.currentTimeMillis())
            Pair(assignees, false)  // 返回新数据，标记为未使用缓存
        } catch (e: Exception) {
            logger.error("获取 Issue assignees 异常: ${e.message}", e)
            // 如果缓存存在，使用缓存
            if (cachedAssignees != null) {
                logger.warn("使用缓存的 assignees（API 调用失败）")
                return Pair(cachedAssignees!!.data, true)  // 返回缓存数据，标记为使用了缓存
            }
            Pair(listOf("WrBug"), false)  // 默认值
        }
    }

    /**
     * 获取 Issue 评论列表（带缓存）
     * @return Pair<评论列表, 是否使用了缓存>
     */
    private suspend fun getIssueComments(forceRefresh: Boolean = false): Pair<List<GitHubCommentResponse>, Boolean> {
        // 检查缓存
        if (!forceRefresh && isCacheValid(cachedComments)) {
            logger.debug("使用缓存的评论列表")
            return Pair(cachedComments!!.data, true)
        }

        val response = githubApi.getIssueComments(
            owner = repoOwner,
            repo = repoName,
            issueNumber = issueNumber
        )

        // 如果被限流，使用缓存数据，不更新缓存
        if (isRateLimited(response)) {
            logger.warn("GitHub API 被限流，使用缓存的评论列表（不更新缓存）")
            if (cachedComments != null) {
                return Pair(cachedComments!!.data, true)  // 返回缓存数据，标记为使用了缓存
            }
            // 如果没有缓存，抛出异常
            throw Exception("获取公告列表失败: GitHub API 被限流，且无缓存数据")
        }

        if (!response.isSuccessful || response.body() == null) {
            logger.error("获取 GitHub Issue 评论失败: code=${response.code()}, message=${response.message()}")
            // 如果缓存存在，使用缓存
            if (cachedComments != null) {
                logger.warn("使用缓存的评论列表（API 调用失败）")
                return Pair(cachedComments!!.data, true)  // 返回缓存数据，标记为使用了缓存
            }
            throw Exception("获取公告列表失败: HTTP ${response.code()}")
        }

        val comments = response.body()!!

        // 更新缓存
        cachedComments = CachedData(comments, System.currentTimeMillis())
        return Pair(comments, false)  // 返回新数据，标记为未使用缓存
    }

    /**
     * 获取公告列表（最近10条）
     * @param forceRefresh 是否强制刷新缓存
     */
    suspend fun getAnnouncementList(forceRefresh: Boolean = false): Result<AnnouncementListResponse> {
        // 检查缓存
        if (!forceRefresh && isCacheValid(cachedList)) {
            logger.debug("使用缓存的公告列表")
            return Result.success(cachedList!!.data)
        }

        return try {
            // 强制刷新时，先尝试获取新数据
            val (assigneeList, assigneesFromCache) = getAssignees(forceRefresh)
            val (comments, commentsFromCache) = getIssueComments(forceRefresh)

            // 如果强制刷新时使用了缓存（被限流），直接返回缓存数据，不更新缓存
            if (forceRefresh && (assigneesFromCache || commentsFromCache)) {
                logger.warn("强制刷新时被限流，返回缓存的公告列表（不更新缓存）")
                if (cachedList != null) {
                    return Result.success(cachedList!!.data)
                }
            }

            // 筛选条件：
            // 1. assignees 发布的评论
            // 2. 排除 issueNumber 为 3703128976 的评论（从 issue_url 中提取）
            val filteredComments = comments.filter { comment ->
                // 检查是否为 assignee
                val isAssignee = assigneeList.contains(comment.user.login)

                // 检查是否应该排除（从 issue_url 中提取 issue ID）
                val shouldExclude = comment.issue_url?.let { issueUrl ->
                    // issue_url 格式：https://api.github.com/repos/owner/repo/issues/3703128976
                    // 提取最后的数字
                    val issueId = issueUrl.split("/").lastOrNull()
                    issueId == excludedIssueId
                } ?: false

                isAssignee && !shouldExclude
            }
                .sortedByDescending { comment ->
                    parseGitHubTime(comment.created_at)
                }

            val total = filteredComments.size
            val hasMore = total > 10

            // 取前10条
            val latest10 = filteredComments.take(10).map { comment ->
                toAnnouncementDto(comment)
            }

            val result = AnnouncementListResponse(
                list = latest10,
                hasMore = hasMore,
                total = total
            )

            // 只有在数据正常返回时才更新缓存（不是从缓存获取的）
            if (!assigneesFromCache && !commentsFromCache) {
                cachedList = CachedData(result, System.currentTimeMillis())
            }

            Result.success(result)
        } catch (e: Exception) {
            logger.error("获取公告列表异常: ${e.message}", e)
            // 如果缓存存在，返回缓存
            if (cachedList != null) {
                logger.warn("使用缓存的公告列表（API 调用失败）")
                return Result.success(cachedList!!.data)
            }
            Result.failure(e)
        }
    }

    /**
     * 获取公告详情
     * @param id 评论ID，如果为 null 则返回最新一条
     * @param forceRefresh 是否强制刷新缓存
     */
    suspend fun getAnnouncementDetail(id: Long?, forceRefresh: Boolean = false): Result<AnnouncementDto> {
        return try {
            // 获取 assignees
            val (assigneeList, _) = getAssignees(forceRefresh)

            // 获取评论列表
            val (comments, _) = getIssueComments(forceRefresh)

            // 筛选条件：
            // 1. assignees 发布的评论
            // 2. 排除 issueNumber 为 3703128976 的评论（从 issue_url 中提取）
            val filteredComments = comments.filter { comment ->
                    // 检查是否为 assignee
                    val isAssignee = assigneeList.contains(comment.user.login)

                    // 检查是否应该排除（从 issue_url 中提取 issue ID）
                    val shouldExclude = comment.issue_url?.let { issueUrl ->
                        // issue_url 格式：https://api.github.com/repos/owner/repo/issues/3703128976
                        // 提取最后的数字
                        val issueId = issueUrl.split("/").lastOrNull()
                        issueId == excludedIssueId
                    } ?: false

                    isAssignee && !shouldExclude
                }
                .sortedByDescending { comment ->
                    parseGitHubTime(comment.created_at)
                }

            val targetComment = if (id != null) {
                filteredComments.find { it.id == id }
            } else {
                filteredComments.firstOrNull()
            }

            if (targetComment == null) {
                return Result.failure(IllegalArgumentException("公告不存在"))
            }

            Result.success(toAnnouncementDto(targetComment))
        } catch (e: Exception) {
            logger.error("获取公告详情异常: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 将 GitHub 评论转换为 AnnouncementDto
     */
    private fun toAnnouncementDto(comment: GitHubCommentResponse): AnnouncementDto {
        // 提取标题（第一行，移除 Markdown 格式）
        val title = extractTitle(comment.body)

        // 转换 reactions 数据
        val reactions = comment.reactions?.let { r ->
            com.wrbug.polymarketbot.dto.ReactionsDto(
                plusOne = r.`+1`,
                minusOne = r.`-1`,
                laugh = r.laugh,
                confused = r.confused,
                heart = r.heart,
                hooray = r.hooray,
                eyes = r.eyes,
                rocket = r.rocket,
                total = r.total_count
            )
        }

        return AnnouncementDto(
            id = comment.id,
            title = title,
            body = comment.body,
            author = comment.user.login,
            authorAvatarUrl = comment.user.avatar_url,
            createdAt = parseGitHubTime(comment.created_at),
            updatedAt = parseGitHubTime(comment.updated_at),
            reactions = reactions
        )
    }

    /**
     * 从评论内容中提取标题（第一行，移除 Markdown 格式）
     * 支持的 Markdown 格式：
     * - # 标题
     * - ## 标题
     * - ### 标题
     * - **粗体**
     * - *斜体*
     * - `代码`
     * - [链接](url)
     */
    private fun extractTitle(body: String): String {
        if (body.isBlank()) {
            return ""
        }

        // 获取第一行
        val firstLine = body.lines().firstOrNull()?.trim() ?: ""
        if (firstLine.isBlank()) {
            return ""
        }

        // 移除 Markdown 格式
        var title = firstLine

        // 移除标题标记（# ## ### 等）
        title = title.replace(Regex("^#{1,6}\\s+"), "")

        // 移除粗体标记（**text** 或 __text__）
        title = title.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        title = title.replace(Regex("__([^_]+)__"), "$1")

        // 移除斜体标记（*text* 或 _text_）
        title = title.replace(Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)"), "$1")
        title = title.replace(Regex("(?<!_)_([^_]+)_(?!_)"), "$1")

        // 移除代码标记（`code`）
        title = title.replace(Regex("`([^`]+)`"), "$1")

        // 移除链接标记（[text](url)）
        title = title.replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")

        // 移除图片标记（![alt](url)）
        title = title.replace(Regex("!\\[([^\\]]*)\\]\\([^\\)]+\\)"), "$1")

        // 移除删除线标记（~~text~~）
        title = title.replace(Regex("~~([^~]+)~~"), "$1")

        // 移除引用标记（> text）
        title = title.replace(Regex("^>\\s+"), "")

        // 移除列表标记（- * + 1. 等）
        title = title.replace(Regex("^[-*+]\\s+"), "")
        title = title.replace(Regex("^\\d+\\.\\s+"), "")

        return title.trim()
    }

    /**
     * 解析 GitHub 时间格式（ISO 8601）为时间戳（毫秒）
     * GitHub API 返回的时间格式：2025-12-07T14:30:00Z
     */
    private fun parseGitHubTime(timeStr: String): Long {
        return try {
            Instant.parse(timeStr).toEpochMilli()
        } catch (e: Exception) {
            logger.warn("解析 GitHub 时间失败: $timeStr", e)
            System.currentTimeMillis()
        }
    }
}

