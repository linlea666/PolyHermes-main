package com.wrbug.polymarketbot.util

/**
 * 分类验证工具类
 * 用于验证分类参数是否符合项目要求（仅支持 sports 和 crypto）
 */
object CategoryValidator {
    
    /**
     * 支持的分类列表
     */
    private val SUPPORTED_CATEGORIES = setOf("sports", "crypto")
    
    /**
     * 分类名称映射（将 Polymarket API 返回的分类名称映射到标准分类）
     */
    private val CATEGORY_MAPPING = mapOf(
        "sports" to "sports",
        "crypto" to "crypto",
        "cryptocurrency" to "crypto",
        "cryptocurrencies" to "crypto"
    )
    
    /**
     * 验证分类是否有效（支持精确匹配和关键字匹配）
     * @param category 分类名称
     * @return 是否有效
     */
    fun isValid(category: String?): Boolean {
        if (category == null) {
            return false
        }
        
        val categoryLower = category.lowercase()
        
        // 精确匹配
        if (categoryLower in SUPPORTED_CATEGORIES) {
            return true
        }
        
        // 映射匹配
        if (categoryLower in CATEGORY_MAPPING.keys) {
            return true
        }
        
        // 关键字匹配
        if (categoryLower.contains("sport")) {
            return true
        }
        if (categoryLower.contains("crypto")) {
            return true
        }
        
        return false
    }
    
    /**
     * 标准化分类名称
     * @param category 原始分类名称
     * @return 标准化后的分类名称（sports 或 crypto）
     */
    fun normalizeCategory(category: String?): String? {
        if (category == null) {
            return null
        }
        
        val categoryLower = category.lowercase()
        
        // 映射匹配
        CATEGORY_MAPPING[categoryLower]?.let {
            return it
        }
        
        // 关键字匹配
        if (categoryLower.contains("sport")) {
            return "sports"
        }
        if (categoryLower.contains("crypto")) {
            return "crypto"
        }
        
        return null
    }
    
    /**
     * 验证分类，如果无效则抛出异常
     * @param category 分类名称
     * @throws IllegalArgumentException 如果分类无效
     */
    fun validate(category: String?) {
        if (!isValid(category)) {
            throw IllegalArgumentException("不支持的分类: $category，仅支持: ${SUPPORTED_CATEGORIES.joinToString(", ")}")
        }
    }
    
    /**
     * 获取所有支持的分类
     * @return 支持的分类列表
     */
    fun getSupportedCategories(): Set<String> {
        return SUPPORTED_CATEGORIES
    }
}

