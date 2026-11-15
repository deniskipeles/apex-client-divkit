# Workspace Notifications Tray

The notifications tray is the launcher's cross-workspace notification surface. It groups
notifications **by workspace** (Reddit-style collapsible groups), pins each notification's
**workspace glyph to the far right** (Google-style), and offers per-notification
**View / Mark read / Mute** actions. It is not a `tools://` capability and not a DivKit node
type — it is a native Compose surface driven by an immutable state snapshot.

## Entry points

| Where | What |
| :--- | :--- |
| Top app bar, bell icon (`top_bar_notifications_button`) | Opens the tray overlay. A `Badge` (`notification_unread_badge`) shows the total unread count. |
| Tenant cards (launcher) | The per-workspace unread badge now reads from the live tray count (`SduiEngineUiState.unreadFor`), falling back to the seeded spec value when the tray is empty. |
| Group overflow menu | Open workspace, Mark all read, Mute/Unmute workspace, Clear notifications. |

## Data model

All model types live in `engine/notifications/NotificationCenter.kt` and are **free of Android
and Compose types**, so the whole reducer is unit-testable on the plain JVM.

```kotlin
enum class NotificationType(val label: String, val icon: String)  // icon is a catalog name

data class NotificationTarget(val type: String, val id: String, val url: String? = null)
data class NotificationActions(canView: Boolean, canMarkRead: Boolean, canMute: Boolean)

data class WorkspaceNotification(
    id: String,
    workspaceId: String,
    workspaceName: String,
    workspaceIcon: String,        // bundled icon reference, e.g. "storefront"
    title: String,
    message: String? = null,
    createdAt: Long,
    type: NotificationType = NotificationType.SYSTEM,
    isRead: Boolean = false,
    isMuted: Boolean = false,
    isExpanded: Boolean = false,
    target: NotificationTarget? = null,
    actions: NotificationActions = NotificationActions()
)

data class NotificationGroup(
    workspaceId, workspaceName, workspaceIcon,
    notifications, unreadCount, isMuted, latestAt
)
```

`workspaceIcon` is a canonical name from the bundled catalog
([`icons://native/<name>`](icons.md)), resolved for rendering through
`engine.icons.nativeIconVector(reference)`.

## Grouping rules

`NotificationCenter.group(list)` produces the group list:

- Partition by `workspaceId`.
- Within a group, newest notification first (`createdAt` descending).
- Groups are ordered by their newest notification (`latestAt` descending).
- `unreadCount` counts only unread items; `isMuted` is true only when **every** item in the
  group is muted.
- Read notifications stay visible (the tray lists groups, it does not hide them), unless the
  "Show read notifications" switch is off, in which case read items are filtered out before
  grouping and a fully-read group disappears.

## Reducers

`NotificationCenter` exposes pure, copy-on-write reducers. Each returns a new list; the input is
never mutated.

| Function | Effect |
| :--- | :--- |
| `markRead(list, id)` | Marks one notification read. |
| `markWorkspaceRead(list, workspaceId)` | Marks one workspace read. |
| `markAllRead(list)` | Marks everything read. |
| `mute(list, id)` | Mutes the whole workspace of `id` **and** marks it read. |
| `muteWorkspace(list, workspaceId)` | Mutes a group. |
| `unmuteWorkspace(list, workspaceId)` | Un-mutes a group. |
| `dismiss(list, id)` / `dismissWorkspace(list, workspaceId)` | Removes one / a group. |
| `toggleExpanded(list, id)` | Flips one row's expanded state. |
| `add(list, notification, mutedWorkspaces)` | Prepends, unless the workspace is muted (then it is dropped). |
| `relativeTime(createdAt, nowMillis)` | `now` / `8m` / `3h` / `2d` / `1w`, clock injected for determinism. |

Muting is a workspace-level rule: muting one notification suppresses future notifications for
that notification's workspace, and `add(...)` drops incoming items whose workspace is muted.

## UI structure

`app/src/main/java/com/example/ui/NotificationTray.kt`:

- `NotificationTrayOverlay(uiState, viewModel, onDismiss)` — the scrim + anchored panel. It
  builds a `NotificationTrayActions` callback bundle and renders `NotificationTrayPanel`.
- `NotificationTrayPanel(uiState, actions, modifier)` — a **dumb, state-driven** composable:
  every interaction goes through the injected `NotificationTrayActions`, which is what the UI
  tests drive. Fixed-width dropdown on wide layouts (`screenWidthDp >= 600`), near-full-width
  sheet on narrow ones, capped at 640 dp tall.
- `WorkspaceGroupHeader` — workspace glyph, name, unread badge, collapsed preview, relative age,
  group overflow menu and the expand/collapse chevron. The whole header is the expand toggle and
  exposes `stateDescription = Expanded|Collapsed`.
- `NotificationRow` — unread dot / type glyph on the left, title + message + age in the middle
  (`weight(1f)`), and the **workspace glyph pinned to the far right**, vertically centred against
  the text. Actions (`View`, `Mark read`, `Mute`, plus a dismiss `X`) appear on hover, focus, when
  the row is expanded, or always on narrow layouts.
- `TrayHeader` — title, unread summary, Mark all read (disabled at zero unread), settings toggle,
  close.
- `TraySettingsBar` — "Show read notifications" switch and "Simulate incoming notification".
- `NotificationEmptyState` / `NotificationSkeletonList` — empty and loading states.

Every interactive element carries a `testTag` (`notification_*`) and the glyphs expose
`contentDescription` (`"<workspace> workspace"`, `"Muted"`), so the tray is exercisable by both
Compose UI tests and accessibility services.

## ViewModel wiring

`SduiEngineViewModel` owns the tray state and all actions:

- State: `notifications`, `notificationsLoading`, `notificationSettingsOpen`,
  `notificationShowRead`, `notificationGroupByWorkspace`, `expandedNotificationGroups`, plus the
  derived `totalUnreadNotifications` / `notificationUnreadByWorkspace` / `unreadFor(...)`.
- Seed: the `init` block loads `NotificationSeed.demo(now)` so the tray is populated on first frame.
- Actions: `markNotificationRead`, `markAllNotificationsRead`, `markWorkspaceNotificationsRead`,
  `muteNotification`, `muteNotificationWorkspace`, `unmuteNotificationWorkspace`,
  `dismissNotification`, `dismissNotificationWorkspace`, `toggleNotificationExpanded`,
  `toggleNotificationGroup`, `openNotificationTarget`, `simulateIncomingNotification`,
  `setNotificationShowRead`, `setNotificationGroupByWorkspace`, `setNotifications`,
  `setNotificationsLoading`.
- `openNotificationTarget` marks the notification read, points the active route at
  `/<target.type>/<target.id>` for non-workspace targets, then opens the owning workspace.

All mutations funnel through one helper (`mutateNotifications`) that also sets the status banner,
so unread counts, group ordering and the top-bar badge update in a single recomposition.

## Adding a notification source

Feed new items through `NotificationCenter.add` (respecting muted workspaces) and push the result
into state via `setNotifications`, or model a new producer as an additional seed in
`NotificationSeed`. New notification kinds should add a `NotificationType` entry whose `icon`
names an existing catalog icon — `NotificationCenterTest` asserts every type icon resolves.

## Tests

- `NotificationCenterTest` (plain JVM) — grouping order, unread counts, mute/unmute, dismiss,
  expand, `add` suppression, relative-time buckets, and that every seeded/type icon resolves
  through `NativeIconCatalog`.
- `NotificationTrayUiTest` (Robolectric + Compose) — collapsed group headers, expansion revealing
  rows with the right-aligned glyph and View/Mark read/Mute actions, action dispatch, disabled
  mark-all-read at zero unread, show-read filtering, empty and skeleton states, and the muted
  indicator.
