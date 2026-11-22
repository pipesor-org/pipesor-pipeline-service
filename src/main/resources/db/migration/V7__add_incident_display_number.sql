-- Human-friendly incident number for the dashboard (shown as INC-<n>). It is presentation only:
-- the UUID remains the identity in every contract and route. Existing incidents are numbered in
-- creation order; new ones take the next sequence value at insert time.
CREATE SEQUENCE ops.incident_display_number_seq AS bigint START WITH 1001;

ALTER TABLE ops.incidents ADD COLUMN display_number bigint;

UPDATE ops.incidents i
SET display_number = n.number
FROM (SELECT id, 1000 + row_number() OVER (ORDER BY created_at, id) AS number FROM ops.incidents) n
WHERE i.id = n.id;

SELECT setval('ops.incident_display_number_seq',
              GREATEST((SELECT max(display_number) FROM ops.incidents), 1000));

ALTER TABLE ops.incidents
    ALTER COLUMN display_number SET DEFAULT nextval('ops.incident_display_number_seq'),
    ALTER COLUMN display_number SET NOT NULL,
    ADD CONSTRAINT incidents_display_number_key UNIQUE (display_number);

ALTER SEQUENCE ops.incident_display_number_seq OWNED BY ops.incidents.display_number;
