# Dashboard concept prompts

Mode: built-in image-generation tool. Brand palette and navigation were read from the current SeniorLink source. All example readings are synthetic.

## A — Compact overview + activity page

```text
Use case: ui-mockup.
Create a high-fidelity Android mobile UI design proposal board for SeniorLink, an existing voluntary family check-in app. This is concept A: a compact overview with full history on a separate page. Produce one crisp landscape image about 1800x1200 with TWO large, flat, straight-on phone UI panels, each tall Android portrait aspect, side by side, filling most of the board. A simple narrow arrow between them links the "View activity" row on the left to the right page. No photographic phones, no hands, no perspective, no glossy effects. Board title "A  Compact overview", subtitle "A short home screen. History one tap away." Footer "Design concept • Sample data". Nothing clipped; readable typography.

Style based on existing app: background #F6F8F4, white cards, dark forest text #1D3329, muted text #586D61, green #226953, pale green #E4EEE5, pink heart panel #FFF0F0 and dusty rose graph #B4495D. Calm Material 3, generous spacing, rounded 20px cards, Roboto-style type, clear 44dp touch targets and restrained line icons. Small outlined heart beside SeniorLink brand. No neon, no medical dashboards, no claims of safety or health assessments.

LEFT phone: Android status bar 9:41. App header "SeniorLink", circle G. Family selector "Grandad ▾". Title "Overview", quiet subtitle "Last contact 09:41".
Compact pink heart card: "Heart rate", large "72 bpm", "Recorded 09:37", small accurate-looking trend chart with simple 09:00 and 09:37 endpoint labels, no ECG wave.
Two equal compact battery cards side by side: phone icon, "Phone", "46%", "Recorded 09:39"; watch icon, "Watch", "72%", "Recorded 09:35".
A white horizontal card with check-in icon: "Last check-in", "I'm okay", "Today, 09:15".
A slim white location row with pin icon: "Latest saved location", "09:32 · ±45 m", right chevron. Do not invent home/address or infer current location.
At bottom a pale green navigable row "View activity" with subline "Check-ins and recorded updates" and right chevron. This is a modest row, not a huge primary button. There is NO event feed below it.
Fixed bottom navigation exactly five items with small line icons: "Updates" (selected), "Location", "Wearable", "Phones", "Settings". No Start or Pause controls on this screen.

RIGHT phone: status bar, header with back arrow and "Grandad's activity". Date selector "Today, 13 Sep". Filter pills "All" selected, "Check-ins", "Devices", and a filter icon. White simple grouped timeline rows, not large cards: "09:39" / "Phone battery" / "46% · Not charging"; "09:37" / "Wearable reading" / "72 bpm"; "09:32" / "Location recorded" / "Accuracy ±45 m"; "09:15" / "I'm okay" / "Check-in"; "08:54" / "Phone unlocked". Comfortable spacing. Small outlined "Load earlier" at end; footer note "Up to 7 days of saved history". Detail page back navigation makes exit obvious. No unbounded feed on the HOME page. Every reading is historical/sample, no LIVE badge, no "all is well", no warning/alarm assertions.
Render only exact specified UI text with correct spelling. Show polished believable Android app screens, not a wireframe or marketing illustration.
```

## B — Daily summary with grouped details

```text
Use case: ui-mockup.
Create a high-fidelity Android mobile UI proposal board for SeniorLink, a voluntary family check-in app. This is concept B: a daily digest grouping frequent recordings by type instead of displaying a long event feed. One crisp landscape image about 1800x1200, TWO large flat Android portrait screens side by side. Header "B  Daily summary", subtitle "Group frequent updates. Expand only what matters." Small footer "Design concept • Sample data". A discreet arrow links the Wearable digest row in the left screen to an expanded detail sheet on the right. No photographic phone shells, hands, perspective or decorative marketing artwork.

Match existing SeniorLink palette precisely: #F6F8F4 background, #1D3329 ink, #586D61 muted type, #226953 green, #E4EEE5 soft green cards, #FFF0F0 pink heart card, #B4495D trend. White surfaces, rounded corners, generous readable spacing, restrained outline icons and Roboto-style type. Practical Android Material 3 design. No medical health status badges, no "Live", no safety assessment.

LEFT screen status 9:41; brand outlined heart plus "SeniorLink", G circle. "Grandad ▾", "Today's summary", "Last contact 09:41".
At top compact pink heart panel "Heart rate", "72 bpm", "Recorded 09:37", small ordinary trend chart with endpoint labels.
Below two slim separate battery cards: "Phone 46%" / "09:39"; "Watch 72%" / "09:35".
Date controls "<  Today, 13 Sep  >".
A finite white daily digest card with FOUR well spaced navigable rows. Row1 check icon, "Check-ins", "1 recorded · Last 09:15", chevron. Row2 heart/watch icon, "Wearable", "18 readings · Last 09:37", chevron. Row3 location icon, "Location", "5 fixes · Last 09:32", chevron. Row4 phone icon, "Phone activity", "3 unlocks · Last 08:54", chevron. These are observed sample recording counts, not completeness/health claims.
Under digest small text link "Open full history".
Fixed bottom navigation five small-icon destinations exactly: "Updates" selected, "Location", "Wearable", "Phones", "Settings". No Start or Pause buttons, no raw feed at bottom.

RIGHT screen: same left-screen layout visible but subtly dimmed behind a rounded bottom sheet occupying lower ~70% of screen. Sheet has drag handle AND clearly visible X close control, title "Wearable · Today", subtitle "18 recorded readings", a modest pink heart-rate trend graph labelled "Recorded heart rate". Then four compact list rows: "09:37    72 bpm", "09:35    74 bpm", "09:33    71 bpm", "09:31    73 bpm". At bottom simple green text action "View all 18 readings". No endless list within the overview. Make bottom sheet extremely clear visually and distinguish it from full page, with dimmed background top visible. Use correct exact labels, no extra paragraphs. Charts are sample trends, never ECGs. Values are synthetic and marked by board footer. The design emphasizes fewer interruptions and grouped routine sensor data while keeping individual timestamps accessible.
```

## C — Overview and Activity views in the same tab

```text
Use case: ui-mockup.
Create a polished high-fidelity Android mobile UI proposal board for SeniorLink, a voluntary family check-in app. This is concept C: split the current Updates destination into two explicit subviews "Overview" and "Activity", retaining the existing five bottom navigation items. One landscape image around 1800x1200 with TWO large straight-on flat Android portrait UI panels side by side, comfortable margins. Board title "C  Overview / Activity", subtitle "Keep history easy to reach without putting it on the overview." Tiny board footer "Design concept • Sample data". Slim arrow connects the Activity segment of the left UI to the right. No photographic devices, hands, perspective, excessive shadows or busy decorative background.

Use existing SeniorLink visual system: warm off-white #F6F8F4, white rounded cards, dark forest #1D3329, muted #586D61, primary green #226953, soft green #E4EEE5, pink #FFF0F0 heart card, rose #B4495D trend chart. Friendly, clear Android Material 3, large readable Roboto-style text, useful hierarchy, outlined icons. No LIVE badges, alarms, wellness scores or claims that missing data means danger.

BOTH phones: status bar 9:41. Brand "SeniorLink" with small outlined heart, round G avatar. Family selector "Grandad ▾". Small text "Last contact 09:41". Prominent but compact two-segment control "Overview" and "Activity". Fixed bottom nav in BOTH screens exactly "Updates" (selected), "Location", "Wearable", "Phones", "Settings".

LEFT screen Overview segment selected in pale green. Content starts with title "Latest readings". Pink heart-rate card: "Heart rate", "72 bpm", "Recorded 09:37" and small clean non-ECG trend graph labelled 09:00 and 09:37. Side-by-side separate battery tiles "Phone" "46%" "Recorded 09:39", and "Watch" "72%" "Recorded 09:35". Slim latest check-in card "I'm okay" with caption "Last check-in · 09:15". Slim location card "Latest saved location" with caption "09:32 · ±45 m" and chevron. Leave calm empty margin at bottom. There is no timeline or raw feed on this Overview.

RIGHT screen Activity segment selected in pale green. Below segments a date control "< Today, 13 Sep >". Filter chips "All" selected, "Check-ins", "Devices", with filter icon. Chronological list inside one clean white surface; timestamp column and subtle type icons. Rows "09:39" "Phone battery" "46% · Not charging"; "09:37" "Wearable reading" "72 bpm"; "09:32" "Location recorded" "Accuracy ±45 m"; "09:15" "I'm okay" "Check-in"; "08:54" "Phone unlocked". A clearly labelled "Load earlier" row after these, plus "Up to 7 days of saved history". All history is bounded by date and user-initiated loading. No additional bottom tab and no dashboard start/pause controls. Maintain SAME screen chrome and tab placements across left and right so the user understands the simple switch. Readable verbatim labels. Show sample data only with the footer, no invented current location/home label or medical conclusions.
```

## B refinement 1

```text
Edit this SeniorLink concept B design board. Preserve the entire layout, all screen chrome, typography, colors, cards, all reading values, captions, titles, sheet, and sample-data footer exactly. Make one targeted correction: on BOTH visible pink heart-rate charts, change the x-axis time labels from "06:00", "12:00", "18:00" to "06:00", "08:00", "09:37" respectively. This matches the Android clock 9:41 and the existing Recorded 09:37 reading; there must be no future data timestamps. Also apply the same correction if any chart labels are visible in the dimmed background behind the bottom sheet. Keep all other pixels as unchanged as possible. No redesign, no additions.
```

## B final refinement

```text
Edit only the chart tick labels in this SeniorLink concept B image. Remove the tiny time labels underneath ALL pink heart-rate charts, on both phones including the bottom sheet. Leave those tiny x-axis tick-label areas blank. Do not add replacement text. Keep every other visible element exactly: the graph lines, the y-axis 90 and 50, values 72, cards, dates, Recorded 09:37 caption, individual reading timestamps, all interface layout and the Sample data footer. Nothing else changes.
```
