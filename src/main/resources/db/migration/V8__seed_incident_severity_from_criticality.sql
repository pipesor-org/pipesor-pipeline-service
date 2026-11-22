-- MVP rule (see Severity.initialFor): an incident's initial severity is its pipeline's criticality when it was
-- opened. Unclassified incidents created before the rule take the pipeline's current criticality.
UPDATE ops.incidents i
SET severity = p.criticality::text::ops."Severity"
FROM ops.pipelines p
WHERE p.id = i.pipeline_id AND i.severity IS NULL;
