package org.pipesor.pipeline.incident.interfaces.rest.transform;

/** A page token that is malformed or bound to other criteria (problem code {@code INVALID_PAGE_TOKEN}). */
public final class InvalidPageToken extends IllegalArgumentException {
    public InvalidPageToken() { super("Invalid page token"); }
}
