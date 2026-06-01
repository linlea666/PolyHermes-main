package com.wrbug.polymarketbot.repository

import com.wrbug.polymarketbot.entity.LeaderPool
import com.wrbug.polymarketbot.enums.LeaderPoolStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface LeaderPoolRepository : JpaRepository<LeaderPool, Long> {
    fun findByLeaderId(leaderId: Long): LeaderPool?

    fun existsByLeaderId(leaderId: Long): Boolean

    fun findByStatus(status: LeaderPoolStatus): List<LeaderPool>

    fun findAllByOrderByCreatedAtDesc(): List<LeaderPool>

    fun deleteByLeaderId(leaderId: Long)

    fun findByIdIn(ids: Collection<Long>): List<LeaderPool>
}
