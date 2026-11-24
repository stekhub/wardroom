# 023. Ignored subject words

- Status: Accepted
- Date: 2026-11-24
- Related: [spec 006](../006-threading/spec.md), [spec 008](../008-discussion-view/spec.md), [spec 010](../010-ignoring/spec.md), [spec 014](../014-next-unread-and-mark-as-unread/spec.md), [spec 016](../016-full-text-search/spec.md), [spec 022](../022-group-list-filters-and-paging/spec.md)
- Changes when implemented: "Managing rules", the rule endpoints and the data model of spec 010

## Scope

The owner ignores a word or a phrase in subjects, in all groups or in one group. Threads with it in the subject are hidden like ignored threads. Part of step 21 of the [implementation plan](../../implementation-plan.md). Patterns and scoring stay [deferred](../../open-questions.md#deferred-beyond-the-mvp).

## Decisions

Decided by the owner on 2026-11-24:

1. Ignoring words in subjects is part of 1.0, because long-time readers of newsgroups filter by subject.

Proposed by the AI assistant, open to a veto by the owner:

2. The owner adds a word or a phrase in the settings section "Ignored", for all groups or for one subscribed group.
3. A thread is hidden when the subject of its card article ([spec 006](../006-threading/spec.md)) contains the word. The card article is the one a card shows, so what the owner sees in the list decides, as for ignored authors.
4. Upper and lower case do not matter. Whole words match, not parts of words: "java" hides "Java 25 released" but not "JavaScript tips". A phrase matches its words in this order.
5. A filter, not a change, as all ignore rules (spec 010): nothing is deleted or marked read.
6. There is no "Ignore word" action on cards or articles. Words are typed in the settings, because a word is chosen on purpose, not from one subject.

## User stories

- As the owner, I hide every thread about a topic I do not care about, such as "election", in all groups.
- As the owner, I hide the weekly "FAQ" posts in one group without hiding them elsewhere.

## Functional requirements

**Adding a word**

- In the settings section "Ignored", a list "Words in subjects" with a field, a choice of "All groups" or one of the subscribed groups, and "Add".
- A word or phrase has 1 to 100 characters and at least one letter or digit. Punctuation counts as a space: "C++" is the same as "C", and "re-post" is the same as "re post".
- Adding a word that is already ignored for the same groups does nothing and keeps the existing rule.

**Effect**

- A hidden thread is not shown in the thread list, the date view or the classic view, including when new replies arrive.
- Its articles do not count as unread, in that group only for a rule of one group.
- `N`, `Space` and `C` skip it ([spec 014](../014-next-unread-and-mark-as-unread/spec.md)).
- The search leaves it out, as it leaves out ignored threads ([spec 016](../016-full-text-search/spec.md)).
- Opening a hidden thread through a link still shows it, with the notice "Hidden in the list by the ignored word "{word}"" and a link to the settings section.
- A word does not hide single articles inside a visible thread; only the card article decides.

**The list**

- Each entry shows the word as typed, its group or "All groups", the date it was added and a remove button. Removing it shows everything as before.
- A rule of one group stays when the group is unsubscribed, so it applies again after a new subscription. It is deleted with the server ([spec 002](../002-nntp-server-configuration/spec.md)).

## Matching

- When an article is stored or a stub is filled in, the backend computes its subject words: the decoded subject, lowercased, with every character that is not a letter or a digit replaced by a space, runs of spaces made one, and one space added before and after. Example: `Re: Java 25 — released!` gives ` re java 25 released `.
- The word of a rule is normalized the same way, without the spaces around it.
- A subject matches when its subject words contain the rule's word with a space before and after.
- Lowercasing uses the root locale, so it does not depend on the language of the container.

## API contracts

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/ignore-rules` | | as in spec 010; rules of kind `SUBJECT` carry `groupId` and `groupName`, `null` for all groups |
| `POST /api/ignore-rules` | as in spec 010, plus `{ "kind": "SUBJECT", "text": "election", "groupId": null }` | `201` rule, also if it already exists; `400` the text breaks a rule; `404` unknown group |
| `DELETE /api/ignore-rules/{id}` | | as in spec 010 |

The thread response gains `ignoredWord` (the word as typed, or `null`), for the notice.

## Technical design

- `IgnoreFilters` gets the condition for subject words. The thread list (`ThreadListRepository`), the unread counts (`SubscriptionRepository`), next unread and the search use it, so every list follows the same rule.
- The condition compares `article.subject_words` of the card article with `LIKE '% word %'`, with `%`, `_` and the escape character in the word escaped. A rule of one group applies only to the threads of that group.
- The subject words are computed by a pure class in the `articles` module, next to `AuthorKey`, by the code that stores an article or fills in a stub. At the start, a startup task fills in the subject words that are missing, as `AuthorKeyStartup` does.
- The ignoring module stores data of a server through the group of a rule, so it implements `ServerRemovalCleanup`.

## Data model changes

- `ignore_rule.kind` gets the value `SUBJECT`. `value` is the normalized word, `label` the word as typed.
- New column `ignore_rule.newsgroup_id`, FK `newsgroup`, nullable; set only for a rule of one group. The unique key becomes `(kind, value, newsgroup_id)`, with all groups counted as one value.
- New column `article.subject_words`, nullable, filled in by the startup task for existing articles.

## Error handling

| Situation | Behavior |
|---|---|
| Text empty, too long, or without a letter or digit | `400` with the field and the rule |
| `groupId` of a group that is not subscribed | `400` |
| Rule already exists | `201` with the existing rule |

## Security considerations

- The word is typed by the owner and rendered as escaped text. Subjects are untrusted and rendered as text, as before.
- The word is passed to the query as a parameter, never as part of the SQL.

## Testing requirements

- Normalization: case, punctuation, runs of spaces, non-ASCII letters (`Äpfel` matches `äpfel`), digits.
- Whole words: "java" hides "Java 25 released" and "[java] question", not "JavaScript tips"; a phrase matches its words in order only.
- Hidden in the thread list, the date view, the unread counts, next unread and the search; a rule of one group only in that group.
- A thread whose card article does not match stays visible, even when a reply has the word.
- Removing the rule restores the lists and counts; no read state was written.
- The startup task fills in the subject words of existing articles; stubs get them when they are filled in.
- A rule of one group survives an unsubscribe and is deleted with the server.
- Frontend: adding a word for all groups and for one group; the list with removal; the notice on a hidden thread opened by link.

## Implementation tasks

1. Flyway migration for `ignore_rule.newsgroup_id`, the new unique key and `article.subject_words`; startup task for existing articles.
2. Subject words computed when articles are stored and stubs filled in.
3. The `SUBJECT` rule in the endpoints and in `IgnoreFilters`; update `openapi.json`.
4. Frontend: the list "Words in subjects" in the settings section "Ignored", the notice on the thread page.
5. Update spec 010, the data model and the user guide.
