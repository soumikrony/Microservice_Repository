CREATE TABLE IF NOT EXISTS order_records (
    order_id VARCHAR(40) PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    total DOUBLE PRECISION NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at VARCHAR(60) NOT NULL,
    payload TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_order_records_user_id ON order_records(user_id);
