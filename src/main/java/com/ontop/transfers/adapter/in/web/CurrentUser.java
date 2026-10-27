package com.ontop.transfers.adapter.in.web;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authenticated user id is the JWT subject. It is never read from the request body or headers.
 */
final class CurrentUser {

    private CurrentUser() {
    }

    static long id(Jwt jwt) {
        try {
            long userId = Long.parseLong(jwt.getSubject());
            if (userId <= 0) {
                throw new AccessDeniedException("Invalid subject");
            }
            return userId;
        } catch (NumberFormatException | NullPointerException e) {
            throw new AccessDeniedException("Token subject is not a user id");
        }
    }
}
