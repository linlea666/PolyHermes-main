package com.wrbug.polymarketbot.util

import java.math.BigDecimal
import java.math.BigInteger

/**
 * 非法的BigDecimal常量，用于表示转换失败的情况
 */
val IllegalBigDecimal = BigDecimal("0")

/**
 * 非法的BigInteger常量，用于表示转换失败的情况
 */
val IllegalBigInteger = BigInteger("0")

/**
 * 安全转换为BigDecimal的扩展函数
 * 将任意类型安全地转换为BigDecimal，转换失败时返回IllegalBigDecimal
 * @return 转换后的BigDecimal值，失败时返回IllegalBigDecimal
 */
fun Any?.toSafeBigDecimal(): BigDecimal {
    return try {
        if (this is BigDecimal) {
            return this
        }
        if (this is BigInteger) {
            return this.toBigDecimal()
        }
        if (this is Number) {
            return BigDecimal.valueOf(this.toDouble())
        }
        BigDecimal(this.toString())
    } catch (t: Throwable) {
        IllegalBigDecimal
    }
}

/**
 * 安全转换为BigInteger的扩展函数
 * 将字符串安全地转换为BigInteger，转换失败时返回IllegalBigInteger
 * @return 转换后的BigInteger值，失败时返回IllegalBigInteger
 */
fun String?.toSafeBigInteger(): BigInteger {
    return try {
        BigInteger(this.orEmpty())
    } catch (t: Throwable) {
        IllegalBigInteger
    }
}

/**
 * 安全转换为Long的扩展函数
 * 将字符串安全地转换为Long，转换失败时返回0
 * @return 转换后的Long值，失败时返回0
 */
fun String?.toSafeLong(): Long {
    return try {
        this?.toLong() ?: 0
    } catch (t: Throwable) {
        0
    }
}

/**
 * 安全转换为Int的扩展函数
 * 将任意类型安全地转换为Int，转换失败时返回0
 * @return 转换后的Int值，失败时返回0
 */
fun Any?.toSafeInt(): Int {
    return try {
        if (this is Number) {
            this.toInt()
        } else {
            this?.toString().toSafeBigDecimal().toInt()
        }
    } catch (t: Throwable) {
        0
    }
}

/**
 * 安全转换为Double的扩展函数
 * 将任意类型安全地转换为Double，转换失败时返回0.0
 * @return 转换后的Double值，失败时返回0.0
 */
fun Any?.toSafeDouble(): Double {
    return try {
        when (this) {
            is Number -> this.toDouble()
            is Boolean -> if (this) 1.0 else 0.0
            else -> this?.toString().toSafeBigDecimal().toDouble()
        }
    } catch (t: Throwable) {
        0.0
    }
}

