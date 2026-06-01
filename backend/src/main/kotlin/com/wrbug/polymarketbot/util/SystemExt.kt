package com.wrbug.polymarketbot.util

/**
 * 获取环境变量的扩展函数
 * @param name 环境变量名称
 * @return 环境变量值，不存在时返回空字符串
 */
fun getEnv(name: String) = System.getenv(name).orEmpty()

