INSERT INTO inventory_items (product_id, available)
VALUES (1, 30)
ON CONFLICT (product_id) DO NOTHING;

INSERT INTO inventory_items (product_id, available)
VALUES (2, 80)
ON CONFLICT (product_id) DO NOTHING;

INSERT INTO inventory_items (product_id, available)
VALUES (3, 65)
ON CONFLICT (product_id) DO NOTHING;
