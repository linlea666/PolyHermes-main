package com.wrbug.polymarketbot.enums

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * TradingMode 枚举的 JPA 转换器
 * 数据库存储为 TINYINT (0=LEGACY_SPREAD, 1=BARRIER_HOLD, 2=BRACKET_DYNAMIC)
 */
@Converter(autoApply = false)
class TradingModeConverter : AttributeConverter<TradingMode, Int> {

    override fun convertToDatabaseColumn(attribute: TradingMode?): Int {
        return attribute?.value ?: TradingMode.LEGACY_SPREAD.value
    }

    override fun convertToEntityAttribute(dbData: Int?): TradingMode {
        return TradingMode.fromValueOrDefault(dbData)
    }
}
