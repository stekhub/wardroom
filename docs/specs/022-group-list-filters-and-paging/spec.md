# 022. Group list filters and paging

- Status: Accepted
- Date: 2026-11-24
- Related: [spec 003](../003-newsgroup-discovery/spec.md), [spec 015](../015-accessibility-and-mobile-fixes/spec.md)
- Changes when accepted: "Browsing and searching" and the group list endpoint in spec 003

## Scope

The group list of the discover view hides binary groups unless the owner asks for them, and gets a paging row that jumps to any page. Part of step 21 of the [implementation plan](../../implementation-plan.md). Requested by the owner after the first activity values showed the list ordered by activity full of binary groups.

## Decisions

Proposed by the AI assistant and approved by the owner on 2026-11-24:

1. A group is a binary group when one part of its name, between dots, is `binaries` or `binary`, for example `alt.binaries.linux` or `de.alt.binaries.bilder`. Servers do not mark binary groups, so the name is the only sign. Groups with other names are not detected; more rules can be added later.
2. A checkbox "Show binary groups" next to the order, off by default, shows them. It is part of the address of the page, like the search, the order and the page. It also applies to the search.
3. The backend filters, so the pages and the number of pages stay right.
4. Subscribed groups are never hidden from the sidebar; the filter applies to the group list only.
5. At the bottom of the list, a paging row: "First", "Previous", "Page [ n ] of N", "Next", "Last". The page number is a field: the owner types a number and presses Enter. A dropdown is not used, because the list can have thousands of pages.

## User stories

- As the owner, the groups ordered by activity show where people talk, not where files are posted.
- As the owner, I go to page 300 of the group list without clicking 300 times.

## Functional requirements

**Binary groups**

- By default, binary groups do not appear in the group list, in its search or in its order by activity.
- With "Show binary groups" checked, the list is as before.
- The address of the page carries the checkbox, for example `/discover?sort=activity&binaries=true`; without it, binary groups are hidden.
- A subscribed binary group stays in the sidebar and can be opened as before.

**Paging row**

- Shown below the list when there is more than one page.
- "First" and "Previous" are disabled on the first page, "Next" and "Last" on the last one.
- The field shows the current page, counted from 1. A number between 1 and the last page opens that page when Enter is pressed; another value is not accepted and the field says so.
- Changing the search, the order or the checkbox opens page 1.
- The page is part of the address, as before; after a change of page the list scrolls to its top.
- Every control has a label and can be reached with the keyboard (spec 015).

## API contracts

| Method and path | Request | Responses |
|---|---|---|
| `GET /api/servers/{id}/groups?q=&sort=&binaries=&page=&size=` | as in spec 003, plus `binaries` (`true` or `false`, default `false`) | as in spec 003 |

- With `binaries=false`, `total` counts only the groups that are not binary groups.
- `activityKnown` stays independent of the filter.

## Technical design

- The filter is a condition in the query of the group list: the name, with a dot added before and after, contains neither `.binaries.` nor `.binary.`. It needs no new column.
- The number of pages is computed by the frontend from `total` and `size`.

## Data model changes

None.

## Error handling

| Situation | Behavior |
|---|---|
| `binaries` is neither `true` nor `false` | `400` |
| A page beyond the last one is requested in the address | The list is empty and the paging row leads back to the last page |

## Security considerations

None.

## Testing requirements

- Binary groups by name: `alt.binaries.x`, `x.binary`, `binaries` alone; not `alt.binariesx` or `alt.notbinaries`.
- Hidden by default in the list, the search and the order by activity; shown with `binaries=true`; `total` follows the filter.
- A subscribed binary group stays in the subscriptions.
- Frontend: the checkbox in the address; the paging row with its disabled buttons; typing a page number and Enter; an invalid number; changing search, order or checkbox opens page 1.

## Implementation tasks

1. Filter in the group list query and the `binaries` parameter; update `openapi.json`.
2. Checkbox and paging row in the discover view.
3. Update spec 003 and the user guide.
