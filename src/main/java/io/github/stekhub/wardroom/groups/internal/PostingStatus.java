package io.github.stekhub.wardroom.groups.internal;

/** Whether a group accepts articles (spec 003). */
enum PostingStatus {

    ALLOWED,
    NOT_ALLOWED,
    MODERATED;

    /**
     * @param listActiveStatus the status field of a {@code LIST ACTIVE} line
     * @return posting allowed for {@code y}, moderated for {@code m}, and not allowed for {@code n} and every other
     *         value, such as {@code x}, {@code j} and {@code =other.group}
     */
    static PostingStatus ofListActiveStatus(String listActiveStatus) {
        return switch (listActiveStatus) {
            case "y" -> ALLOWED;
            case "m" -> MODERATED;
            default -> NOT_ALLOWED;
        };
    }
}
