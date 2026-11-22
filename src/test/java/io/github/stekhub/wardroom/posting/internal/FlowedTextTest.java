package io.github.stekhub.wardroom.posting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FlowedTextTest {

    @Test
    void aLongLineIsBrokenAfterSpacesAndEveryPartButTheLastEndsWithASpace() {
        String word = "abcdefghi";
        String line = String.join(" ", java.util.Collections.nCopies(20, word));

        List<String> flowedLines = FlowedText.encode(line);

        assertThat(flowedLines).hasSize(3);
        assertThat(flowedLines).allSatisfy(flowedLine -> assertThat(flowedLine.length()).isLessThanOrEqualTo(72));
        assertThat(flowedLines.get(0)).hasSize(70).endsWith(" ");
        assertThat(flowedLines.get(1)).endsWith(" ");
        assertThat(flowedLines.get(2)).doesNotEndWith(" ");
        // A reader joins the lines at the soft breaks and has the line as it was written.
        assertThat(String.join("", flowedLines)).isEqualTo(line);
    }

    @Test
    void shortLinesEmptyLinesAndWordsLongerThanALineStayAsTheyAre() {
        String longWord = "x".repeat(90);

        assertThat(FlowedText.encode("Hello\n\nworld\r\n" + longWord + " after"))
                .containsExactly("Hello", "", "world", longWord + " ", "after");
    }

    @Test
    void trailingSpacesAreRemovedButTheSignatureSeparatorKeepsItsSpace() {
        assertThat(FlowedText.encode("Text   \n-- \nJane  ")).containsExactly("Text", "-- ", "Jane");
    }

    @Test
    void quotedLinesAreNotBroken() {
        String quoted = "> " + "quoted words ".repeat(10).strip();

        assertThat(FlowedText.encode(quoted + "  \n>> deeper")).containsExactly(quoted, ">> deeper");
    }

    @Test
    void linesThatWouldBeMisreadAreSpaceStuffed() {
        assertThat(FlowedText.encode(" indented\nFrom the start\nFromage")).containsExactly("  indented",
                " From the start", "Fromage");
    }

    @Test
    void aPartThatStartsWithAQuoteMarkAfterABreakIsSpaceStuffed() {
        String line = "a".repeat(70) + " > b";

        assertThat(FlowedText.encode(line)).containsExactly("a".repeat(70) + " ", " > b");
    }

    @Test
    void emojisStayWhole() {
        String line = "🙂 ".repeat(40).strip();

        List<String> flowedLines = FlowedText.encode(line);

        assertThat(String.join("", flowedLines)).isEqualTo(line);
        assertThat(flowedLines).allSatisfy(flowedLine -> assertThat(flowedLine).doesNotContain("?"));
    }
}
