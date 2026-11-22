package io.github.stekhub.wardroom.articles.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ThreadCursorTest {

    @Test
    void aCursorIsReadBackAsItWasWritten() {
        ThreadCursor cursor = new ThreadCursor(ThreadOrder.DISCUSSED, 14, Long.MIN_VALUE, 1201);

        assertThat(ThreadCursor.decode(cursor.encode(), ThreadOrder.DISCUSSED)).contains(cursor);
    }

    @Test
    void aCursorOfAnotherOrderIsNotAccepted() {
        ThreadCursor cursor = new ThreadCursor(ThreadOrder.NEWEST, 14, 0, 1201);

        assertThat(ThreadCursor.decode(cursor.encode(), ThreadOrder.ACTIVITY)).isEmpty();
    }

    @Test
    void textThatIsNoCursorIsNotAccepted() {
        assertThat(ThreadCursor.decode("", ThreadOrder.NEWEST)).isEmpty();
        assertThat(ThreadCursor.decode("not base64 !", ThreadOrder.NEWEST)).isEmpty();
        assertThat(ThreadCursor.decode(encoded("NEWEST,1,2"), ThreadOrder.NEWEST)).isEmpty();
        assertThat(ThreadCursor.decode(encoded("NEWEST,1,2,x"), ThreadOrder.NEWEST)).isEmpty();
        assertThat(ThreadCursor.decode(encoded("NEWEST,1,2,3; DROP TABLE article"), ThreadOrder.NEWEST)).isEmpty();
        assertThat(ThreadCursor.decode("A".repeat(300), ThreadOrder.NEWEST)).isEmpty();
    }

    private static String encoded(String plain) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }
}
