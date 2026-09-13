# Dashboard design concepts

Design exploration, 13 September 2026. These are image-generated caregiver-screen mockups with synthetic readings, not screenshots of implemented screens. The initial exploration changed no application code. The user subsequently selected B for implementation in version 0.1.9.

## Selected design

**B: Daily summary** was selected. Version 0.1.9 uses compact latest-reading cards, daily counts grouped by type, a preview sheet, and full history with date/type filters and explicit loading. The main page no longer appends the event feed.

| Concept | Interaction | Tradeoff |
| --- | --- | --- |
| [A — Compact overview](01-overview-and-activity.png) | Latest readings, separate phone/watch batteries, last check-in and saved location; View activity opens a dedicated page. | Clearest home screen; inspecting history requires opening another page. |
| [B — Daily summary](02-daily-digest.png) | Counts and latest times grouped by update type; tap a group for a detail sheet and its full records. | Good for frequent recordings; counts add visual complexity and describe observations, not completeness. |
| [C — Overview / Activity](03-overview-activity-tabs.png) | A segmented control within the existing Updates destination switches between overview and history. | Convenient for frequent history inspection; adds another level of navigation. |

## Design behavior

- Keep the current five bottom destinations; do not add a sixth History tab. Keep background sharing controls in Settings.
- Show one selected family member consistently across all dashboard cards and their activity page. On the sharing phone, use the same summary with its own data and retain the I'm okay action.
- Keep the overview bounded: latest values and fixed summary rows, with no appended event list. Allow ordinary scrolling for large fonts and small screens rather than forcing everything into a fixed height.
- Open location and wearable detail views from their summary cards. Distinguish last contact time from measurement time, and explicitly show unavailable or old readings.
- Put the full timeline behind View activity or the Activity segment. Offer date and type filters and explicit Load earlier pagination within the retained history.
- Group routine battery, location and wearable recordings by day/type, with expansion to individual timestamps. Keep check-ins easy to find.
- Calculate daily counts from the relevant retained data, not only the latest 100 dashboard events. Label partial history if some records expired or were withdrawn.
- Avoid inferred health/safety status. Missing updates are missing data; recorded locations are not guaranteed current locations.

## Original behavior inspected

The original dashboard appended up to 100 individual event cards underneath health/device information and connection/location panels. That creates the long scrolling page. These concepts reorganize the existing information; they do not require new sensors or more frequent background collection.

## Deliverables and generation

The three PNGs above were generated with the built-in image-generation tool and visually reviewed. B was refined to remove inconsistent illustrative chart tick labels. Chart artwork is schematic; a future implementation should use real timestamped points, preserve gaps, and label actual time ranges.

[Exact generation prompts and refinement prompts](prompts.md).


## Implemented previews

These are Android view renders from the isolated emulator using synthetic data,
not generated mockups. Production screenshot protection remained enabled. A
sheet-only render has transparency outside the sheet instead of the underlying
activity backdrop.

| View | Normal text | 2× text |
| --- | --- | --- |
| Latest readings | [Overview](implemented/overview-1.0.png) | [Overview](implemented/overview-2.0.png) |
| Daily groups | [Groups](implemented/groups-1.0.png) | [Groups](implemented/groups-2.0.png) |
| Wearable preview | [Sheet](implemented/sheet-1.0.png) | [Sheet](implemented/sheet-2.0.png) |
| Filtered history | [History](implemented/history-1.0.png) | [History](implemented/history-2.0.png) |

The page has a bounded set of groups; it can still scroll on small screens or
with large text. Full history loads only when opened, in batches of 40. Daily
counts include all retained records, not only the current history page.
