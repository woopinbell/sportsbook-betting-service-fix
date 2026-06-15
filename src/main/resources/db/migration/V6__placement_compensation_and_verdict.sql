-- Irreversible compensation checkpoints and one Idempotency-Key namespace for both bets and
-- preflight rejections.
ALTER TABLE bet
    ADD COLUMN compensation_action       VARCHAR(24),
    ADD COLUMN compensation_state        VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN compensation_operation_id UUID;

ALTER TABLE bet
    ADD CONSTRAINT bet_compensation_valid CHECK (
        (compensation_state = 'NONE'
            AND compensation_action IS NULL
            AND compensation_operation_id IS NULL)
        OR
        (compensation_state IN ('REQUIRED', 'IN_PROGRESS')
            AND compensation_action IN ('RISK_RELEASE', 'WALLET_REFUND')
            AND compensation_operation_id IS NULL)
        OR
        (compensation_state = 'COMPLETED'
            AND compensation_action = 'RISK_RELEASE'
            AND compensation_operation_id IS NULL)
        OR
        (compensation_state = 'COMPLETED'
            AND compensation_action = 'WALLET_REFUND'
            AND compensation_operation_id IS NOT NULL)
    );

COMMENT ON COLUMN bet.compensation_action IS
    'Irreversible rollback branch: release the risk reservation or refund the wallet debit.';
COMMENT ON COLUMN bet.compensation_state IS
    'Durable NONE -> REQUIRED -> IN_PROGRESS -> COMPLETED compensation checkpoint.';
COMMENT ON COLUMN bet.compensation_operation_id IS
    'Wallet operation proof for a completed WALLET_REFUND; null for risk release.';

CREATE TABLE placement_request (
    idempotency_key    VARCHAR(128)             PRIMARY KEY,
    user_id            UUID                     NOT NULL,
    request_fingerprint VARCHAR(64),
    outcome            VARCHAR(16)              NOT NULL,
    bet_id             UUID REFERENCES bet (bet_id) ON DELETE CASCADE,
    error_code         VARCHAR(64),
    error_detail       VARCHAR(1024),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_placement_request_bet UNIQUE (bet_id),
    CONSTRAINT placement_request_outcome_valid CHECK (
        (outcome = 'BET'
            AND bet_id IS NOT NULL
            AND error_code IS NULL
            AND error_detail IS NULL)
        OR
        (outcome = 'REJECTION'
            AND bet_id IS NULL
            AND error_code IS NOT NULL
            AND error_detail IS NOT NULL)
    )
);

-- Existing rows already own their Idempotency-Key. Rejected rows with a Bet aggregate remain BET
-- outcomes; their original error is replayed from bet.rejection_reason/rejection_detail.
INSERT INTO placement_request (
    idempotency_key,
    user_id,
    request_fingerprint,
    outcome,
    bet_id,
    created_at
)
SELECT
    idempotency_key,
    user_id,
    request_fingerprint,
    'BET',
    bet_id,
    created_at
FROM bet;

COMMENT ON TABLE placement_request IS
    'Authoritative placement Idempotency-Key outcome: a bet pointer or durable preflight rejection.';
COMMENT ON COLUMN placement_request.request_fingerprint IS
    'Canonical request SHA-256; nullable only for legacy rows migrated from bet.';
