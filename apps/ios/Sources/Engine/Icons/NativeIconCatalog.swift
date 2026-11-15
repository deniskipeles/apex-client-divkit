import Foundation

/// Category grouping for the bundled native POS icon set.
public enum IconCategory: String, CaseIterable, Sendable {
    case commerce = "Commerce"
    case payment = "Payment"
    case inventory = "Inventory"
    case customer = "Customer"
    case alerts = "Alerts"
    case status = "Status"
    case time = "Time"
    case connectivity = "Connectivity"
    case device = "Device"
    case analytics = "Analytics"
    case security = "Security"
    case actions = "Actions"
    case navigation = "Navigation"
    case communication = "Communication"
    case food = "Food"
    case general = "General"

    public var label: String { rawValue }
}

/// One icon in the bundled native set.
///
/// - `name`: canonical POS-facing name used in `icons://native/<name>`.
/// - `material`: Material Symbols identifier the renderer maps to a glyph.
/// - `category`: grouping used by docs and the icon gallery.
/// - `aliases`: alternative names that resolve to this icon.
public struct NativeIcon: Sendable, Equatable {
    public let name: String
    public let material: String
    public let category: IconCategory
    public let aliases: [String]

    public init(name: String, material: String, category: IconCategory, aliases: [String] = []) {
        self.name = name; self.material = material; self.category = category; self.aliases = aliases
    }
}

/// Platform-free registry of the icons bundled with the app. Mirrors
/// `engine.icons.NativeIconCatalog` (Kotlin) so the `icons://native/<name>` scheme resolves
/// identically on iOS. Generated from the Android catalog; keep both in sync.
public enum NativeIconCatalog {
    public static let scheme = "icons://"
    public static let host = "native"

    public static let icons: [NativeIcon] = [
        NativeIcon(name: "cart", material: "ShoppingCart", category: .commerce, aliases: ["shopping_cart", "trolley"]),
        NativeIcon(name: "cart_add", material: "AddShoppingCart", category: .commerce, aliases: ["add_to_cart"]),
        NativeIcon(name: "cart_remove", material: "RemoveShoppingCart", category: .commerce, aliases: ["remove_from_cart"]),
        NativeIcon(name: "checkout", material: "ShoppingCartCheckout", category: .commerce, aliases: ["cart_checkout", "buy"]),
        NativeIcon(name: "bag", material: "ShoppingBag", category: .commerce, aliases: ["shopping_bag"]),
        NativeIcon(name: "basket", material: "ShoppingBasket", category: .commerce, aliases: ["shopping_basket"]),
        NativeIcon(name: "product", material: "Inventory2", category: .commerce, aliases: ["item", "box"]),
        NativeIcon(name: "stock", material: "Inventory", category: .commerce, aliases: ["inventory", "on_hand"]),
        NativeIcon(name: "category", material: "Category", category: .commerce, aliases: ["categories"]),
        NativeIcon(name: "tag", material: "Sell", category: .commerce, aliases: ["price_tag", "sell", "pricetag"]),
        NativeIcon(name: "label", material: "Label", category: .commerce, aliases: ["labels", "barcode_label"]),
        NativeIcon(name: "offer", material: "LocalOffer", category: .commerce, aliases: ["local_offer", "deal"]),
        NativeIcon(name: "redeem", material: "Redeem", category: .commerce, aliases: ["coupon", "gift"]),
        NativeIcon(name: "loyalty", material: "Loyalty", category: .commerce, aliases: ["rewards", "points"]),
        NativeIcon(name: "storefront", material: "Storefront", category: .commerce, aliases: ["store", "shop"]),
        NativeIcon(name: "mall", material: "StoreMallDirectory", category: .commerce, aliases: ["store_mall", "shopping_mall"]),
        NativeIcon(name: "branch", material: "Business", category: .commerce, aliases: ["store_branch"]),
        NativeIcon(name: "mall_bag", material: "LocalMall", category: .commerce, aliases: ["local_mall"]),
        NativeIcon(name: "convenience", material: "LocalConvenienceStore", category: .commerce, aliases: ["convenience_store"]),
        NativeIcon(name: "new_business", material: "AddBusiness", category: .commerce, aliases: ["add_business"]),
        NativeIcon(name: "new_order", material: "PostAdd", category: .commerce, aliases: ["post_add", "create_order"]),
        NativeIcon(name: "view_module", material: "ViewModule", category: .commerce, aliases: ["catalogue", "catalog_grid"]),
        NativeIcon(name: "production", material: "ProductionQuantityLimits", category: .commerce, aliases: ["limits", "qty"]),
        NativeIcon(name: "payment", material: "Payments", category: .payment, aliases: ["pay", "payments"]),
        NativeIcon(name: "pos", material: "PointOfSale", category: .payment, aliases: ["point_of_sale", "terminal"]),
        NativeIcon(name: "card", material: "CreditCard", category: .payment, aliases: ["credit_card", "debit_card"]),
        NativeIcon(name: "wallet", material: "AccountBalanceWallet", category: .payment, aliases: ["balance_wallet"]),
        NativeIcon(name: "cash", material: "AttachMoney", category: .payment, aliases: ["money", "dollar"]),
        NativeIcon(name: "paid", material: "Paid", category: .payment, aliases: ["paid_stamp"]),
        NativeIcon(name: "exchange", material: "CurrencyExchange", category: .payment, aliases: ["currency_exchange", "fx"]),
        NativeIcon(name: "atm", material: "LocalAtm", category: .payment, aliases: ["cash_machine"]),
        NativeIcon(name: "bank", material: "AccountBalance", category: .payment, aliases: ["account_balance"]),
        NativeIcon(name: "receipt", material: "ReceiptLong", category: .payment, aliases: ["receipt_long", "bill", "invoice"]),
        NativeIcon(name: "receipt_short", material: "RequestPage", category: .payment, aliases: []),
        NativeIcon(name: "quote", material: "RequestQuote", category: .payment, aliases: ["request_quote", "estimate"]),
        NativeIcon(name: "discount", material: "Percent", category: .payment, aliases: ["percent", "sale"]),
        NativeIcon(name: "gift_card", material: "CardGiftcard", category: .payment, aliases: ["giftcard", "card_giftcard"]),
        NativeIcon(name: "ticket", material: "ConfirmationNumber", category: .payment, aliases: ["confirmation_number"]),
        NativeIcon(name: "refund", material: "AssignmentReturn", category: .payment, aliases: ["return_payment", "refund_order"]),
        NativeIcon(name: "warehouse", material: "Warehouse", category: .inventory, aliases: ["storage", "depot"]),
        NativeIcon(name: "shipping", material: "LocalShipping", category: .inventory, aliases: ["truck", "delivery_truck"]),
        NativeIcon(name: "delivery", material: "DeliveryDining", category: .inventory, aliases: ["delivery_dining"]),
        NativeIcon(name: "factory", material: "Factory", category: .inventory, aliases: ["manufacturing"]),
        NativeIcon(name: "new_box", material: "AddBox", category: .inventory, aliases: ["add_box"]),
        NativeIcon(name: "all_inbox", material: "AllInbox", category: .inventory, aliases: ["inbox_all"]),
        NativeIcon(name: "shelves", material: "Garage", category: .inventory, aliases: ["shelf", "rack", "garage"]),
        NativeIcon(name: "moving", material: "Moving", category: .inventory, aliases: ["transfer_stock"]),
        NativeIcon(name: "scan", material: "QrCodeScanner", category: .inventory, aliases: ["scanner", "barcode", "scan_code"]),
        NativeIcon(name: "qr", material: "QrCode2", category: .inventory, aliases: ["qr_code", "qrcode"]),
        NativeIcon(name: "nfc", material: "Nfc", category: .inventory, aliases: ["tap", "contactless_pay"]),
        NativeIcon(name: "contactless", material: "Contactless", category: .inventory, aliases: ["tap_to_pay"]),
        NativeIcon(name: "sensors", material: "Sensors", category: .inventory, aliases: ["sensor"]),
        NativeIcon(name: "customer", material: "Person", category: .customer, aliases: ["person", "user", "profile"]),
        NativeIcon(name: "customers", material: "People", category: .customer, aliases: ["people", "users"]),
        NativeIcon(name: "group", material: "Groups", category: .customer, aliases: ["groups", "team"]),
        NativeIcon(name: "person_add", material: "PersonAdd", category: .customer, aliases: ["add_customer", "new_customer"]),
        NativeIcon(name: "manage_accounts", material: "ManageAccounts", category: .customer, aliases: ["account_manage"]),
        NativeIcon(name: "supervisor", material: "SupervisorAccount", category: .customer, aliases: ["supervisor_account"]),
        NativeIcon(name: "support", material: "SupportAgent", category: .customer, aliases: ["support_agent", "help_desk"]),
        NativeIcon(name: "contact_phone", material: "ContactPhone", category: .customer, aliases: ["phone_contact"]),
        NativeIcon(name: "contacts", material: "Contacts", category: .customer, aliases: ["address_book"]),
        NativeIcon(name: "assignment_ind", material: "AssignmentInd", category: .customer, aliases: ["assigned_staff"]),
        NativeIcon(name: "membership", material: "CardMembership", category: .customer, aliases: ["card_membership"]),
        NativeIcon(name: "premium", material: "WorkspacePremium", category: .customer, aliases: ["workspace_premium", "vip"]),
        NativeIcon(name: "achievement", material: "MilitaryTech", category: .customer, aliases: ["military_tech", "tier"]),
        NativeIcon(name: "trophy", material: "EmojiEvents", category: .customer, aliases: ["emoji_events", "award"]),
        NativeIcon(name: "badge", material: "Badge", category: .customer, aliases: ["staff_badge", "id_badge"]),
        NativeIcon(name: "bell", material: "Notifications", category: .alerts, aliases: ["notification", "notifications", "push"]),
        NativeIcon(name: "bell_add", material: "NotificationAdd", category: .alerts, aliases: ["notification_add"]),
        NativeIcon(name: "bell_active", material: "NotificationsActive", category: .alerts, aliases: ["notifications_active"]),
        NativeIcon(name: "bell_important", material: "NotificationImportant", category: .alerts, aliases: ["notification_important"]),
        NativeIcon(name: "campaign", material: "Campaign", category: .alerts, aliases: ["broadcast", "megaphone"]),
        NativeIcon(name: "announcement", material: "Announcement", category: .alerts, aliases: ["news"]),
        NativeIcon(name: "push_pin", material: "PushPin", category: .alerts, aliases: ["pin_message"]),
        NativeIcon(name: "mute", material: "DoNotDisturb", category: .alerts, aliases: ["do_not_disturb"]),
        NativeIcon(name: "priority", material: "PriorityHigh", category: .alerts, aliases: ["priority_high", "urgent"]),
        NativeIcon(name: "report", material: "Report", category: .alerts, aliases: ["report_flag"]),
        NativeIcon(name: "report_problem", material: "ReportProblem", category: .alerts, aliases: ["problem"]),
        NativeIcon(name: "error", material: "Error", category: .status, aliases: ["failed", "danger"]),
        NativeIcon(name: "error_outline", material: "ErrorOutline", category: .status, aliases: ["error_alt"]),
        NativeIcon(name: "warning", material: "Warning", category: .status, aliases: ["alert", "caution"]),
        NativeIcon(name: "warning_amber", material: "WarningAmber", category: .status, aliases: ["warning_alt"]),
        NativeIcon(name: "info", material: "Info", category: .status, aliases: ["information"]),
        NativeIcon(name: "help", material: "Help", category: .status, aliases: ["question"]),
        NativeIcon(name: "help_center", material: "HelpCenter", category: .status, aliases: ["support_center"]),
        NativeIcon(name: "check", material: "CheckCircle", category: .status, aliases: ["success", "ok", "checkout_ok"]),
        NativeIcon(name: "check_outline", material: "CheckCircleOutline", category: .status, aliases: ["success_alt"]),
        NativeIcon(name: "cancel", material: "Cancel", category: .status, aliases: ["cancelled", "void"]),
        NativeIcon(name: "close", material: "Close", category: .status, aliases: ["dismiss", "x"]),
        NativeIcon(name: "done", material: "Done", category: .status, aliases: ["tick"]),
        NativeIcon(name: "done_all", material: "DoneAll", category: .status, aliases: ["all_done"]),
        NativeIcon(name: "pending", material: "PendingActions", category: .status, aliases: ["pending_actions"]),
        NativeIcon(name: "task_alt", material: "TaskAlt", category: .status, aliases: ["task_complete"]),
        NativeIcon(name: "clock", material: "Schedule", category: .time, aliases: ["schedule", "time"]),
        NativeIcon(name: "hourglass", material: "HourglassEmpty", category: .time, aliases: ["waiting", "hourglass_empty"]),
        NativeIcon(name: "hourglass_full", material: "HourglassBottom", category: .time, aliases: ["hourglass_bottom"]),
        NativeIcon(name: "timer", material: "Timer", category: .time, aliases: ["stopwatch"]),
        NativeIcon(name: "calendar", material: "CalendarMonth", category: .time, aliases: ["calendar_month", "date"]),
        NativeIcon(name: "today", material: "Today", category: .time, aliases: ["today_date"]),
        NativeIcon(name: "event", material: "Event", category: .time, aliases: ["appointment"]),
        NativeIcon(name: "alarm", material: "Alarm", category: .time, aliases: ["alarm_clock"]),
        NativeIcon(name: "update", material: "Update", category: .time, aliases: ["update_time"]),
        NativeIcon(name: "history", material: "History", category: .time, aliases: ["recent", "log"]),
        NativeIcon(name: "sync", material: "Sync", category: .connectivity, aliases: ["refresh_sync"]),
        NativeIcon(name: "autorenew", material: "Autorenew", category: .connectivity, aliases: ["retry", "cycle"]),
        NativeIcon(name: "cloud", material: "CloudSync", category: .connectivity, aliases: ["cloud_sync"]),
        NativeIcon(name: "cloud_download", material: "CloudDownload", category: .connectivity, aliases: ["download_cloud"]),
        NativeIcon(name: "cloud_upload", material: "CloudUpload", category: .connectivity, aliases: ["upload_cloud"]),
        NativeIcon(name: "cloud_done", material: "CloudDone", category: .connectivity, aliases: ["cloud_ok"]),
        NativeIcon(name: "cloud_off", material: "CloudOff", category: .connectivity, aliases: ["offline", "cloud_offline"]),
        NativeIcon(name: "wifi", material: "Wifi", category: .connectivity, aliases: ["network"]),
        NativeIcon(name: "wifi_off", material: "WifiOff", category: .connectivity, aliases: ["no_network"]),
        NativeIcon(name: "bluetooth", material: "Bluetooth", category: .connectivity, aliases: ["bt"]),
        NativeIcon(name: "bluetooth_connected", material: "BluetoothConnected", category: .connectivity, aliases: ["bt_connected"]),
        NativeIcon(name: "router", material: "Router", category: .connectivity, aliases: ["gateway"]),
        NativeIcon(name: "memory", material: "Memory", category: .connectivity, aliases: ["chip"]),
        NativeIcon(name: "usb", material: "Usb", category: .connectivity, aliases: ["usb_port"]),
        NativeIcon(name: "battery", material: "BatteryFull", category: .connectivity, aliases: ["battery_full"]),
        NativeIcon(name: "battery_low", material: "BatteryAlert", category: .connectivity, aliases: ["battery_alert"]),
        NativeIcon(name: "power", material: "Power", category: .connectivity, aliases: ["power_on"]),
        NativeIcon(name: "cable", material: "Cable", category: .connectivity, aliases: ["wired"]),
        NativeIcon(name: "lan", material: "Lan", category: .connectivity, aliases: ["ethernet"]),
        NativeIcon(name: "printer", material: "Print", category: .device, aliases: ["print", "receipt_printer"]),
        NativeIcon(name: "print_off", material: "PrintDisabled", category: .device, aliases: ["printer_off"]),
        NativeIcon(name: "scanner_device", material: "DocumentScanner", category: .device, aliases: ["scanner_hw"]),
        NativeIcon(name: "fax", material: "Fax", category: .device, aliases: ["fax_machine"]),
        NativeIcon(name: "camera", material: "PhotoCamera", category: .device, aliases: ["photo_camera"]),
        NativeIcon(name: "photo", material: "Image", category: .device, aliases: ["image", "picture"]),
        NativeIcon(name: "photo_library", material: "PhotoLibrary", category: .device, aliases: ["gallery", "photos"]),
        NativeIcon(name: "collections", material: "Collections", category: .device, aliases: ["media"]),
        NativeIcon(name: "video", material: "Videocam", category: .device, aliases: ["videocam"]),
        NativeIcon(name: "movie", material: "Movie", category: .device, aliases: ["film"]),
        NativeIcon(name: "tv", material: "Tv", category: .device, aliases: ["display"]),
        NativeIcon(name: "smart_display", material: "SmartDisplay", category: .device, aliases: ["kiosk"]),
        NativeIcon(name: "phone", material: "PhoneAndroid", category: .device, aliases: ["mobile", "smartphone"]),
        NativeIcon(name: "tablet", material: "TabletMac", category: .device, aliases: ["tablet_mac"]),
        NativeIcon(name: "laptop", material: "LaptopMac", category: .device, aliases: ["laptop_mac", "computer"]),
        NativeIcon(name: "desktop", material: "DesktopWindows", category: .device, aliases: ["desktop_windows", "pc"]),
        NativeIcon(name: "watch", material: "Watch", category: .device, aliases: ["smartwatch"]),
        NativeIcon(name: "devices", material: "Devices", category: .device, aliases: ["multi_device"]),
        NativeIcon(name: "volume", material: "VolumeUp", category: .device, aliases: ["audio", "sound"]),
        NativeIcon(name: "volume_off", material: "VolumeOff", category: .device, aliases: ["mute_audio"]),
        NativeIcon(name: "mic", material: "Mic", category: .device, aliases: ["microphone"]),
        NativeIcon(name: "headphones", material: "Headphones", category: .device, aliases: ["headset"]),
        NativeIcon(name: "speaker", material: "Speaker", category: .device, aliases: ["speaker_audio"]),
        NativeIcon(name: "music", material: "MusicNote", category: .device, aliases: ["music_note", "beeper", "tone"]),
        NativeIcon(name: "haptics", material: "Vibration", category: .device, aliases: ["vibration", "buzz"]),
        NativeIcon(name: "dashboard", material: "Dashboard", category: .analytics, aliases: ["overview"]),
        NativeIcon(name: "insights", material: "Insights", category: .analytics, aliases: ["insight"]),
        NativeIcon(name: "bar_chart", material: "BarChart", category: .analytics, aliases: ["chart_bar", "sales_chart"]),
        NativeIcon(name: "pie_chart", material: "PieChart", category: .analytics, aliases: ["chart_pie"]),
        NativeIcon(name: "trending_up", material: "TrendingUp", category: .analytics, aliases: ["growth"]),
        NativeIcon(name: "trending_down", material: "TrendingDown", category: .analytics, aliases: ["decline"]),
        NativeIcon(name: "line_chart", material: "ShowChart", category: .analytics, aliases: ["show_chart"]),
        NativeIcon(name: "stacked_chart", material: "StackedLineChart", category: .analytics, aliases: ["stacked_line_chart"]),
        NativeIcon(name: "query_stats", material: "QueryStats", category: .analytics, aliases: []),
        NativeIcon(name: "analytics", material: "Analytics", category: .analytics, aliases: ["metrics"]),
        NativeIcon(name: "assessment", material: "Assessment", category: .analytics, aliases: ["report_card"]),
        NativeIcon(name: "leaderboard", material: "Leaderboard", category: .analytics, aliases: ["ranking"]),
        NativeIcon(name: "speed", material: "Speed", category: .analytics, aliases: ["performance"]),
        NativeIcon(name: "health", material: "MonitorHeart", category: .analytics, aliases: ["monitor_heart", "health_check"]),
        NativeIcon(name: "security", material: "Security", category: .security, aliases: ["sec"]),
        NativeIcon(name: "shield", material: "Shield", category: .security, aliases: ["e2ee", "protected"]),
        NativeIcon(name: "verified", material: "VerifiedUser", category: .security, aliases: ["verified_user"]),
        NativeIcon(name: "gpp_good", material: "GppGood", category: .security, aliases: ["compliance"]),
        NativeIcon(name: "lock", material: "Lock", category: .security, aliases: ["locked", "secure"]),
        NativeIcon(name: "unlock", material: "LockOpen", category: .security, aliases: ["lock_open", "unlocked"]),
        NativeIcon(name: "key", material: "Key", category: .security, aliases: ["keys"]),
        NativeIcon(name: "vpn_key", material: "VpnKey", category: .security, aliases: ["token"]),
        NativeIcon(name: "password", material: "Password", category: .security, aliases: ["passcode", "pin"]),
        NativeIcon(name: "fingerprint", material: "Fingerprint", category: .security, aliases: ["biometrics", "biometric"]),
        NativeIcon(name: "face_id", material: "Face", category: .security, aliases: ["face", "facial"]),
        NativeIcon(name: "add", material: "AddCircle", category: .actions, aliases: ["plus", "new"]),
        NativeIcon(name: "remove", material: "RemoveCircle", category: .actions, aliases: ["minus"]),
        NativeIcon(name: "edit", material: "Edit", category: .actions, aliases: ["pencil", "modify"]),
        NativeIcon(name: "edit_note", material: "EditNote", category: .actions, aliases: []),
        NativeIcon(name: "note_add", material: "NoteAdd", category: .actions, aliases: []),
        NativeIcon(name: "delete", material: "DeleteOutline", category: .actions, aliases: ["trash", "remove_item"]),
        NativeIcon(name: "delete_filled", material: "DeleteForever", category: .actions, aliases: ["trash_filled"]),
        NativeIcon(name: "copy", material: "ContentCopy", category: .actions, aliases: ["content_copy", "duplicate"]),
        NativeIcon(name: "cut", material: "ContentCut", category: .actions, aliases: ["content_cut", "scissors"]),
        NativeIcon(name: "crop", material: "Crop", category: .actions, aliases: ["crop_image"]),
        NativeIcon(name: "paste", material: "ContentPaste", category: .actions, aliases: ["clipboard", "content_paste"]),
        NativeIcon(name: "clear", material: "Clear", category: .actions, aliases: ["reset"]),
        NativeIcon(name: "search", material: "Search", category: .actions, aliases: ["find", "magnify", "ocr", "read_text", "text"]),
        NativeIcon(name: "filter", material: "FilterList", category: .actions, aliases: ["filter_list"]),
        NativeIcon(name: "filter_alt", material: "FilterAlt", category: .actions, aliases: []),
        NativeIcon(name: "sort", material: "Sort", category: .actions, aliases: ["sort_by"]),
        NativeIcon(name: "refresh", material: "Refresh", category: .actions, aliases: ["reload"]),
        NativeIcon(name: "upload", material: "Upload", category: .actions, aliases: ["upload_file"]),
        NativeIcon(name: "download", material: "Download", category: .actions, aliases: ["download_file"]),
        NativeIcon(name: "file_download", material: "FileDownload", category: .actions, aliases: []),
        NativeIcon(name: "file_upload", material: "FileUpload", category: .actions, aliases: []),
        NativeIcon(name: "folder", material: "Folder", category: .actions, aliases: ["directory"]),
        NativeIcon(name: "folder_open", material: "FolderOpen", category: .actions, aliases: ["open_folder"]),
        NativeIcon(name: "new_folder", material: "CreateNewFolder", category: .actions, aliases: ["create_folder"]),
        NativeIcon(name: "file", material: "InsertDriveFile", category: .actions, aliases: ["document", "doc"]),
        NativeIcon(name: "note", material: "Note", category: .actions, aliases: ["memo"]),
        NativeIcon(name: "article", material: "Article", category: .actions, aliases: ["page"]),
        NativeIcon(name: "description", material: "Description", category: .actions, aliases: ["doc_text"]),
        NativeIcon(name: "share", material: "Share", category: .actions, aliases: ["send_to"]),
        NativeIcon(name: "send", material: "Send", category: .actions, aliases: ["submit"]),
        NativeIcon(name: "home", material: "Home", category: .navigation, aliases: ["hub", "start"]),
        NativeIcon(name: "menu", material: "Menu", category: .navigation, aliases: ["hamburger"]),
        NativeIcon(name: "more", material: "MoreVert", category: .navigation, aliases: ["overflow", "more_vert"]),
        NativeIcon(name: "more_horizontal", material: "MoreHoriz", category: .navigation, aliases: ["more_horiz"]),
        NativeIcon(name: "apps", material: "Apps", category: .navigation, aliases: ["app_grid"]),
        NativeIcon(name: "grid", material: "GridView", category: .navigation, aliases: ["grid_view", "tiles"]),
        NativeIcon(name: "list", material: "ViewList", category: .navigation, aliases: ["view_list"]),
        NativeIcon(name: "columns", material: "ViewColumn", category: .navigation, aliases: ["view_column"]),
        NativeIcon(name: "bulleted_list", material: "FormatListBulleted", category: .navigation, aliases: ["list_bullets"]),
        NativeIcon(name: "checklist", material: "Checklist", category: .navigation, aliases: []),
        NativeIcon(name: "fact_check", material: "FactCheck", category: .navigation, aliases: []),
        NativeIcon(name: "assignment", material: "Assignment", category: .navigation, aliases: []),
        NativeIcon(name: "assignment_done", material: "AssignmentTurnedIn", category: .navigation, aliases: ["assignment_turned_in"]),
        NativeIcon(name: "rule", material: "Rule", category: .navigation, aliases: ["rule_check"]),
        NativeIcon(name: "location", material: "LocationOn", category: .navigation, aliases: ["place", "pin_drop"]),
        NativeIcon(name: "map", material: "Map", category: .navigation, aliases: ["maps"]),
        NativeIcon(name: "navigation", material: "Navigation", category: .navigation, aliases: ["near_me"]),
        NativeIcon(name: "my_location", material: "MyLocation", category: .navigation, aliases: []),
        NativeIcon(name: "explore", material: "Explore", category: .navigation, aliases: ["compass"]),
        NativeIcon(name: "language", material: "Language", category: .navigation, aliases: ["locale", "globe"]),
        NativeIcon(name: "translate", material: "Translate", category: .navigation, aliases: ["language_translate"]),
        NativeIcon(name: "public", material: "Public", category: .navigation, aliases: ["world", "global"]),
        NativeIcon(name: "directions", material: "DirectionsRun", category: .navigation, aliases: ["directions_run"]),
        NativeIcon(name: "walk", material: "DirectionsWalk", category: .navigation, aliases: ["directions_walk"]),
        NativeIcon(name: "car", material: "DirectionsCar", category: .navigation, aliases: ["directions_car"]),
        NativeIcon(name: "bus", material: "DirectionsBus", category: .navigation, aliases: ["directions_bus"]),
        NativeIcon(name: "taxi", material: "LocalTaxi", category: .navigation, aliases: ["local_taxi"]),
        NativeIcon(name: "parking", material: "LocalParking", category: .navigation, aliases: ["local_parking"]),
        NativeIcon(name: "fuel", material: "LocalGasStation", category: .navigation, aliases: ["local_gas_station"]),
        NativeIcon(name: "ev_charge", material: "EvStation", category: .navigation, aliases: ["ev_station"]),
        NativeIcon(name: "call", material: "Call", category: .communication, aliases: ["phone_call"]),
        NativeIcon(name: "email", material: "Email", category: .communication, aliases: ["mail"]),
        NativeIcon(name: "message", material: "Message", category: .communication, aliases: ["sms"]),
        NativeIcon(name: "chat", material: "Chat", category: .communication, aliases: ["chat_bubble"]),
        NativeIcon(name: "forum", material: "Forum", category: .communication, aliases: ["forum_discussion"]),
        NativeIcon(name: "comment", material: "Comment", category: .communication, aliases: ["comments"]),
        NativeIcon(name: "question_answer", material: "QuestionAnswer", category: .communication, aliases: ["qa"]),
        NativeIcon(name: "feedback", material: "Feedback", category: .communication, aliases: ["review_feedback"]),
        NativeIcon(name: "reviews", material: "Reviews", category: .communication, aliases: ["reviews_list"]),
        NativeIcon(name: "rate_review", material: "RateReview", category: .communication, aliases: []),
        NativeIcon(name: "thumbs_up", material: "ThumbUp", category: .communication, aliases: ["like"]),
        NativeIcon(name: "thumbs_down", material: "ThumbDown", category: .communication, aliases: ["dislike"]),
        NativeIcon(name: "handshake", material: "Handshake", category: .communication, aliases: ["deal_done", "agreement"]),
        NativeIcon(name: "restaurant", material: "Restaurant", category: .food, aliases: []),
        NativeIcon(name: "fastfood", material: "Fastfood", category: .food, aliases: ["fast_food", "burger"]),
        NativeIcon(name: "cafe", material: "LocalCafe", category: .food, aliases: ["local_cafe", "coffee"]),
        NativeIcon(name: "dining", material: "LocalDining", category: .food, aliases: ["local_dining"]),
        NativeIcon(name: "drink", material: "LocalDrink", category: .food, aliases: ["local_drink", "beverage"]),
        NativeIcon(name: "pizza", material: "LocalPizza", category: .food, aliases: ["local_pizza"]),
        NativeIcon(name: "bakery", material: "BakeryDining", category: .food, aliases: ["bakery_dining"]),
        NativeIcon(name: "liquor", material: "Liquor", category: .food, aliases: ["bar", "alcohol"]),
        NativeIcon(name: "pharmacy", material: "LocalPharmacy", category: .food, aliases: ["local_pharmacy", "drugstore"]),
        NativeIcon(name: "florist", material: "LocalFlorist", category: .food, aliases: ["local_florist", "flowers"]),
        NativeIcon(name: "pets", material: "Pets", category: .food, aliases: ["pet"]),
        NativeIcon(name: "toys", material: "Toys", category: .food, aliases: ["toy"]),
        NativeIcon(name: "child_care", material: "ChildCare", category: .food, aliases: ["childcare", "baby"]),
        NativeIcon(name: "celebration", material: "Celebration", category: .food, aliases: ["party"]),
        NativeIcon(name: "cake", material: "Cake", category: .food, aliases: ["birthday"]),
        NativeIcon(name: "settings", material: "Settings", category: .general, aliases: ["config", "gear"]),
        NativeIcon(name: "tune", material: "Tune", category: .general, aliases: ["adjust", "sliders"]),
        NativeIcon(name: "build", material: "Build", category: .general, aliases: ["wrench", "tools"]),
        NativeIcon(name: "construction", material: "Construction", category: .general, aliases: ["maintenance"]),
        NativeIcon(name: "engineering", material: "Engineering", category: .general, aliases: ["engineer"]),
        NativeIcon(name: "handyman", material: "Handyman", category: .general, aliases: ["repair"]),
        NativeIcon(name: "precision", material: "PrecisionManufacturing", category: .general, aliases: ["machine"]),
        NativeIcon(name: "help_outline", material: "LiveHelp", category: .general, aliases: ["live_help"]),
        NativeIcon(name: "favorite", material: "Favorite", category: .general, aliases: ["heart", "like_filled"]),
        NativeIcon(name: "bookmark", material: "Bookmark", category: .general, aliases: ["saved"]),
        NativeIcon(name: "flag", material: "Flag", category: .general, aliases: ["report_flag_alt"]),
        NativeIcon(name: "star", material: "Star", category: .general, aliases: ["favourite", "rating"]),
        NativeIcon(name: "stars", material: "Stars", category: .general, aliases: ["featured"]),
        NativeIcon(name: "brush", material: "Brush", category: .general, aliases: ["paint_brush"]),
        NativeIcon(name: "palette", material: "Palette", category: .general, aliases: ["colors", "theme"]),
        NativeIcon(name: "format_paint", material: "FormatPaint", category: .general, aliases: ["fill_color"]),
        NativeIcon(name: "calculate", material: "Calculate", category: .general, aliases: ["calculator", "math"]),
        NativeIcon(name: "functions", material: "Functions", category: .general, aliases: ["formula"]),
        NativeIcon(name: "numbers", material: "Numbers", category: .general, aliases: ["digits"]),
        NativeIcon(name: "summarize", material: "Summarize", category: .general, aliases: ["summary", "stats_summary"]),
        NativeIcon(name: "books", material: "MenuBook", category: .general, aliases: ["menu_book", "catalog_book"]),
        NativeIcon(name: "book", material: "Book", category: .general, aliases: ["manual"]),
        NativeIcon(name: "import_contacts", material: "ImportContacts", category: .general, aliases: ["contacts_book"]),
        NativeIcon(name: "school", material: "School", category: .general, aliases: ["training", "education"]),
        NativeIcon(name: "translate_alt", material: "GTranslate", category: .general, aliases: ["g_translate"]),
    ]

    private static let byName: [String: NativeIcon] = {
        var m: [String: NativeIcon] = [:]
        for icon in icons {
            m[icon.name] = icon
            for alias in icon.aliases { m[alias] = icon }
        }
        return m
    }()
    private static let byMaterial: [String: NativeIcon] = Dictionary(icons.map { ($0.material, $0) }, uniquingKeysWith: { a, _ in a })
    private static let byMaterialLower: [String: NativeIcon] = Dictionary(icons.map { ($0.material.lowercased(), $0) }, uniquingKeysWith: { a, _ in a })

    public static var size: Int { icons.count }
    public static var names: [String] { icons.map(\.name) }
    public static var allKeys: Set<String> { Set(byName.keys) }
    public static var categories: [IconCategory] { IconCategory.allCases }
    public static func byCategory(_ category: IconCategory) -> [NativeIcon] { icons.filter { $0.category == category } }
    public static func find(_ nameOrUri: String) -> NativeIcon? {
        guard let key = normalize(nameOrUri) else { return nil }
        return byName[key]
    }
    public static func findByMaterial(_ material: String) -> NativeIcon? { byMaterial[material] }
    public static func isKnown(_ nameOrUri: String) -> Bool { find(nameOrUri) != nil }
    public static func canonicalName(_ nameOrUri: String) -> String? { find(nameOrUri)?.name }
    public static func materialFor(_ nameOrUri: String) -> String? { find(nameOrUri)?.material }

    /// Extracts the icon key from a reference. Accepts `icons://native/cart`,
    /// `icons://native/cart?variant=filled`, `icons://cart`, and bare names. Returns nil for
    /// an empty reference or a different scheme (e.g. `https://`).
    public static func normalize(_ reference: String) -> String? {
        var ref = reference.trimmingCharacters(in: .whitespaces)
        if ref.isEmpty { return nil }
        if ref.lowercased().hasPrefix(scheme) {
            ref = String(ref.dropFirst(scheme.count))
            ref = ref.components(separatedBy: "?").first ?? ref
            ref = ref.components(separatedBy: "#").first ?? ref
            let segments = ref.split(separator: "/").map(String.init).filter { !$0.isEmpty }
            if segments.isEmpty { return nil }
            if segments[0].lowercased() == host {
                ref = segments.dropFirst().joined(separator: "/")
            } else {
                ref = segments.joined(separator: "/")
            }
        } else if ref.contains("://") {
            return nil
        }
        ref = ref.trimmingCharacters(in: .whitespaces)
        if ref.isEmpty { return nil }
        let lower = ref.lowercased()
        if let hit = byName[lower] { return hit.name }
        if let hit = byMaterialLower[lower] { return hit.name }
        return lower
    }

    public static func isIconUri(_ reference: String) -> Bool {
        reference.trimmingCharacters(in: .whitespaces).lowercased().hasPrefix(scheme)
    }

    public static func uriFor(_ name: String) -> String { "\(scheme)\(host)/\(name)" }
}
