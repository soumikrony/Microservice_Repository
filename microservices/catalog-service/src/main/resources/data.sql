INSERT INTO catalog_products (id, name, price, category, active)
VALUES (1, 'Laptop', 850.0, 'Computers', TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO catalog_products (id, name, price, category, active)
VALUES (2, 'Keyboard', 40.0, 'Accessories', TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO catalog_products (id, name, price, category, active)
VALUES (3, 'Mouse', 25.0, 'Accessories', TRUE)
ON CONFLICT (id) DO NOTHING;
