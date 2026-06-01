-- Leader Research Agent performance fixture and query plan helper.
-- Intended for a disposable local/test database after migrations have run.
-- It seeds at least 100 candidates, 10k activity events, and 10k paper trades,
-- then runs EXPLAIN on the hot list/detail paths.

SET @now_ms = UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000;

WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 10000
)
INSERT IGNORE INTO leader_research_candidate (
    normalized_wallet,
    research_state,
    source,
    score,
    score_version,
    agent_owned,
    provenance,
    first_seen_at,
    last_source_seen_at,
    last_transition_at,
    created_at,
    updated_at
)
SELECT
    CONCAT('0x', LPAD(HEX(n), 40, '0')),
    CASE WHEN n % 5 = 0 THEN 'TRIAL_READY' WHEN n % 3 = 0 THEN 'PAPER' ELSE 'CANDIDATE' END,
    'PERF_FIXTURE',
    60 + (n % 40),
    'research-copyability-v1',
    1,
    'AGENT_CREATED',
    @now_ms - 3600000,
    @now_ms - 60000,
    @now_ms - 60000,
    @now_ms,
    @now_ms
FROM seq
WHERE n <= 100;

WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 10000
)
INSERT IGNORE INTO leader_activity_event (
    source,
    source_event_id,
    stable_event_key,
    normalized_wallet,
    market_id,
    asset,
    side,
    price,
    size,
    amount,
    event_time,
    raw_payload_hash,
    payload_summary,
    usable_for_discovery,
    usable_for_paper,
    paper_processing_status,
    processing_attempts,
    created_at,
    updated_at
)
SELECT
    'PERF_FIXTURE',
    CONCAT('perf-event-', n),
    CONCAT('perf-event-', n),
    CONCAT('0x', LPAD(HEX(1 + (n % 100)), 40, '0')),
    CONCAT('condition-', n % 200),
    CONCAT('asset-', n % 200),
    IF(n % 2 = 0, 'BUY', 'SELL'),
    0.45,
    10,
    4.5,
    @now_ms - (n * 1000),
    SHA2(CONCAT('perf-event-', n), 256),
    CONCAT('perf event ', n),
    1,
    1,
    'NEW',
    0,
    @now_ms,
    @now_ms
FROM seq;

WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 10000
)
INSERT IGNORE INTO leader_paper_session (
    id,
    candidate_id,
    status,
    started_at,
    trade_count,
    filtered_count,
    open_exposure,
    copyable_pnl,
    max_drawdown,
    unknown_valuation_exposure,
    confirmed_zero_exposure,
    filtered_ratio,
    created_at,
    updated_at
)
SELECT
    n,
    1 + (n % 100),
    'ACTIVE',
    @now_ms - 604800000,
    100,
    5,
    100,
    10,
    -3,
    5,
    0,
    0.0476,
    @now_ms,
    @now_ms
FROM seq
WHERE n <= 100;

WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 10000
)
INSERT IGNORE INTO leader_paper_trade (
    session_id,
    candidate_id,
    leader_trade_id,
    market_id,
    side,
    leader_price,
    leader_size,
    simulated_price,
    simulated_size,
    simulated_amount,
    fill_assumption,
    quote_confidence,
    quote_source,
    quote_timestamp,
    filter_result,
    valuation_status,
    event_time,
    created_at
)
SELECT
    1 + (n % 100),
    1 + (n % 100),
    CONCAT('perf-trade-', n),
    CONCAT('condition-', n % 200),
    IF(n % 2 = 0, 'BUY', 'SELL'),
    0.45,
    10,
    0.45,
    2,
    1,
    'LEADER_PRICE',
    'MEDIUM',
    'perf',
    @now_ms,
    'PASSED',
    IF(n % 20 = 0, 'UNKNOWN', 'AVAILABLE'),
    @now_ms - (n * 1000),
    @now_ms
FROM seq;

EXPLAIN SELECT * FROM leader_research_candidate WHERE research_state IN ('PAPER', 'TRIAL_READY') ORDER BY updated_at DESC LIMIT 50;
EXPLAIN SELECT * FROM leader_activity_event WHERE paper_processing_status IN ('NEW', 'RETRYABLE') AND usable_for_paper = 1 ORDER BY event_time ASC LIMIT 200;
EXPLAIN SELECT * FROM leader_paper_trade WHERE session_id = 1 ORDER BY event_time DESC LIMIT 100;
EXPLAIN SELECT * FROM leader_research_event ORDER BY created_at DESC LIMIT 100;
