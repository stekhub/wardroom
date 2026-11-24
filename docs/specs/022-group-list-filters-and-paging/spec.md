# 022. Group list filters and paging

- Status: Accepted
- Date: 2026-11-24, changed on the same day before implementation (see "Changes")
- Related: [spec 003](../003-newsgroup-discovery/spec.md), [spec 010](../010-ignoring/spec.md), [spec 015](../015-accessibility-and-mobile-fixes/spec.md)
- Changes when implemented: "Browsing and searching" and the group list endpoint in spec 003; "Managing rules" and the rule endpoints in spec 010

## Scope

The group list of the discover view hides binary groups and the groups the owner ignores, and gets a paging row that jumps to any page. Part of step 21 of the [implementation plan](../../implementation-plan.md). Requested by the owner after the first activity values showed the list ordered by activity full of binary groups.

## Decisions

Proposed by the AI assistant and approved by the owner on 2026-11-24:

1. Servers do not mark binary groups, so the name is the only sign. Wardroom detects them with a few naming rules (see "Binary groups by name").
2. The backend filters, so the pages and the number of pages stay right.
3. Subscribed groups are never hidden from the sidebar; the filters apply to the group list only.
4. At the bottom of the list, a paging row: "First", "Previous", "Page [ n ] of N", "Next", "Last". The page number is a field: the owner types a number and presses Enter. A dropdown is not used, because the list can have thousands of pages.

Changed by the owner on 2026-11-24, with points proposed by the AI assistant and approved by the owner:

5. Hiding binary groups is a setting "Hide binary groups" in the settings section "Ignored", on by default, instead of a checkbox on the discover page. It is stored on the server and is not part of the address of the page.
6. The owner can ignore a group. An ignored group does not appear in the group list. Ignored groups are a third kind of ignore rule, next to authors and threads ([spec 010](../010-ignoring/spec.md)).
7. Wardroom has a built-in list of binary groups that the naming rules miss. The owner fills it during implementation, after checking which binary groups the naming rules still show. Further naming rules found in this check are added instead where they fit. The built-in list is hidden by the same setting.

## User stories

- As the owner, the groups ordered by activity show where people talk, not where files are posted.
- As the owner, I ignore a group I never want to see in the group list.
- As the owner, I go to page 300 of the group list without clicking 300 times.

## Binary groups by name

A group is a binary group when:

- one part of its name, between dots, is `binaries`, `binary` or `bin`, for example `alt.binaries.linux` or `de.alt.binaries.bilder`;
- one part of its name is `mp3` or `warez`;
- its name starts with `a.b.`, the common short form of `alt.binaries.`;
- or its name is on the built-in list.

Proposed by the AI assistant as a start. The owner checks the list of a real server during implementation; rules that hide discussion groups are removed, and rules for binary groups that are still shown are added.

## Functional requirements

**Binary groups**

- With "Hide binary groups" on, binary groups do not appear in the group list, in its search or in its order by activity.
- With the setting off, the list shows them as before.
- A subscribed binary group stays in the sidebar and can be opened as before.

**Ignored groups**

- Each row of the group list has a button "Ignore group" (Lucide `eye-off`, with a tooltip), except for subscribed groups. Ignoring a group hides the row at once and shows a toast with "Undo", as for the other ignore actions.
- A subscribed group cannot be ignored, so ignoring never hides a group the owner reads. After an unsubscribe, the button appears.
- An ignored group does not appear in the group list, in its search or in its order by activity, whatever the setting.
- The rule matches the exact group name. There are no patterns.

**Settings section "Ignored"**

- Below the ignored authors and threads, a third list "Groups" with each ignored group, the date it was added and a remove button. Removing it shows the group in the group list again.
- Above the lists, the switch "Hide binary groups" with the explanation "Hides groups whose names show that they carry files, such as alt.binaries.*." The section gets a save button for the switch, as every settings section ([UI concept](../../product/ui-concept.md#settings-page)).

**Paging row**

- Shown below the list when there is more than one page.
- "First" and "Previous" are disabled on the first page, "Next" and "Last" on the last one.
- The field shows the current page, counted from 1. A number between 1 and the last page opens that page when Enter is pressed; another value is not accepted and the field says so.
- Changing the search or the order opens page 1.
- The page is part of the address, as before; after a change of page the list scrolls to its top.
- Every control has a label and can be reached with the keyboard (spec 015).

## API contracts

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/servers/{id}/groups?q=&sort=&page=&size=` | as in spec 003 | as in spec 003, without binary groups when the setting is on and without ignored groups |
| `GET`, `PUT /api/settings` | as in spec 005, plus `groups.hideBinaryGroups` (boolean, default `true`) | as in spec 005 |
| `POST /api/ignore-rules` | as in spec 010, plus `{ "kind": "GROUP", "groupId": 42 }` | as in spec 010; `400` the group is subscribed; `404` unknown group |

- `total` counts only the groups that are shown.
- `activityKnown` stays independent of the filters.
- For `GROUP`, the backend takes the value and the label from the group name. `GET /api/ignore-rules` returns these rules with `kind` `GROUP`.

## Technical design

- The filters are conditions in the query of the group list. The naming rules compare the name with a dot added before and after, for example that it does not contain `.binaries.`. The built-in list is a table in the code of the groups module. They need no new column.
- Ignored groups are left out through the `ignore_rule` table, built with `IgnoreFilters` of the ignoring module.
- The number of pages is computed by the frontend from `total` and `size`.

## Data model changes

- `ignore_rule.kind` gets the value `GROUP`; `value` and `label` are the group name.
- `app_setting` gets the key `groups.hide_binary_groups`, default `true`.

## Error handling

| Situation | Behavior |
|---|---|
| Ignoring a subscribed group | `400`; the button is not shown for subscribed groups |
| Ignoring a group that is already ignored | `201` with the existing rule |
| A page beyond the last one is requested in the address | The list is empty and the paging row leads back to the last page |

## Security considerations

- Group names come from the server and are rendered as escaped text.

## Testing requirements

- Binary groups by name: `alt.binaries.x`, `x.binary`, `binaries` alone, `alt.bin.x`, `x.mp3.y`, `a.b.x`, a group on the built-in list; not `alt.binariesx`, `alt.notbinaries`, `comp.lang.binary-trees` or `ab.x`.
- Hidden with the setting on, in the list, the search and the order by activity; shown with the setting off; `total` follows the filters.
- An ignored group is hidden whatever the setting and comes back when the rule is removed; ignoring a subscribed group is refused.
- A subscribed binary group stays in the subscriptions.
- Frontend: "Ignore group" with toast and undo, and no button on subscribed groups; the switch and the third list in the settings section; the paging row with its disabled buttons; typing a page number and Enter; an invalid number; changing search or order opens page 1.

## Implementation tasks

1. Naming rules, built-in list and the ignore filter in the group list query; the setting and the `GROUP` rule; update `openapi.json`.
2. "Ignore group" and the paging row in the discover view; the switch and the third list in the settings section "Ignored".
3. The owner checks the group list of a real server; naming rules and built-in list are adjusted.
4. Update spec 003, spec 010, the UI concept, the data model and the user guide.

## Changes

- 2026-11-24, before implementation: the checkbox "Show binary groups" on the discover page and the parameter `binaries` were replaced by the setting "Hide binary groups" in the section "Ignored". Ignored groups, more naming rules and the built-in list were added.
