# 018. Notifications

- Status: Proposed
- Date: 2026-11-23
- Related: [spec 005](../005-article-synchronization/spec.md), [spec 009](../009-article-posting/spec.md), [spec 010](../010-ignoring/spec.md), [spec 012](../012-two-factor-authentication/spec.md), [ADR 0025](../../architecture/decisions/0025-external-encryption-key.md)

## Scope

Notifications on a phone or elsewhere, sent to notification URLs: right away for new replies in chosen threads, and once a day as a summary of chosen groups. Step 28 of the [implementation plan](../../implementation-plan.md), after 1.0.

## Decisions

Decided by the owner on 2026-11-23:

1. A bell on threads and on groups turns notifications on.
2. A thread with the bell: a notification when a synchronization finds a new reply in it, so within the synchronization interval.
3. A group with the bell: once a day, one summary with the number of new articles per group with the bell. At 20:00 by default, in the time zone of the container; the time is a setting.
4. A thread the owner posts in gets the bell on its own, so replies to own articles are notified.
5. Notifications are sent to one or more notification URLs in the settings, each with a test button. The URLs follow the common format of the Shoutrrr library. Wardroom implements the format itself and starts with `ntfy://` and `generic://`; further services on demand. No browser push.
6. A notification URL can contain a token, so it is stored encrypted like the NNTP password and never logged.
7. The user guide names ntfy as an example service, as an exception the owner asked for.

## Details added by the AI assistant

Open to a veto by the owner:

1. An optional setting "Address of Wardroom", for example `https://wardroom.example.com`. With it, a notification opens the thread when tapped; without it, it is text only.
2. Articles that arrive read are not notified: those stored by "load older" and jump to date (ADR 0022), and the owner's own articles.
3. Replies by ignored authors and in ignored threads are not notified.
4. When one synchronization finds several replies in the same thread, they are sent as one notification ("3 new replies").
5. A group summary is sent only if a group with the bell has new articles. "New" means arrived since the last summary and still unread.
6. Turning on a bell without a notification URL works, and a hint points to the settings.
7. A failed notification is logged as `WARN` with the scheme and host of the URL, never the whole URL, and is not repeated. The settings show the time and the reason of the last failure per URL.

## User stories

- As the owner, my phone tells me when someone replies to my article.
- As the owner, I follow a thread and hear about new replies without opening Wardroom.
- As the owner, I get one message in the evening that tells me where something happened.

## Functional requirements

**Bells**

- The group header has a bell button next to the subscribe toggle; the thread page header has one, and thread cards show a small bell when it is on. Lucide `bell` when off, `bell-ring` when on, with a tooltip and a label.
- One click turns it on or off, with a short toast.
- Posting a new discussion or a reply turns the bell of its thread on (spec 009). The owner can turn it off again.
- Unsubscribing a group removes its bell and the bells of its threads.

**Thread notifications**

- After each synchronization run, new replies in threads with the bell are sent: title "New reply: {subject}", text "{author} in {group}", and the link to the thread when the address is set.
- Detail 2, 3 and 4 apply.

**Group summary**

- Every day at the set time, one notification: title "Wardroom: new articles", text one line per group with the bell and new articles, for example "comp.lang.java.programmer: 12".
- Detail 5 applies. The link leads to the start page when the address is set.

**Settings**

- A new settings section "Notifications" (Lucide `bell`): the notification URLs, each with "Test" and "Remove", and "Add URL"; the time of the summary; the address of Wardroom.
- A URL is shown as its scheme and host; the whole URL is never shown again after it was saved. To change it, the owner removes it and adds it again.
- "Test" sends "Wardroom test notification" and shows the result.
- The user guide explains the format for `ntfy://` and `generic://`, with ntfy as an example.

**Supported URLs**

- `ntfy://[:token@]host[:port]/topic`: a `POST` to the topic over HTTPS, with the title, the text and the link; the token is sent as a bearer token. `?scheme=http` for a server without HTTPS.
- `generic://host[:port]/path`: a `POST` of the text to the address over HTTPS; `?template=json` sends `{ "title", "message" }`; `?disabletls=yes` uses HTTP; query parameters that start with `@` become headers.
- The exact syntax follows the documentation of the format; the tests use its examples.

## API contracts

| Method and path | Request | Responses |
|---|---|---|
| `PUT`, `DELETE /api/groups/{id}/notification` | | `204`; `404` unknown or unsubscribed group |
| `PUT`, `DELETE /api/groups/{id}/threads/{articleId}/notification` | | `204`; `404` as for the thread page |
| `GET /api/notification-urls` | | `200 [{ "id", "label", "lastFailure" }]`; `label` is scheme and host |
| `POST /api/notification-urls` | `{ "url" }` | `201 { "id", "label" }`; `400` unknown scheme or invalid URL |
| `DELETE /api/notification-urls/{id}` | | `204` |
| `POST /api/notification-urls/{id}/test` | | `204` sent; `502 { "reason" }` the service refused or could not be reached |

- The thread cards, the thread and the subscriptions get a field `notifying`.
- `GET`, `PUT /api/settings` get `notification.summaryTime` (`HH:mm`, default `20:00`) and `notification.wardroomAddress` (nullable).

## Technical design

- A new module `notifications`. It reads the watched threads and groups, finds the new articles after each synchronization run, and sends through one `NotificationSender` per scheme.
- New articles are found with a mark: the highest article ID that was looked at is stored, and each run looks at the articles above it. The same for the summary, with its own mark. This needs no change in the synchronization except an event at the end of each run.
- A thread is watched by the `Message-ID` of its root, as ignored threads are (spec 010), so the bell stays when the root changes.
- The summary is scheduled each day at the set time in the time zone of the container; a change of the setting takes effect at once. A summary missed because Wardroom was not running is not sent later.
- The URLs are encrypted with `SecretCipher` of the `platform` module (spec 012), purpose `notification-url`.
- Requests to the services have a timeout of 10 seconds and follow no redirects.
- Subjects, names and group names in a notification are untrusted text; control characters are removed and the text is shortened to 200 characters.

## Data model changes

- New table `watched_thread` with `thread_message_id` (unique) and `created_at`.
- `subscription`: new column `notify_daily` (`BOOLEAN`, default false).
- New table `notification_url` with `id`, `url_encrypted`, `label`, `last_failure_at` (`BIGINT`, nullable) and `last_failure_reason` (nullable).
- `app_setting`: `notification.summary_time`, `notification.wardroom_address`, and the marks `notification.thread_mark` and `notification.summary_mark`.
- The module implements `ServerRemovalCleanup`; unsubscribing removes the bells of the group and of its threads.

## Error handling

| Situation | Behavior |
|---|---|
| A service refuses or cannot be reached | `WARN` with scheme and host; the failure is shown in the settings; not repeated |
| A URL cannot be decrypted (key file lost) | Shown as failed in the settings; the owner adds it again |
| No URL configured | Nothing is sent; bells work; a hint in the settings and when a bell is turned on |

## Security considerations

- URLs are stored encrypted, never returned whole, never logged.
- Notifications carry subjects, names and group names to the service the owner chose; the user guide says so.
- No redirects are followed, so a service cannot send the request elsewhere.

## Testing requirements

- `ntfy://` and `generic://`: parsing, the request sent (against a mock HTTP server), the token as a header, HTTP and HTTPS, the JSON template, headers from `@` parameters.
- Thread notifications after a synchronization: new replies are sent once, grouped per thread; read, own, ignored and loaded-older articles are not.
- Posting turns the bell of the thread on.
- The summary at the set time with a controlled clock; nothing when nothing is new; a changed time.
- URLs are stored encrypted and never returned or logged; a failed request is logged without the URL.
- Unsubscribing and removing the server remove the bells.
- Frontend: the bells, the settings section, the test button and its result.

## Implementation tasks

1. Migration; module `notifications` with the cleanup.
2. URL parsing and the senders for `ntfy://` and `generic://`.
3. Watching threads and groups; bells on posting.
4. Thread notifications after each synchronization run; the daily summary.
5. Endpoints and settings.
6. Frontend: bells and the settings section.
7. User guide: notification URLs, with ntfy as an example.
