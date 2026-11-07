package org.pipesor.pipeline.shared.interfaces.rest;

import java.util.List;

public record PageResource<T>(List<T> items, int offset, int limit) {
    public PageResource {
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("offset must be nonnegative and limit between 1 and 100");
        }
        items = List.copyOf(items);
    }
}
