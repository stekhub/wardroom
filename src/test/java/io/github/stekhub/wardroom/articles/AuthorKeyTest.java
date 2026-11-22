package io.github.stekhub.wardroom.articles;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthorKeyTest {

    @Test
    void theKeyIsTheLowercasedAddress() {
        assertThat(AuthorKey.of("Jane Doe <Jane@Example.ORG>")).isEqualTo("jane@example.org");
        assertThat(AuthorKey.of("\"Doe, Jane\" < jane@example.org >")).isEqualTo("jane@example.org");
        assertThat(AuthorKey.of("jane@example.org (Jane Doe)")).isEqualTo("jane@example.org");
        assertThat(AuthorKey.of("  JANE@example.org  ")).isEqualTo("jane@example.org");
    }

    @Test
    void withoutAnAddressTheKeyIsTheTrimmedName() {
        assertThat(AuthorKey.of("  Jane Doe ")).isEqualTo("Jane Doe");
        assertThat(AuthorKey.of("Jane <not an address>")).isEqualTo("Jane <not an address>");
    }

    @Test
    void withoutAnAuthorThereIsNoKey() {
        assertThat(AuthorKey.of(null)).isNull();
        assertThat(AuthorKey.of("   ")).isNull();
    }
}
