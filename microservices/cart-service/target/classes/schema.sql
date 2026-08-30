CREATE TABLE IF NOT EXISTS cart_items (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    product_id INTEGER NOT NULL,
    name VARCHAR(120) NOT NULL,
    quantity INTEGER NOT NULL,
    price DOUBLE PRECISION NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_cart_items_user_id ON cart_items(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_cart_items_user_product ON cart_items(user_id, product_id);
