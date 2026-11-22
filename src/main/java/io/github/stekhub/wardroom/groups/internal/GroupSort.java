package io.github.stekhub.wardroom.groups.internal;

import io.github.stekhub.wardroom.platform.FieldValidationException;

/** The orders of the group list page (spec 003). */
enum GroupSort {

    /** By name. */
    NAME,

    /** The groups with the most articles per day first; groups without a value last. */
    ACTIVITY;

    /**
     * @param parameter the value of the request parameter {@code sort}, or {@code null} when it is absent
     * @throws FieldValidationException when the value names no order
     */
    static GroupSort ofParameter(String parameter) {
        if (parameter == null || parameter.equals("name")) {
            return NAME;
        }
        if (parameter.equals("activity")) {
            return ACTIVITY;
        }
        throw new FieldValidationException("sort", "The order must be name or activity.");
    }
}
