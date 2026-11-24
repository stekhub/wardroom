# 024. Choosing the groups of a post

- Status: Accepted
- Date: 2026-11-24
- Related: [spec 003](../003-newsgroup-discovery/spec.md), [spec 009](../009-article-posting/spec.md), [spec 022](../022-group-list-filters-and-paging/spec.md)
- Changes when implemented: "Target groups", the article draft and the generated article in spec 009

## Scope

The owner chooses the groups a new discussion or a reply is posted to, and where follow-ups go. Step 24 of the [implementation plan](../../implementation-plan.md).

## Background

A post can go to several groups at once; this is a crosspost. Replies go to all groups of the post they answer, unless the post has the header `Followup-To`, which names the groups where replies belong. Two customs follow from this:

- When a reply does not concern all groups any more, the writer removes the others. This is called trimming.
- A writer who crossposts sets `Followup-To` to one group, so the discussion stays together in one place.

Many servers and readers treat a post to many groups as spam. Today Wardroom posts a new discussion to one group only and a reply to the groups of its parent, without a way to change them. A reply to a wide crosspost therefore spreads the discussion further, which long-time readers notice at once.

## Decisions

Decided by the owner on 2026-11-24:

1. The groups of a post can be chosen, and `Followup-To` can be set, before 1.0.

Proposed by the AI assistant, open to a veto by the owner:

2. The editor shows the groups of the post as a list of names, each with a remove button, and a field "Add group" with a search of the group list.
3. A new discussion starts with the current group. A reply starts with the groups of its parent (`Followup-To`, otherwise `Newsgroups`), as today.
4. With more than one group, a choice "Follow-ups to" sets `Followup-To` to one of the groups, or to none.
5. A reply to a crosspost starts with "Follow-ups to" set to the current group, because that is where the owner reads the discussion. A new discussion starts with none.
6. A warning appears from 4 groups on. More than 10 groups are not accepted.
7. Only groups of the server's group list are accepted, and none that does not allow posting.
8. The current group can be removed. The post is then stored with a group it was sent to that the owner subscribes to, so the owner sees it there. Without such a group, it is not stored at all.

## User stories

- As the owner, I reply to a post that went to six groups, only in the group where I read it.
- As the owner, I start a discussion in two groups and send the replies to one of them.

## Functional requirements

**The list of groups**

- The editor shows "Groups" above the subject, for a new discussion and for a reply. Each group is shown by name with a remove button (Lucide `x`, with a tooltip "Remove {group}").
- The last group cannot be removed; its button is disabled.
- "Add group" is a field with suggestions: typing searches the group list of the server as on the discover page ([spec 003](../003-newsgroup-discovery/spec.md)), with at most 10 suggestions. Groups that do not allow posting are not suggested; moderated groups are, with the badge "Moderated".
- Binary and ignored groups are not suggested, as on the discover page ([spec 022](../022-group-list-filters-and-paging/spec.md)). A group already in the list is not suggested again.
- The suggestions can be reached with the arrow keys and chosen with Enter. A name that is not suggested cannot be added.
- With a moderated group in the list, the editor shows "Your post is sent to the moderator and appears after approval", as today.

**Follow-ups to**

- Shown when there is more than one group. A choice of the groups in the list, and "None".
- A reply whose parent had more than one target group starts with the current group, when it is in the list. A new discussion starts with "None".
- When the chosen group is removed from the list, the choice goes back to "None".
- Hint below the choice: "Replies to your post go to this group only."

**Checks**

- From 4 groups on, a warning: "Posts to many groups are often treated as spam. Remove the groups your post does not concern." Sending is still possible.
- At most 10 groups. "Add group" is disabled at 10.
- When the current group is not in the list, the editor says: "Your post will not appear in this thread, because it is not sent to {current group}."
- When none of the groups is subscribed, it also says: "You do not read any of these groups, so you will not see your post in Wardroom."

**After sending**

- The post is stored with `posted_from_newsgroup_id` as follows: the current group when it is in the list; otherwise the first group of the list that is subscribed; otherwise the post is not stored, and a toast says "Sent to {groups}".
- Each subscribed group in the list is synchronized soon, so the post finds its location in all of them.
- When the post is not stored in the current group, the editor closes, and a toast says where the post went, with a link to the thread when it was stored.

## The generated article

Changes to the table in spec 009:

| Header | Value |
|---|---|
| `Newsgroups` | The groups of the list, in their order, comma-separated |
| `Followup-To` | The chosen group; the header is left out with "None" |

## API contracts

| Method and path | Request | Responses |
|---|---|---|
| `POST /api/groups/{id}/articles` | Article draft of spec 009, plus `newsgroups` and `followupTo` | as in spec 009; `201` with `storedInGroupId` (`null` when the post was not stored); `400` the groups break a rule |

**Article draft**, additions:

```json
{
  "newsgroups": ["comp.lang.java.programmer", "comp.lang.java.help"],
  "followupTo": "comp.lang.java.programmer"
}
```

- `newsgroups` has 1 to 10 names, without repeats. Each must be a group of the server's group list that allows posting or is moderated.
- `followupTo` is `null` or one of the names in `newsgroups`.
- For a reply, `articleId` and `rootId` in the response are `null` when the post was not stored.
- The group search of the editor uses `GET /api/servers/{id}/groups?q=&size=10` of spec 003; no new endpoint.

## Technical design

- `ArticleComposer.targetGroups` gives the starting list of a reply; the frontend gets it with the parent, so it shows the same groups the backend would use. The backend no longer computes the target groups when sending; it checks the list it receives.
- `ArticleComposer` writes `Followup-To` from the draft.
- `PostingService` chooses the group the post is stored with, as above, and asks for a synchronization of each subscribed group in the list with `GroupSynchronization.synchronizeSoon`.
- The rules of `Followup-To: poster` stay as in spec 009: replying is disabled.

## Data model changes

None.

## Error handling

| Situation | Behavior |
|---|---|
| No group, more than 10, a repeat, or `followupTo` not among them | `400` with the field and the rule |
| An unknown group, or one that does not allow posting | `400` naming the group |
| A group that the server refuses | `502` `POSTING_FAILED` with the server's message, as in spec 009 |

## Security considerations

- Group names are checked against the newsgroup name syntax and the group list of the server before they are written into headers, so a name cannot add headers.
- Group names are rendered as escaped text.

## Testing requirements

- Headers: `Newsgroups` in the order of the list; `Followup-To` with a group and left out with none.
- Checks: no group, 11 groups, a repeat, `followupTo` not in the list, an unknown group and one that does not allow posting are refused; a moderated group is accepted.
- Stored group: the current group when it is in the list; otherwise the first subscribed group; not stored without one; each subscribed group is synchronized.
- A retry with the same `Message-ID` sends the same groups.
- Frontend: the starting lists of a new discussion and of a reply (with and without `Followup-To`); adding with the suggestions and the keyboard; removing, and the last group kept; "Follow-ups to" shown from 2 groups, its starting value, and reset after removal; the warning from 4 groups; the limit of 10; both notes when the current group is removed or no group is subscribed; the toast after sending elsewhere.

## Implementation tasks

1. `newsgroups` and `followupTo` in the draft, their checks and the headers; the choice of the stored group; update `openapi.json`.
2. Frontend: the list of groups with "Add group" and its suggestions, "Follow-ups to", warnings and notes, the toast after sending.
3. Update spec 009 and the user guide.
