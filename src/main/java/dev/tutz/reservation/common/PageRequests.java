package dev.tutz.reservation.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class PageRequests {

    private static final int MAX_PAGE_SIZE = 100;

    private PageRequests() {
    }

    public static PageRequest create(int page, int size, Sort sort) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessRuleException("INVALID_PAGE", "page must be >= 0 and size between 1 and 100.");
        }
        return PageRequest.of(page, size, sort);
    }
}
