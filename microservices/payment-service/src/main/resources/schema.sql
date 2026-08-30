CREATE TABLE IF NOT EXISTS payment_records (
    transaction_id VARCHAR(60) PRIMARY KEY,
    order_id VARCHAR(40) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    amount DOUBLE PRECISION NOT NULL,
    payment_method VARCHAR(40) NOT NULL,
    approved BOOLEAN NOT NULL,
    created_at VARCHAR(60) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_payment_records_order_id ON payment_records(order_id);
