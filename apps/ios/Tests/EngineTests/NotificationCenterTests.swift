import XCTest
@testable import Engine

/// Tests for the workspace notification tray. Mirrors `NotificationCenterTest.kt`.
final class NotificationCenterTests: XCTestCase {

    private let now: Int64 = 1_700_000_000_000
    private var seed: [WorkspaceNotification] { NotificationSeed.demo(nowMillis: now) }

    func testDemoSeedCoversMultipleWorkspacesWithUnreadItems() {
        let workspaces = Set(seed.map(\.workspaceId))
        XCTAssertGreaterThanOrEqual(workspaces.count, 3)
        XCTAssertGreaterThan(NotificationCenter.unreadCount(seed), 0)
    }

    func testDemoSeedIconsAllResolveThroughTheNativeIconCatalog() {
        for icon in Set(seed.map(\.workspaceIcon)) {
            XCTAssertTrue(NativeIconCatalog.isKnown(icon), "seed icon \(icon) does not resolve")
        }
    }

    func testNotificationTypesMapToCatalogIcons() {
        for type in NotificationType.allCases {
            XCTAssertTrue(NativeIconCatalog.isKnown(type.icon), "type \(type.rawValue) icon does not resolve")
            XCTAssertFalse(type.label.isEmpty)
        }
    }

    func testFromWireFallsBackToSystemForUnknownValues() {
        XCTAssertEqual(NotificationType.fromWire("comment"), .comment)
        XCTAssertEqual(NotificationType.fromWire("COMMENT"), .comment)
        XCTAssertEqual(NotificationType.fromWire("nonsense"), .system)
        XCTAssertEqual(NotificationType.fromWire(nil), .system)
    }

    func testGroupPartitionsByWorkspaceNewestGroupFirstNewestItemFirst() {
        let groups = NotificationCenter.group(seed)
        let latestTimes = groups.map(\.latestAt)
        XCTAssertEqual(latestTimes, latestTimes.sorted(by: >), "groups are not newest-first")
        for group in groups {
            let times = group.notifications.map(\.createdAt)
            XCTAssertEqual(times, times.sorted(by: >), "items in \(group.workspaceId) are not newest-first")
        }
    }

    func testGroupCountsOnlyUnreadAndReportsMutedOnlyWhenEveryItemIsMuted() {
        let groups = NotificationCenter.group(seed)
        for group in groups {
            XCTAssertEqual(group.unreadCount, group.notifications.filter { $0.isUnread }.count)
            XCTAssertEqual(group.isMuted, group.notifications.allSatisfy { $0.isMuted })
        }
    }

    func testUnreadCountAggregatesAcrossAndWithinWorkspaces() {
        let total = NotificationCenter.unreadCount(seed)
        let perWorkspace = Set(seed.map(\.workspaceId)).reduce(0) { acc, ws in
            acc + NotificationCenter.unreadCount(seed, workspaceId: ws)
        }
        XCTAssertEqual(total, perWorkspace)
    }

    func testMarkReadAffectsOnlyTheTargetNotification() {
        let target = seed.first { $0.isUnread }!
        let updated = NotificationCenter.markRead(seed, id: target.id)
        XCTAssertTrue(updated.first { $0.id == target.id }!.isRead)
        let others = updated.filter { $0.id != target.id }
        XCTAssertEqual(others.filter { $0.isRead }.count, seed.filter { $0.id != target.id && $0.isRead }.count)
    }

    func testMarkWorkspaceReadScopesToOneWorkspace() {
        let ws = seed.first!.workspaceId
        let updated = NotificationCenter.markWorkspaceRead(seed, workspaceId: ws)
        XCTAssertEqual(NotificationCenter.unreadCount(updated, workspaceId: ws), 0)
        XCTAssertEqual(NotificationCenter.unreadCount(updated), NotificationCenter.unreadCount(seed) - NotificationCenter.unreadCount(seed, workspaceId: ws))
    }

    func testMarkAllReadClearsEveryUnreadFlag() {
        XCTAssertEqual(NotificationCenter.unreadCount(NotificationCenter.markAllRead(seed)), 0)
    }

    func testMuteMutesTheWholeWorkspaceAndClearsUnread() {
        let target = seed.first { $0.isUnread }!
        let updated = NotificationCenter.mute(seed, id: target.id)
        let ws = target.workspaceId
        XCTAssertTrue(updated.filter { $0.workspaceId == ws }.allSatisfy { $0.isMuted && $0.isRead })
        XCTAssertEqual(NotificationCenter.unreadCount(updated, workspaceId: ws), 0)
    }

    func testMuteOfAnUnknownIdIsANoOp() {
        let updated = NotificationCenter.mute(seed, id: "does_not_exist")
        XCTAssertEqual(updated, seed)
    }

    func testUnmuteRestoresAWorkspace() {
        let ws = seed.first!.workspaceId
        let muted = NotificationCenter.muteWorkspace(seed, workspaceId: ws)
        let unmuted = NotificationCenter.unmuteWorkspace(muted, workspaceId: ws)
        XCTAssertTrue(unmuted.filter { $0.workspaceId == ws }.allSatisfy { !$0.isMuted })
    }

    func testDismissRemovesOneAndDismissWorkspaceRemovesAGroup() {
        let target = seed.first!
        XCTAssertNil(NotificationCenter.dismiss(seed, id: target.id).first { $0.id == target.id })
        let ws = target.workspaceId
        XCTAssertTrue(NotificationCenter.dismissWorkspace(seed, workspaceId: ws).allSatisfy { $0.workspaceId != ws })
    }

    func testToggleExpandedFlipsOneRow() {
        let target = seed.first!
        let toggled = NotificationCenter.toggleExpanded(seed, id: target.id)
        XCTAssertEqual(toggled.first { $0.id == target.id }?.isExpanded, !target.isExpanded)
    }

    func testAddPrependsANotificationUnlessItsWorkspaceIsMuted() {
        let incoming = NotificationSeed.incoming(workspaceId: NotificationSeed.primaryWorkspaceId, nowMillis: now, sequence: 1)
        let added = NotificationCenter.add(seed, notification: incoming)
        XCTAssertEqual(added.first?.id, incoming.id)
        let dropped = NotificationCenter.add(seed, notification: incoming, mutedWorkspaces: [incoming.workspaceId])
        XCTAssertNil(dropped.first { $0.id == incoming.id })
    }

    func testSimulatedIncomingNotificationLandsInTheRightGroupAndRaisesItsCount() {
        let ws = NotificationSeed.primaryWorkspaceId
        let before = NotificationCenter.unreadCount(seed, workspaceId: ws)
        let incoming = NotificationSeed.incoming(workspaceId: ws, nowMillis: now, sequence: 7)
        let updated = NotificationCenter.add(seed, notification: incoming)
        XCTAssertEqual(NotificationCenter.unreadCount(updated, workspaceId: ws), before + 1)
        let group = NotificationCenter.group(updated).first { $0.workspaceId == ws }
        XCTAssertNotNil(group)
        XCTAssertEqual(group?.notifications.first?.id, incoming.id)
    }

    func testRelativeTimeBucketsByMinuteHourDayWeek() {
        let minute: Int64 = 60_000, hour = 60 * minute, day = 24 * hour, week = 7 * day
        XCTAssertEqual(NotificationCenter.relativeTime(createdAt: now, nowMillis: now), "now")
        XCTAssertEqual(NotificationCenter.relativeTime(createdAt: now - 8 * minute, nowMillis: now), "8m")
        XCTAssertEqual(NotificationCenter.relativeTime(createdAt: now - 3 * hour, nowMillis: now), "3h")
        XCTAssertEqual(NotificationCenter.relativeTime(createdAt: now - 2 * day, nowMillis: now), "2d")
        XCTAssertEqual(NotificationCenter.relativeTime(createdAt: now - 2 * week, nowMillis: now), "2w")
    }

    func testLatestIsNullForAnEmptyGroupPayload() {
        XCTAssertTrue(NotificationCenter.group([]).isEmpty)
    }

    func testEverySeededWorkspaceIconIsNonBlankAndUniquePerWorkspace() {
        var iconsByWorkspace: [String: Set<String>] = [:]
        for n in seed {
            XCTAssertFalse(n.workspaceIcon.isEmpty)
            iconsByWorkspace[n.workspaceId, default: []].insert(n.workspaceIcon)
        }
        for (ws, icons) in iconsByWorkspace {
            XCTAssertEqual(icons.count, 1, "workspace \(ws) uses multiple icons")
        }
    }
}
