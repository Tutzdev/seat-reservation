-- A charge that lands after its booking ended is given back. REFUND_PENDING is written in the same
-- transaction that finds the booking over, so the refund is not forgotten if the process dies before
-- the ledger is called.
ALTER TABLE payments
    ADD COLUMN refund_transfer_id UUID,
    DROP CONSTRAINT payments_status_check,
    DROP CONSTRAINT ck_payments_outcome,
    ADD CONSTRAINT ck_payments_status
        CHECK (status IN ('PENDING', 'DECLINED', 'CAPTURED', 'REFUND_PENDING', 'REFUNDED')),
    ADD CONSTRAINT ck_payments_outcome CHECK (
        (status = 'PENDING' AND transfer_id IS NULL AND decline_reason IS NULL AND refund_transfer_id IS NULL)
        OR (status = 'DECLINED' AND transfer_id IS NULL AND decline_reason IS NOT NULL AND refund_transfer_id IS NULL)
        OR (status IN ('CAPTURED', 'REFUND_PENDING')
            AND transfer_id IS NOT NULL AND decline_reason IS NULL AND refund_transfer_id IS NULL)
        OR (status = 'REFUNDED'
            AND transfer_id IS NOT NULL AND decline_reason IS NULL AND refund_transfer_id IS NOT NULL));
