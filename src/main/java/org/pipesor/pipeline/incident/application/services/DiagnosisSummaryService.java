package org.pipesor.pipeline.incident.application.services;

import org.pipesor.pipeline.incident.application.commands.RecordDiagnosisSummaryCommand;

public interface DiagnosisSummaryService {
    RecordDiagnosisSummaryCommand.Result handle(RecordDiagnosisSummaryCommand command);
}
