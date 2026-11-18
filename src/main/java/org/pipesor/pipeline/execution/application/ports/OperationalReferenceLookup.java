package org.pipesor.pipeline.execution.application.ports;

import org.pipesor.pipeline.execution.application.queries.OperationalReferenceQuery;

public interface OperationalReferenceLookup {
    enum Membership { MATCH, NOT_READY, MISMATCH }

    Membership check(OperationalReferenceQuery query);
}
