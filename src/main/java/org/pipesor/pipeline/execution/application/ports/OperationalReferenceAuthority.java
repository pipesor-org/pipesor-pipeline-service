package org.pipesor.pipeline.execution.application.ports;

import org.pipesor.pipeline.execution.application.queries.OperationalReferenceQuery;

/** Composition must authenticate the caller and check current exact-revision machine scope. */
public interface OperationalReferenceAuthority {
    enum Permission { AUTHORIZED, DENIED, UNAVAILABLE }

    Permission verify(OperationalReferenceQuery query);
}
