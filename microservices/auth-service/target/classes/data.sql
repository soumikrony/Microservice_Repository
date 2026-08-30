INSERT INTO auth_users (username, password, roles, enabled)
VALUES ('alice', '{noop}alice123', 'USER', TRUE)
ON CONFLICT (username) DO NOTHING;

INSERT INTO auth_users (username, password, roles, enabled)
VALUES ('admin', '{noop}admin123', 'USER;ADMIN', TRUE)
ON CONFLICT (username) DO NOTHING;
