-- The reconciler only looks for payments still waiting on the ledger, which should be a handful at any time.
CREATE INDEX ix_payments_unsettled ON payments (updated_at) WHERE status IN ('PENDING', 'REFUND_PENDING');
