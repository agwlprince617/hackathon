\connect student

BEGIN;

ALTER TABLE items
    ADD COLUMN IF NOT EXISTS shadow_available INT,
    ADD COLUMN IF NOT EXISTS last_synced_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE items
SET shadow_available = available
WHERE shadow_available IS NULL;

ALTER TABLE items
    ALTER COLUMN shadow_available SET NOT NULL;

INSERT INTO items (id, name, available, shadow_available, last_synced_at)
VALUES
    ('hype-001', 'Hype Drop 001', 50, 50, now()),
    ('hype-002', 'Court Classic', 20, 20, now()),
    ('hype-003', 'Night Runner', 10, 10, now()),
    ('hype-004', 'Studio Pack', 5, 5, now()),
    ('hype-005', 'Daily Tee', 100, 100, now())
ON CONFLICT (id) DO NOTHING;

ALTER TABLE reservations
    ADD COLUMN IF NOT EXISTS idempotency_key TEXT,
    ADD COLUMN IF NOT EXISTS request_hash TEXT,
    ADD COLUMN IF NOT EXISTS mode TEXT NOT NULL DEFAULT 'live',
    ADD COLUMN IF NOT EXISTS reason TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE UNIQUE INDEX IF NOT EXISTS reservations_user_idem_idx
    ON reservations (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS reservations_pending_replay_idx
    ON reservations (created_at, id)
    WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS reservations_user_created_idx
    ON reservations (user_id, created_at);

CREATE TABLE IF NOT EXISTS reservation_events (
    id BIGSERIAL PRIMARY KEY,
    reservation_id TEXT NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    old_status TEXT,
    new_status TEXT NOT NULL,
    reason TEXT,
    authority_status_code INT,
    authority_response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (old_status IS NULL OR old_status IN ('confirmed', 'pending', 'rejected', 'reversed')),
    CHECK (new_status IN ('confirmed', 'pending', 'rejected', 'reversed')),
    CHECK (authority_status_code IS NULL OR authority_status_code BETWEEN 100 AND 599)
);

CREATE INDEX IF NOT EXISTS reservation_events_reservation_created_idx
    ON reservation_events (reservation_id, created_at, id);

GRANT SELECT, INSERT ON TABLE reservation_events TO launchday;
GRANT USAGE, SELECT ON SEQUENCE reservation_events_id_seq TO launchday;

INSERT INTO reservation_events (reservation_id, old_status, new_status, reason, created_at)
SELECT r.id, NULL, r.status, r.reason, r.created_at
FROM reservations r
WHERE NOT EXISTS (
    SELECT 1 FROM reservation_events event WHERE event.reservation_id = r.id
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'items_inventory_nonnegative'
          AND conrelid = 'items'::regclass
    ) THEN
        ALTER TABLE items ADD CONSTRAINT items_inventory_nonnegative
            CHECK (available >= 0 AND shadow_available >= 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'reservations_qty_positive'
          AND conrelid = 'reservations'::regclass
    ) THEN
        ALTER TABLE reservations ADD CONSTRAINT reservations_qty_positive
            CHECK (qty > 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'reservations_status_valid'
          AND conrelid = 'reservations'::regclass
    ) THEN
        ALTER TABLE reservations ADD CONSTRAINT reservations_status_valid
            CHECK (status IN ('confirmed', 'pending', 'rejected', 'reversed'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'reservations_mode_valid'
          AND conrelid = 'reservations'::regclass
    ) THEN
        ALTER TABLE reservations ADD CONSTRAINT reservations_mode_valid
            CHECK (mode IN ('live', 'standin'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'reservations_idempotency_complete'
          AND conrelid = 'reservations'::regclass
    ) THEN
        ALTER TABLE reservations ADD CONSTRAINT reservations_idempotency_complete
            CHECK (
                (idempotency_key IS NULL AND request_hash IS NULL)
                OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL)
            );
    END IF;
END
$$;

COMMIT;