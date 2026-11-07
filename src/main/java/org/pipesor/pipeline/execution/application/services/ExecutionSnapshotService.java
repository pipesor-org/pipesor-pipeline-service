package org.pipesor.pipeline.execution.application.services;

import org.pipesor.pipeline.execution.application.commands.ApplyExecutionSnapshotCommand;

public interface ExecutionSnapshotService {
    String handle(ApplyExecutionSnapshotCommand command);
}
