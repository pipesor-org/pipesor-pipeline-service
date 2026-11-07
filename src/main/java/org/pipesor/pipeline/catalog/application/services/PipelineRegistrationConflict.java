package org.pipesor.pipeline.catalog.application.services;

public final class PipelineRegistrationConflict extends RuntimeException {
    public PipelineRegistrationConflict() { super("Workflow already monitored or incompatible local identity"); }
}
