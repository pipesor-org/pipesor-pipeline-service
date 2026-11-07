-- DD-002 origin constraints from the canonical SQL generator, source revision 18dacfb.
-- DD-002: at most one ORIGIN, including concurrent writes.
CREATE UNIQUE INDEX incident_runs_one_origin_uq
ON ops.incident_runs (incident_id) WHERE role = 'ORIGIN';

-- DD-002: exactly one failed ORIGIN, consistent with the incident pipeline.
-- No extra workflow policy is imposed on VERIFICATION runs.
-- Deferred so incident + ORIGIN can be inserted in the same transaction.
CREATE FUNCTION ops.assert_incident_runs(p_incident uuid) RETURNS void
LANGUAGE plpgsql SET search_path = pg_catalog, ops AS $$
DECLARE
    p_pipeline uuid;
BEGIN
    SELECT pipeline_id INTO p_pipeline FROM ops.incidents
      WHERE id = p_incident FOR UPDATE;
    IF NOT FOUND THEN RETURN; END IF;
    IF (SELECT count(*) FROM ops.incident_runs
        WHERE incident_id = p_incident AND role = 'ORIGIN') <> 1 THEN
        RAISE EXCEPTION 'Incident % requires exactly one ORIGIN', p_incident
          USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM ops.incident_runs ir
        JOIN ops.pipeline_executions pe ON pe.id = ir.execution_id
        WHERE ir.incident_id = p_incident AND ir.role = 'ORIGIN' AND
          (pe.pipeline_id <> p_pipeline OR pe.failed IS NOT TRUE)
    ) THEN
        RAISE EXCEPTION 'Incident % requires a failed ORIGIN consistent with its pipeline', p_incident
          USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION ops.check_incident_runs() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, ops AS $$
DECLARE
    linked_incident uuid;
BEGIN
    IF TG_TABLE_NAME = 'incidents' THEN
        PERFORM ops.assert_incident_runs(NEW.id);
    ELSIF TG_TABLE_NAME = 'incident_runs' THEN
        IF TG_OP <> 'INSERT' THEN
            PERFORM ops.assert_incident_runs(OLD.incident_id);
        END IF;
        IF TG_OP <> 'DELETE' THEN
            PERFORM ops.assert_incident_runs(NEW.incident_id);
        END IF;
    ELSE
        FOR linked_incident IN SELECT incident_id FROM ops.incident_runs
            WHERE execution_id = NEW.id ORDER BY incident_id LOOP
            PERFORM ops.assert_incident_runs(linked_incident);
        END LOOP;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER incidents_origin_required
AFTER INSERT OR UPDATE ON ops.incidents DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION ops.check_incident_runs();

CREATE CONSTRAINT TRIGGER incident_runs_origin_required
AFTER INSERT OR UPDATE OR DELETE ON ops.incident_runs DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION ops.check_incident_runs();

CREATE CONSTRAINT TRIGGER execution_origin_stays_failed
AFTER UPDATE ON ops.pipeline_executions DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION ops.check_incident_runs();

-- TRUNCATE bypasses row/constraint triggers; reject it to preserve the invariant.
CREATE FUNCTION ops.reject_incident_runs_truncate() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Use transactional DELETE for incident run records'
      USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER incident_runs_no_truncate BEFORE TRUNCATE ON ops.incident_runs
FOR EACH STATEMENT EXECUTE FUNCTION ops.reject_incident_runs_truncate();
