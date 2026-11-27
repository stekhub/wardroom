package io.github.stekhub.wardroom.groups.internal;

import java.util.Collection;
import java.util.List;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * Which groups carry files instead of discussions (spec 022, "Binary groups by name"). Servers do not mark such
 * groups, so the name is the only sign.
 */
final class BinaryGroups {

    /**
     * A group is a binary group when one part of its name, between dots, is one of these. After the English words
     * come the forms of "binary" that hierarchies in other languages use.
     */
    static final List<String> NAME_PARTS = List.of("binary", "bin", "mp3", "warez", "binaer", "binaere", "binarios",
            "binari", "binaires");

    /**
     * A group is a binary group when one part of its name starts with one of these, as in
     * {@code alt.binariespw-required}. Not {@code binary}, which would hide {@code comp.lang.binary-trees}.
     */
    static final List<String> NAME_PART_STARTS = List.of("binaries");

    /**
     * A group is a binary group when its name starts with one of these: the common short form of
     * {@code alt.binaries.}, and hierarchies that carry files.
     */
    static final List<String> NAME_STARTS = List.of("a.b.", "alt.multimedia.", "3b.");

    /**
     * Binary groups that the naming rules miss, by their exact names. From the owner's check of the group list of
     * a real server (2026-11-25).
     */
    static final List<String> BUILT_IN = List.of("alt.multimedia", "alt.asian-movies", "ab.alt.movies",
            "4u.alt.movies");

    private BinaryGroups() {
    }

    /** @return whether the group with this name is a binary group */
    static Condition isBinary(Field<String> groupName) {
        return isBinary(groupName, BUILT_IN);
    }

    /**
     * @param builtIn names of binary groups the naming rules miss
     * @return whether the group with this name is a binary group. Group names are compared without regard to
     *         case, as SQLite does for the letters A to Z
     */
    static Condition isBinary(Field<String> groupName, Collection<String> builtIn) {
        // With a dot before and after, every part of the name stands between two dots.
        Field<String> dottedName = DSL.concat(DSL.inline("."), groupName, DSL.inline("."));
        Condition binary = DSL.falseCondition();
        for (String nameStart : NAME_STARTS) {
            binary = binary.or(groupName.like(DSL.inline(nameStart + "%")));
        }
        for (String namePart : NAME_PARTS) {
            binary = binary.or(dottedName.like(DSL.inline("%." + namePart + ".%")));
        }
        for (String namePartStart : NAME_PART_STARTS) {
            binary = binary.or(dottedName.like(DSL.inline("%." + namePartStart + "%")));
        }
        if (!builtIn.isEmpty()) {
            binary = binary.or(DSL.lower(groupName).in(builtIn.stream().map(String::toLowerCase).toList()));
        }
        return binary;
    }
}
