package org.pipesor.pipeline.incident.application.commands;

/** The summary's execution, pipeline or repository is not the incident's (problem code {@code SCOPE_MISMATCH}). */
public final class DiagnosisSummaryScopeMismatch extends RuntimeException {
    public DiagnosisSummaryScopeMismatch() { super("Diagnosis summary does not bind to the incident"); }
}
