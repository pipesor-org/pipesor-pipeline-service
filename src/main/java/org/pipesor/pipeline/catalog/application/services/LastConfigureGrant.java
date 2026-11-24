package org.pipesor.pipeline.catalog.application.services;

/** ADR-028: a pipeline keeps at least one CONFIGURE grant, so revoking the last one is refused. */
public final class LastConfigureGrant extends RuntimeException {
    public LastConfigureGrant() { super("The pipeline's last CONFIGURE grant cannot be revoked"); }
}
