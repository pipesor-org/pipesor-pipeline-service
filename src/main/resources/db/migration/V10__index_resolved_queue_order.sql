-- History orders the RESOLVED queue by resolution time (sort=resolvedAt), keyset-paged on the same expression.
CREATE INDEX incidents_resolved_order_ix ON ops.incidents ((COALESCE(resolved_at, created_at)) DESC, id DESC)
    WHERE status = 'RESOLVED';
