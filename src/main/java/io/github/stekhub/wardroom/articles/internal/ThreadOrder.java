package io.github.stekhub.wardroom.articles.internal;

/** The orders a thread list can have (spec 008, "Sorting" and "Date view"). */
enum ThreadOrder {

    /** By the date of the card article, newest first. */
    NEWEST,

    /** By latest activity, newest first. */
    ACTIVITY,

    /** By message count, highest first; ties by latest activity. */
    DISCUSSED,

    /** The date view: by the date of the card article, newest first. */
    UNTIL_DATE
}
