package io.github.stekhub.wardroom.articles.internal;

import static io.github.stekhub.wardroom.jooq.Tables.ARTICLE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stekhub.wardroom.IntegrationTest;
import io.github.stekhub.wardroom.TestInstallation;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The author keys of articles that were stored before the key was stored with them (spec 010). */
@IntegrationTest
class AuthorKeyStartupTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    DSLContext database;

    @Autowired
    AuthorKeyStartup authorKeyStartup;

    @Test
    void theStartGivesStoredArticlesTheirAuthorKeyAndLeavesStubsAndStoredKeysAlone() {
        TestInstallation.loggedInOwner(mvc, database);
        int withAddressId = article("<a>", false, "Jane Doe <Jane@Example.org>", null);
        int nameOnlyId = article("<b>", false, "  Jane Doe ", null);
        int stubId = article("<c>", true, null, null);
        int withoutFromId = article("<d>", false, null, null);
        int withKeyId = article("<e>", false, "Bob <bob@example.org>", "kept@example.org");

        authorKeyStartup.fillMissingAuthorKeys();

        assertThat(authorKeyOf(withAddressId)).isEqualTo("jane@example.org");
        assertThat(authorKeyOf(nameOnlyId)).isEqualTo("Jane Doe");
        assertThat(authorKeyOf(stubId)).isNull();
        assertThat(authorKeyOf(withoutFromId)).isNull();
        assertThat(authorKeyOf(withKeyId)).isEqualTo("kept@example.org");
    }

    private int article(String messageId, boolean stub, String from, String authorKey) {
        return database.insertInto(ARTICLE)
                .set(ARTICLE.MESSAGE_ID, messageId)
                .set(ARTICLE.IS_STUB, stub)
                .set(ARTICLE.FROM, from)
                .set(ARTICLE.AUTHOR_KEY, authorKey)
                .returning(ARTICLE.ID)
                .fetchSingle().getId();
    }

    private String authorKeyOf(int articleId) {
        return database.select(ARTICLE.AUTHOR_KEY).from(ARTICLE).where(ARTICLE.ID.eq(articleId))
                .fetchSingle(ARTICLE.AUTHOR_KEY);
    }
}
