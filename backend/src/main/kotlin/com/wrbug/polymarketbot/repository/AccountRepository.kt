package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.Account
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * 账户 Repository
 */
@Repository
interface AccountRepository : JpaRepository<Account, Long> {
    
    /**
     * 根据钱包地址查找账户
     */
    fun findByWalletAddress(walletAddress: String): Account?
    
    /**
     * 查找默认账户
     */
    fun findByIsDefaultTrue(): Account?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Account?
    
    /**
     * 查找所有账户，按创建时间排序
     */
    fun findAllByOrderByCreatedAtAsc(): List<Account>
    
    /**
     * 检查钱包地址是否存在
     */
    fun existsByWalletAddress(walletAddress: String): Boolean

    /**
     * 检查代理地址是否存在
     */
    fun existsByProxyAddress(proxyAddress: String): Boolean
}
