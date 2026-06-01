package com.wrbug.polymarketbot.service.copytrading.leaderpool

class LeaderPoolNotFoundException(message: String = "Leader 池项不存在") : RuntimeException(message)

class LeaderPoolAlreadyExistsException(message: String = "Leader 已在池子中") : RuntimeException(message)

class LeaderPoolDuplicateTrialConfigException(message: String = "该账户已存在此 Leader 的跟单配置") : RuntimeException(message)

class LeaderPoolConfirmRequiredException(message: String = "立即启用试跟配置需要显式确认") : RuntimeException(message)

class LeaderPoolResearchCandidateNotReadyException(
    message: String = "研究候选尚未进入试跟建议状态"
) : RuntimeException(message)
