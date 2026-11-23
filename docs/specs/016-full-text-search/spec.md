# 016. Full-text search

- Status: Proposed
- Date: 2026-11-23
- Related: [ADR 0013](../../architecture/decisions/0013-synchronization-and-caching.md), [ADR 0023](../../architecture/decisions/0023-cache-size-limit.md), [spec 007](../007-article-reading/spec.md), [spec 010](../010-ignoring/spec.md), [spec 014](../014-next-unread-and-mark-as-unread/spec.md)
- Changes when accepted: the layout in the UI concept (a top bar)

## Scope

One search over all cached articles of the subscribed groups, by words in the subject, the author and the text. Step 22 of the [implementation plan](../../implementation-plan.md).

## Decisions

Decided by the owner on 2026-11-23:

1. One search over all cached articles, not limited to one group, with the results on a new page.
2. Only what is cached is searched; no content is fetched in advance. The text of an article is found once it was opened; the subject and author of every synchronized article.
3. All words must match; a phrase can be put in quotes. No other operators.
4. Results are ordered by best match.
5. The search field is in a top bar of every page.
6. The results are a simple list of articles. Each shows the subject, the author, the group and the date, and opens its thread at that article. More results are loaded like the thread list.

## Details added by the AI assistant

Open to a veto by the owner:

1. Each result shows a short excerpt around the words found, with these words in bold. When only the subject or the author matched, the excerpt is the start of the text, as on the cards; without cached text there is none.
2. A match in the subject counts more than one in the author, and that more than one in the text.
3. Upper and lower case and accents do not matter: "cafe" finds "Café".
4. The top bar also holds the menu button on narrow screens, which today has a row of its own.
5. A query has at most 200 characters and 10 words or phrases.
6. Unread results are bold, as on the cards.

## User stories

- As the owner, I find the thread where someone explained something, even when I only remember a few words.
- As the owner, I find all articles of an author in my groups.

## Functional requirements

- The top bar is above the main area on every page with the sidebar. It holds the search field, at most 768 pixels wide, centered like the content.
- Enter in the field opens the results page `/search?q=...`. The query is part of the address, so back and bookmarks work.
- A query is split into words and phrases in quotes. A quote without its end counts as ending with the query.
- An article matches when every word and phrase is found in its subject, its author or its text. Words match whole words, not parts of words.
- Results contain non-stub articles that are located in a subscribed group, without the authors and threads the owner ignores (spec 010).
- Each result shows the subject, the author with the author icon, the group, the date, the excerpt and the unread state. A click opens the thread at that article (`?article=`, [spec 014](../014-next-unread-and-mark-as-unread/spec.md)).
- A crossposted article is shown once, with the first subscribed group by name where it is located.
- The page loads 50 results; more are loaded at the end of the list, as in the thread list.
- Without results, the page says so and that only opened articles are searched by their text.
- Opening the results page marks nothing as read.

## API contracts

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/search` | `q` (the query), `cursor`, `limit` (default 50, max 100) | `200` page of results; `400` empty or too long query |

**Page of results**

```json
{
  "results": [
    {
      "articleId": 4711,
      "groupId": 12,
      "groupName": "comp.lang.java.programmer",
      "threadArticleId": 4600,
      "subject": "Generics and erasure",
      "author": "Jane Doe <jane@example.com>",
      "authorKey": "jane@example.com",
      "date": "2004-03-14T10:22:00Z",
      "unread": true,
      "excerpt": [
        { "text": "...the type is ", "match": false },
        { "text": "erased", "match": true },
        { "text": " at compile time...", "match": false }
      ]
    }
  ],
  "nextCursor": "..."
}
```

- `excerpt` is `null` when there is no cached text. The frontend draws each part as text and the parts with `match` in bold.
- `threadArticleId` is the ID the thread page is opened with, as in the thread cards.

## Technical design

- An FTS5 table `article_search` of SQLite indexes `subject`, `from` and `body_text` of `article`, as an external-content table, so the text is not stored twice. Triggers keep it up to date when an article is stored, its content is fetched or emptied by the cache cleanup (`CacheRepository.clearContent`), or it is deleted.
- Tokenizer `unicode61` with `remove_diacritics 2`.
- The backend turns the query into an FTS5 expression of quoted strings only, so no input is read as an FTS5 operator.
- Order: `bm25` with the weights 3 for the subject, 2 for the author and 1 for the text.
- The excerpt comes from the FTS5 function `snippet` with markers that cannot appear in text; the backend splits it into parts and removes the markers.
- The cursor is the position in the ordered result. Results may move when a synchronization adds articles between two pages; this is accepted.
- The search table and its shadow tables are left out of the jOOQ code generation; the search query is written as SQL in one repository of the `articles` module.
- The index is part of the database, so it counts in the cache size (ADR 0023) without a change there.

## Data model changes

- New FTS5 table `article_search` with the columns `subject`, `from` and `body_text`, external content `article`.
- Triggers on `article` for insert, update of these columns and delete.
- The migration builds the index for the articles that exist.

## Error handling

| Situation | Behavior |
|---|---|
| Empty query, or more than 200 characters or 10 words | `400`; the field says why |
| No results | The page says so and explains that only opened articles are searched by their text |

## Security considerations

- The query never reaches SQLite as an FTS5 expression of its own; every word is a quoted string.
- Excerpts and subjects are untrusted text and are drawn as text, never as HTML.
- The query is not logged.

## Testing requirements

- Matches in subject, author and text; all words must match; phrases; a quote without its end; case and accents.
- Order: a match in the subject before one in the text.
- Ignored authors and threads, stubs, unsubscribed groups and articles without location are left out; a crosspost appears once.
- The index follows when content is fetched, emptied by the cache cleanup and deleted with a group.
- Characters that are FTS5 operators (`"`, `*`, `:`, `(`, `NEAR`, `AND`) are searched as text and do not cause an error.
- The excerpt parts and their `match` flags.
- Paging with the cursor.
- Frontend: the top bar on every page; Enter opens `/search?q=`; results drawn as text with bold matches; a click opens the thread at the article; loading more; the empty page.

## Implementation tasks

1. Migration with the FTS5 table, triggers and the first index; leave it out of jOOQ.
2. Query parser and search repository.
3. `GET /api/search`.
4. Top bar with the search field.
5. Results page.
6. Update the UI concept and the user guide.
