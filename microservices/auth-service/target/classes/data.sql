-- Repair demo users created before the DelegatingPasswordEncoder format was used.
UPDATE auth_users SET password = '{noop}alice123'
WHERE username = 'alice' AND password = 'alice123';

UPDATE auth_users SET password = '{noop}admin123'
WHERE username = 'admin' AND password = 'admin123';

INSERT INTO auth_users (username, password, roles, enabled)
VALUES ('alice', '{noop}alice123', 'USER', TRUE)
ON CONFLICT (username) DO NOTHING;

INSERT INTO auth_users (username, password, roles, enabled)
VALUES ('admin', '{noop}admin123', 'USER;ADMIN', TRUE)
ON CONFLICT (username) DO NOTHING;
