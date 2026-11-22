package io.github.stekhub.wardroom.posting.internal;

/** Reads single values out of the stored header block of an article. The block is untrusted text. */
final class HeaderBlock {

    private HeaderBlock() {
    }

    /**
     * @param headerBlock the header lines of an article, with any line ending
     * @param name        the name of the header, without the colon; compared without regard to case
     * @return the value of the first header of this name, with the lines it is folded over joined by a space;
     *         {@code null} when the block has no such header
     */
    static String value(String headerBlock, String name) {
        String prefix = name + ":";
        StringBuilder value = null;
        for (String line : headerBlock.split("\r\n|\r|\n")) {
            boolean continuation = line.startsWith(" ") || line.startsWith("\t");
            if (value != null) {
                if (!continuation) {
                    break;
                }
                value.append(' ').append(line.strip());
            } else if (!continuation && line.regionMatches(true, 0, prefix, 0, prefix.length())) {
                value = new StringBuilder(line.substring(prefix.length()).strip());
            }
        }
        return value == null ? null : value.toString().strip();
    }
}
