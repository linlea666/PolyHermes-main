-- V59: 给退出轮询查询加索引
-- 根因：CryptoTailExitPoller 每 500ms 调 findAllOpenForExitPolling()，过滤
--   exit_status IN ('OPEN','PARTIAL_EXIT') AND token_id IS NOT NULL AND remaining_size IS NOT NULL
-- exit_status 列此前无索引，历史 trigger 行增多后退化为全表扫描，2 核机持续吃 CPU。
-- 绝大多数行 exit_status = 'NONE'/'FULLY_EXITED'，索引可让活跃持仓查询走索引范围。
CREATE INDEX idx_ctst_exit_status
    ON crypto_tail_strategy_trigger (exit_status, token_id);
