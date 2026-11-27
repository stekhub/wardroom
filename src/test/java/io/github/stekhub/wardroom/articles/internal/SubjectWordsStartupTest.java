package io.github.stekhub.wardroom.articles.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestInstallation;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The subject words of articles that were stored before the words were stored with them (spec 023). */
@IntegrationTest
class SubjectWordsStartupTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    SubjectWordsStartup subjectWordsStartup;

    @Test
    void theStartGivesStoredArticlesTheirSubjectWordsAndLeavesStubsAndStoredWordsAlone() {
        TestInstallation.loggedInOwner(mvc, database);
        int withSubjectId = article("<a>", false, "Re: Java 25 — released!", null);
        int stubId = article("<b>", true, null, null);
        int withoutSubjectId = article("<c>", false, null, null);
        int withWordsId = article("<d>", false, "Something else", " kept ");

        subjectWordsStartup.fillMissingSubjectWords();

        assertThat(subjectWordsOf(withSubjectId)).isEqualTo(" re java 25 released ");
        assertThat(subjectWordsOf(stubId)).isNull();
        assertThat(subjectWordsOf(withoutSubjectId)).isNull();
        assertThat(subjectWordsOf(withWordsId)).isEqualTo(" kept ");
    }

    private int article(String messageId, boolean stub, String subject, String subjectWords) {
        return database.insertInto(ARTICLE)
                .set(ARTICLE.MESSAGE_ID, messageId)
                .set(ARTICLE.IS_STUB, stub)
                .set(ARTICLE.SUBJECT, subject)
                .set(ARTICLE.SUBJECT_WORDS, subjectWords)
                .returning(ARTICLE.ID)
                .fetchSingle().getId();
    }

    private String subjectWordsOf(int articleId) {
        return database.select(ARTICLE.SUBJECT_WORDS).from(ARTICLE).where(ARTICLE.ID.eq(articleId))
                .fetchSingle(ARTICLE.SUBJECT_WORDS);
    }
}
