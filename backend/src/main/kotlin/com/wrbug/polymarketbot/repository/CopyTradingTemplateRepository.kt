package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.CopyTradingTemplate
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 跟单模板 Repository
 */
@Repository
interface CopyTradingTemplateRepository : JpaRepository<CopyTradingTemplate, Long> {
    
    /**
     * 根据模板名称查找模板
     */
    fun findByTemplateName(templateName: String): CopyTradingTemplate?
    
    /**
     * 检查模板名称是否存在
     */
    fun existsByTemplateName(templateName: String): Boolean
    
    /**
     * 查找所有模板，按创建时间降序排序（最新的在前）
     */
    fun findAllByOrderByCreatedAtDesc(): List<CopyTradingTemplate>
}

