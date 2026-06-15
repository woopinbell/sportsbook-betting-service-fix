-- Durable checkpoints and rejection replay data for the recoverable placement saga.
ALTER TABLE bet
    ADD COLUMN request_fingerprint           VARCHAR(64),
    ADD COLUMN placement_phase               VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    ADD COLUMN rejection_detail              VARCHAR(1024),
    ADD COLUMN risk_reservation_expires_at   TIMESTAMP WITH TIME ZONE,
    ADD COLUMN risk_commit_observed          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN wallet_operation_id           UUID;

-- Existing terminal bets predate the saga checkpoints. They have already completed all placement
-- side effects, while old rejected/PENDING rows remain at the conservative CREATED phase. The
-- nullable fingerprint deliberately permits safe actor-only replay of pre-migration records.
UPDATE bet
SET placement_phase = 'RISK_COMMITTED',
    risk_commit_observed = TRUE
WHERE status IN ('ACCEPTED', 'SETTLED', 'CANCELLED', 'VOIDED');

ALTER TABLE bet
    ADD CONSTRAINT bet_placement_phase_valid CHECK (
        placement_phase IN ('CREATED', 'RISK_RESERVED', 'WALLET_CONFIRMED', 'RISK_COMMITTED')
    );

COMMENT ON COLUMN bet.request_fingerprint IS
    'SHA-256 of the canonical placement request; prevents Idempotency-Key payload reuse.';
COMMENT ON COLUMN bet.placement_phase IS
    'Durable CREATED -> RISK_RESERVED -> WALLET_CONFIRMED -> RISK_COMMITTED checkpoint.';
COMMENT ON COLUMN bet.rejection_detail IS
    'Original definitive rejection detail replayed through RFC 7807.';
