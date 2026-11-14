package engine.icons

/**
 * Category grouping for the bundled native POS icon set.
 */
enum class IconCategory(val label: String) {
    COMMERCE("Commerce"),
    PAYMENT("Payment"),
    INVENTORY("Inventory"),
    CUSTOMER("Customer"),
    ALERTS("Alerts"),
    STATUS("Status"),
    TIME("Time"),
    CONNECTIVITY("Connectivity"),
    DEVICE("Device"),
    ANALYTICS("Analytics"),
    SECURITY("Security"),
    ACTIONS("Actions"),
    NAVIGATION("Navigation"),
    COMMUNICATION("Communication"),
    FOOD("Food"),
    GENERAL("General")
}

/**
 * One icon in the bundled native set.
 *
 * @param name canonical POS-facing name used in `icons://native/<name>`.
 * @param material Material Symbols identifier the renderer maps to an ImageVector.
 * @param category grouping used by docs and the icon gallery.
 * @param aliases alternative names that resolve to this icon.
 */
data class NativeIcon(
    val name: String,
    val material: String,
    val category: IconCategory,
    val aliases: List<String> = emptyList()
)

/**
 * Android-free registry of the icons bundled with the app.
 *
 * The set is deliberately curated (rather than every Material icon) so the scheme stays
 * predictable and the shipped vector payload stays small. Icons are referenced from DivKit
 * with `icons://native/<name>` (the `icons://<name>` and bare `<name>` forms are also accepted).
 *
 * This object touches no Android or Compose type, so it is unit-testable on the JVM.
 */
object NativeIconCatalog {

    /** URI scheme that references a bundled native icon. */
    const val SCHEME = "icons://"

    /** URI host for the bundled icon set. */
    const val HOST = "native"

    val icons: List<NativeIcon> = listOf(
        NativeIcon("cart", "ShoppingCart", IconCategory.COMMERCE, listOf("shopping_cart", "trolley")),
        NativeIcon("cart_add", "AddShoppingCart", IconCategory.COMMERCE, listOf("add_to_cart")),
        NativeIcon("cart_remove", "RemoveShoppingCart", IconCategory.COMMERCE, listOf("remove_from_cart")),
        NativeIcon("checkout", "ShoppingCartCheckout", IconCategory.COMMERCE, listOf("cart_checkout", "buy")),
        NativeIcon("bag", "ShoppingBag", IconCategory.COMMERCE, listOf("shopping_bag")),
        NativeIcon("basket", "ShoppingBasket", IconCategory.COMMERCE, listOf("shopping_basket")),
        NativeIcon("product", "Inventory2", IconCategory.COMMERCE, listOf("item", "box")),
        NativeIcon("stock", "Inventory", IconCategory.COMMERCE, listOf("inventory", "on_hand")),
        NativeIcon("category", "Category", IconCategory.COMMERCE, listOf("categories")),
        NativeIcon("tag", "Sell", IconCategory.COMMERCE, listOf("price_tag", "sell", "pricetag")),
        NativeIcon("label", "Label", IconCategory.COMMERCE, listOf("labels", "barcode_label")),
        NativeIcon("offer", "LocalOffer", IconCategory.COMMERCE, listOf("local_offer", "deal")),
        NativeIcon("redeem", "Redeem", IconCategory.COMMERCE, listOf("coupon", "gift")),
        NativeIcon("loyalty", "Loyalty", IconCategory.COMMERCE, listOf("rewards", "points")),
        NativeIcon("storefront", "Storefront", IconCategory.COMMERCE, listOf("store", "shop")),
        NativeIcon("mall", "StoreMallDirectory", IconCategory.COMMERCE, listOf("store_mall", "shopping_mall")),
        NativeIcon("branch", "Business", IconCategory.COMMERCE, listOf("store_branch")),
        NativeIcon("mall_bag", "LocalMall", IconCategory.COMMERCE, listOf("local_mall")),
        NativeIcon("convenience", "LocalConvenienceStore", IconCategory.COMMERCE, listOf("convenience_store")),
        NativeIcon("new_business", "AddBusiness", IconCategory.COMMERCE, listOf("add_business")),
        NativeIcon("new_order", "PostAdd", IconCategory.COMMERCE, listOf("post_add", "create_order")),
        NativeIcon("view_module", "ViewModule", IconCategory.COMMERCE, listOf("catalogue", "catalog_grid")),
        NativeIcon("production", "ProductionQuantityLimits", IconCategory.COMMERCE, listOf("limits", "qty")),
        NativeIcon("payment", "Payments", IconCategory.PAYMENT, listOf("pay", "payments")),
        NativeIcon("pos", "PointOfSale", IconCategory.PAYMENT, listOf("point_of_sale", "terminal")),
        NativeIcon("card", "CreditCard", IconCategory.PAYMENT, listOf("credit_card", "debit_card")),
        NativeIcon("wallet", "AccountBalanceWallet", IconCategory.PAYMENT, listOf("balance_wallet")),
        NativeIcon("cash", "AttachMoney", IconCategory.PAYMENT, listOf("money", "dollar")),
        NativeIcon("paid", "Paid", IconCategory.PAYMENT, listOf("paid_stamp")),
        NativeIcon("exchange", "CurrencyExchange", IconCategory.PAYMENT, listOf("currency_exchange", "fx")),
        NativeIcon("atm", "LocalAtm", IconCategory.PAYMENT, listOf("cash_machine")),
        NativeIcon("bank", "AccountBalance", IconCategory.PAYMENT, listOf("account_balance")),
        NativeIcon("receipt", "ReceiptLong", IconCategory.PAYMENT, listOf("receipt_long", "bill", "invoice")),
        NativeIcon("receipt_short", "RequestPage", IconCategory.PAYMENT, listOf()),
        NativeIcon("quote", "RequestQuote", IconCategory.PAYMENT, listOf("request_quote", "estimate")),
        NativeIcon("discount", "Percent", IconCategory.PAYMENT, listOf("percent", "sale")),
        NativeIcon("gift_card", "CardGiftcard", IconCategory.PAYMENT, listOf("giftcard", "card_giftcard")),
        NativeIcon("ticket", "ConfirmationNumber", IconCategory.PAYMENT, listOf("confirmation_number")),
        NativeIcon("refund", "AssignmentReturn", IconCategory.PAYMENT, listOf("return_payment", "refund_order")),
        NativeIcon("warehouse", "Warehouse", IconCategory.INVENTORY, listOf("storage", "depot")),
        NativeIcon("shipping", "LocalShipping", IconCategory.INVENTORY, listOf("truck", "delivery_truck")),
        NativeIcon("delivery", "DeliveryDining", IconCategory.INVENTORY, listOf("delivery_dining")),
        NativeIcon("factory", "Factory", IconCategory.INVENTORY, listOf("manufacturing")),
        NativeIcon("new_box", "AddBox", IconCategory.INVENTORY, listOf("add_box")),
        NativeIcon("all_inbox", "AllInbox", IconCategory.INVENTORY, listOf("inbox_all")),
        NativeIcon("shelves", "Garage", IconCategory.INVENTORY, listOf("shelf", "rack", "garage")),
        NativeIcon("moving", "Moving", IconCategory.INVENTORY, listOf("transfer_stock")),
        NativeIcon("scan", "QrCodeScanner", IconCategory.INVENTORY, listOf("scanner", "barcode", "scan_code")),
        NativeIcon("qr", "QrCode2", IconCategory.INVENTORY, listOf("qr_code", "qrcode")),
        NativeIcon("nfc", "Nfc", IconCategory.INVENTORY, listOf("tap", "contactless_pay")),
        NativeIcon("contactless", "Contactless", IconCategory.INVENTORY, listOf("tap_to_pay")),
        NativeIcon("sensors", "Sensors", IconCategory.INVENTORY, listOf("sensor")),
        NativeIcon("customer", "Person", IconCategory.CUSTOMER, listOf("person", "user", "profile")),
        NativeIcon("customers", "People", IconCategory.CUSTOMER, listOf("people", "users")),
        NativeIcon("group", "Groups", IconCategory.CUSTOMER, listOf("groups", "team")),
        NativeIcon("person_add", "PersonAdd", IconCategory.CUSTOMER, listOf("add_customer", "new_customer")),
        NativeIcon("manage_accounts", "ManageAccounts", IconCategory.CUSTOMER, listOf("account_manage")),
        NativeIcon("supervisor", "SupervisorAccount", IconCategory.CUSTOMER, listOf("supervisor_account")),
        NativeIcon("support", "SupportAgent", IconCategory.CUSTOMER, listOf("support_agent", "help_desk")),
        NativeIcon("contact_phone", "ContactPhone", IconCategory.CUSTOMER, listOf("phone_contact")),
        NativeIcon("contacts", "Contacts", IconCategory.CUSTOMER, listOf("address_book")),
        NativeIcon("assignment_ind", "AssignmentInd", IconCategory.CUSTOMER, listOf("assigned_staff")),
        NativeIcon("membership", "CardMembership", IconCategory.CUSTOMER, listOf("card_membership")),
        NativeIcon("premium", "WorkspacePremium", IconCategory.CUSTOMER, listOf("workspace_premium", "vip")),
        NativeIcon("achievement", "MilitaryTech", IconCategory.CUSTOMER, listOf("military_tech", "tier")),
        NativeIcon("trophy", "EmojiEvents", IconCategory.CUSTOMER, listOf("emoji_events", "award")),
        NativeIcon("badge", "Badge", IconCategory.CUSTOMER, listOf("staff_badge", "id_badge")),
        NativeIcon("bell", "Notifications", IconCategory.ALERTS, listOf("notification", "notifications", "push")),
        NativeIcon("bell_add", "NotificationAdd", IconCategory.ALERTS, listOf("notification_add")),
        NativeIcon("bell_active", "NotificationsActive", IconCategory.ALERTS, listOf("notifications_active")),
        NativeIcon("bell_important", "NotificationImportant", IconCategory.ALERTS, listOf("notification_important")),
        NativeIcon("campaign", "Campaign", IconCategory.ALERTS, listOf("broadcast", "megaphone")),
        NativeIcon("announcement", "Announcement", IconCategory.ALERTS, listOf("news")),
        NativeIcon("push_pin", "PushPin", IconCategory.ALERTS, listOf("pin_message")),
        NativeIcon("mute", "DoNotDisturb", IconCategory.ALERTS, listOf("do_not_disturb")),
        NativeIcon("priority", "PriorityHigh", IconCategory.ALERTS, listOf("priority_high", "urgent")),
        NativeIcon("report", "Report", IconCategory.ALERTS, listOf("report_flag")),
        NativeIcon("report_problem", "ReportProblem", IconCategory.ALERTS, listOf("problem")),
        NativeIcon("error", "Error", IconCategory.STATUS, listOf("failed", "danger")),
        NativeIcon("error_outline", "ErrorOutline", IconCategory.STATUS, listOf("error_alt")),
        NativeIcon("warning", "Warning", IconCategory.STATUS, listOf("alert", "caution")),
        NativeIcon("warning_amber", "WarningAmber", IconCategory.STATUS, listOf("warning_alt")),
        NativeIcon("info", "Info", IconCategory.STATUS, listOf("information")),
        NativeIcon("help", "Help", IconCategory.STATUS, listOf("question")),
        NativeIcon("help_center", "HelpCenter", IconCategory.STATUS, listOf("support_center")),
        NativeIcon("check", "CheckCircle", IconCategory.STATUS, listOf("success", "ok", "checkout_ok")),
        NativeIcon("check_outline", "CheckCircleOutline", IconCategory.STATUS, listOf("success_alt")),
        NativeIcon("cancel", "Cancel", IconCategory.STATUS, listOf("cancelled", "void")),
        NativeIcon("close", "Close", IconCategory.STATUS, listOf("dismiss", "x")),
        NativeIcon("done", "Done", IconCategory.STATUS, listOf("tick")),
        NativeIcon("done_all", "DoneAll", IconCategory.STATUS, listOf("all_done")),
        NativeIcon("pending", "PendingActions", IconCategory.STATUS, listOf("pending_actions")),
        NativeIcon("task_alt", "TaskAlt", IconCategory.STATUS, listOf("task_complete")),
        NativeIcon("clock", "Schedule", IconCategory.TIME, listOf("schedule", "time")),
        NativeIcon("hourglass", "HourglassEmpty", IconCategory.TIME, listOf("waiting", "hourglass_empty")),
        NativeIcon("hourglass_full", "HourglassBottom", IconCategory.TIME, listOf("hourglass_bottom")),
        NativeIcon("timer", "Timer", IconCategory.TIME, listOf("stopwatch")),
        NativeIcon("calendar", "CalendarMonth", IconCategory.TIME, listOf("calendar_month", "date")),
        NativeIcon("today", "Today", IconCategory.TIME, listOf("today_date")),
        NativeIcon("event", "Event", IconCategory.TIME, listOf("appointment")),
        NativeIcon("alarm", "Alarm", IconCategory.TIME, listOf("alarm_clock")),
        NativeIcon("update", "Update", IconCategory.TIME, listOf("update_time")),
        NativeIcon("history", "History", IconCategory.TIME, listOf("recent", "log")),
        NativeIcon("sync", "Sync", IconCategory.CONNECTIVITY, listOf("refresh_sync")),
        NativeIcon("autorenew", "Autorenew", IconCategory.CONNECTIVITY, listOf("retry", "cycle")),
        NativeIcon("cloud", "CloudSync", IconCategory.CONNECTIVITY, listOf("cloud_sync")),
        NativeIcon("cloud_download", "CloudDownload", IconCategory.CONNECTIVITY, listOf("download_cloud")),
        NativeIcon("cloud_upload", "CloudUpload", IconCategory.CONNECTIVITY, listOf("upload_cloud")),
        NativeIcon("cloud_done", "CloudDone", IconCategory.CONNECTIVITY, listOf("cloud_ok")),
        NativeIcon("cloud_off", "CloudOff", IconCategory.CONNECTIVITY, listOf("offline", "cloud_offline")),
        NativeIcon("wifi", "Wifi", IconCategory.CONNECTIVITY, listOf("network")),
        NativeIcon("wifi_off", "WifiOff", IconCategory.CONNECTIVITY, listOf("no_network")),
        NativeIcon("bluetooth", "Bluetooth", IconCategory.CONNECTIVITY, listOf("bt")),
        NativeIcon("bluetooth_connected", "BluetoothConnected", IconCategory.CONNECTIVITY, listOf("bt_connected")),
        NativeIcon("router", "Router", IconCategory.CONNECTIVITY, listOf("gateway")),
        NativeIcon("memory", "Memory", IconCategory.CONNECTIVITY, listOf("chip")),
        NativeIcon("usb", "Usb", IconCategory.CONNECTIVITY, listOf("usb_port")),
        NativeIcon("battery", "BatteryFull", IconCategory.CONNECTIVITY, listOf("battery_full")),
        NativeIcon("battery_low", "BatteryAlert", IconCategory.CONNECTIVITY, listOf("battery_alert")),
        NativeIcon("power", "Power", IconCategory.CONNECTIVITY, listOf("power_on")),
        NativeIcon("cable", "Cable", IconCategory.CONNECTIVITY, listOf("wired")),
        NativeIcon("lan", "Lan", IconCategory.CONNECTIVITY, listOf("ethernet")),
        NativeIcon("printer", "Print", IconCategory.DEVICE, listOf("print", "receipt_printer")),
        NativeIcon("print_off", "PrintDisabled", IconCategory.DEVICE, listOf("printer_off")),
        NativeIcon("scanner_device", "DocumentScanner", IconCategory.DEVICE, listOf("scanner_hw")),
        NativeIcon("fax", "Fax", IconCategory.DEVICE, listOf("fax_machine")),
        NativeIcon("camera", "PhotoCamera", IconCategory.DEVICE, listOf("photo_camera")),
        NativeIcon("photo", "Image", IconCategory.DEVICE, listOf("image", "picture")),
        NativeIcon("photo_library", "PhotoLibrary", IconCategory.DEVICE, listOf("gallery", "photos")),
        NativeIcon("collections", "Collections", IconCategory.DEVICE, listOf("media")),
        NativeIcon("video", "Videocam", IconCategory.DEVICE, listOf("videocam")),
        NativeIcon("movie", "Movie", IconCategory.DEVICE, listOf("film")),
        NativeIcon("tv", "Tv", IconCategory.DEVICE, listOf("display")),
        NativeIcon("smart_display", "SmartDisplay", IconCategory.DEVICE, listOf("kiosk")),
        NativeIcon("phone", "PhoneAndroid", IconCategory.DEVICE, listOf("mobile", "smartphone")),
        NativeIcon("tablet", "TabletMac", IconCategory.DEVICE, listOf("tablet_mac")),
        NativeIcon("laptop", "LaptopMac", IconCategory.DEVICE, listOf("laptop_mac", "computer")),
        NativeIcon("desktop", "DesktopWindows", IconCategory.DEVICE, listOf("desktop_windows", "pc")),
        NativeIcon("watch", "Watch", IconCategory.DEVICE, listOf("smartwatch")),
        NativeIcon("devices", "Devices", IconCategory.DEVICE, listOf("multi_device")),
        NativeIcon("volume", "VolumeUp", IconCategory.DEVICE, listOf("audio", "sound")),
        NativeIcon("volume_off", "VolumeOff", IconCategory.DEVICE, listOf("mute_audio")),
        NativeIcon("mic", "Mic", IconCategory.DEVICE, listOf("microphone")),
        NativeIcon("headphones", "Headphones", IconCategory.DEVICE, listOf("headset")),
        NativeIcon("speaker", "Speaker", IconCategory.DEVICE, listOf("speaker_audio")),
        NativeIcon("music", "MusicNote", IconCategory.DEVICE, listOf("music_note", "beeper", "tone")),
        NativeIcon("haptics", "Vibration", IconCategory.DEVICE, listOf("vibration", "buzz")),
        NativeIcon("dashboard", "Dashboard", IconCategory.ANALYTICS, listOf("overview")),
        NativeIcon("insights", "Insights", IconCategory.ANALYTICS, listOf("insight")),
        NativeIcon("bar_chart", "BarChart", IconCategory.ANALYTICS, listOf("chart_bar", "sales_chart")),
        NativeIcon("pie_chart", "PieChart", IconCategory.ANALYTICS, listOf("chart_pie")),
        NativeIcon("trending_up", "TrendingUp", IconCategory.ANALYTICS, listOf("growth")),
        NativeIcon("trending_down", "TrendingDown", IconCategory.ANALYTICS, listOf("decline")),
        NativeIcon("line_chart", "ShowChart", IconCategory.ANALYTICS, listOf("show_chart")),
        NativeIcon("stacked_chart", "StackedLineChart", IconCategory.ANALYTICS, listOf("stacked_line_chart")),
        NativeIcon("query_stats", "QueryStats", IconCategory.ANALYTICS, listOf()),
        NativeIcon("analytics", "Analytics", IconCategory.ANALYTICS, listOf("metrics")),
        NativeIcon("assessment", "Assessment", IconCategory.ANALYTICS, listOf("report_card")),
        NativeIcon("leaderboard", "Leaderboard", IconCategory.ANALYTICS, listOf("ranking")),
        NativeIcon("speed", "Speed", IconCategory.ANALYTICS, listOf("performance")),
        NativeIcon("health", "MonitorHeart", IconCategory.ANALYTICS, listOf("monitor_heart", "health_check")),
        NativeIcon("security", "Security", IconCategory.SECURITY, listOf("sec")),
        NativeIcon("shield", "Shield", IconCategory.SECURITY, listOf("e2ee", "protected")),
        NativeIcon("verified", "VerifiedUser", IconCategory.SECURITY, listOf("verified_user")),
        NativeIcon("gpp_good", "GppGood", IconCategory.SECURITY, listOf("compliance")),
        NativeIcon("lock", "Lock", IconCategory.SECURITY, listOf("locked", "secure")),
        NativeIcon("unlock", "LockOpen", IconCategory.SECURITY, listOf("lock_open", "unlocked")),
        NativeIcon("key", "Key", IconCategory.SECURITY, listOf("keys")),
        NativeIcon("vpn_key", "VpnKey", IconCategory.SECURITY, listOf("token")),
        NativeIcon("password", "Password", IconCategory.SECURITY, listOf("passcode", "pin")),
        NativeIcon("fingerprint", "Fingerprint", IconCategory.SECURITY, listOf("biometrics", "biometric")),
        NativeIcon("face_id", "Face", IconCategory.SECURITY, listOf("face", "facial")),
        NativeIcon("add", "AddCircle", IconCategory.ACTIONS, listOf("plus", "new")),
        NativeIcon("remove", "RemoveCircle", IconCategory.ACTIONS, listOf("minus")),
        NativeIcon("edit", "Edit", IconCategory.ACTIONS, listOf("pencil", "modify")),
        NativeIcon("edit_note", "EditNote", IconCategory.ACTIONS, listOf()),
        NativeIcon("note_add", "NoteAdd", IconCategory.ACTIONS, listOf()),
        NativeIcon("delete", "DeleteOutline", IconCategory.ACTIONS, listOf("trash", "remove_item")),
        NativeIcon("delete_filled", "DeleteForever", IconCategory.ACTIONS, listOf("trash_filled")),
        NativeIcon("copy", "ContentCopy", IconCategory.ACTIONS, listOf("content_copy", "duplicate")),
        NativeIcon("cut", "ContentCut", IconCategory.ACTIONS, listOf("content_cut", "scissors")),
        NativeIcon("crop", "Crop", IconCategory.ACTIONS, listOf("crop_image")),
        NativeIcon("paste", "ContentPaste", IconCategory.ACTIONS, listOf("clipboard", "content_paste")),
        NativeIcon("clear", "Clear", IconCategory.ACTIONS, listOf("reset")),
        NativeIcon("search", "Search", IconCategory.ACTIONS, listOf("find", "magnify", "ocr", "read_text", "text")),
        NativeIcon("filter", "FilterList", IconCategory.ACTIONS, listOf("filter_list")),
        NativeIcon("filter_alt", "FilterAlt", IconCategory.ACTIONS, listOf()),
        NativeIcon("sort", "Sort", IconCategory.ACTIONS, listOf("sort_by")),
        NativeIcon("refresh", "Refresh", IconCategory.ACTIONS, listOf("reload")),
        NativeIcon("upload", "Upload", IconCategory.ACTIONS, listOf("upload_file")),
        NativeIcon("download", "Download", IconCategory.ACTIONS, listOf("download_file")),
        NativeIcon("file_download", "FileDownload", IconCategory.ACTIONS, listOf()),
        NativeIcon("file_upload", "FileUpload", IconCategory.ACTIONS, listOf()),
        NativeIcon("folder", "Folder", IconCategory.ACTIONS, listOf("directory")),
        NativeIcon("folder_open", "FolderOpen", IconCategory.ACTIONS, listOf("open_folder")),
        NativeIcon("new_folder", "CreateNewFolder", IconCategory.ACTIONS, listOf("create_folder")),
        NativeIcon("file", "InsertDriveFile", IconCategory.ACTIONS, listOf("document", "doc")),
        NativeIcon("note", "Note", IconCategory.ACTIONS, listOf("memo")),
        NativeIcon("article", "Article", IconCategory.ACTIONS, listOf("page")),
        NativeIcon("description", "Description", IconCategory.ACTIONS, listOf("doc_text")),
        NativeIcon("share", "Share", IconCategory.ACTIONS, listOf("send_to")),
        NativeIcon("send", "Send", IconCategory.ACTIONS, listOf("submit")),
        NativeIcon("home", "Home", IconCategory.NAVIGATION, listOf("hub", "start")),
        NativeIcon("menu", "Menu", IconCategory.NAVIGATION, listOf("hamburger")),
        NativeIcon("more", "MoreVert", IconCategory.NAVIGATION, listOf("overflow", "more_vert")),
        NativeIcon("more_horizontal", "MoreHoriz", IconCategory.NAVIGATION, listOf("more_horiz")),
        NativeIcon("apps", "Apps", IconCategory.NAVIGATION, listOf("app_grid")),
        NativeIcon("grid", "GridView", IconCategory.NAVIGATION, listOf("grid_view", "tiles")),
        NativeIcon("list", "ViewList", IconCategory.NAVIGATION, listOf("view_list")),
        NativeIcon("columns", "ViewColumn", IconCategory.NAVIGATION, listOf("view_column")),
        NativeIcon("bulleted_list", "FormatListBulleted", IconCategory.NAVIGATION, listOf("list_bullets")),
        NativeIcon("checklist", "Checklist", IconCategory.NAVIGATION, listOf()),
        NativeIcon("fact_check", "FactCheck", IconCategory.NAVIGATION, listOf()),
        NativeIcon("assignment", "Assignment", IconCategory.NAVIGATION, listOf()),
        NativeIcon("assignment_done", "AssignmentTurnedIn", IconCategory.NAVIGATION, listOf("assignment_turned_in")),
        NativeIcon("rule", "Rule", IconCategory.NAVIGATION, listOf("rule_check")),
        NativeIcon("location", "LocationOn", IconCategory.NAVIGATION, listOf("place", "pin_drop")),
        NativeIcon("map", "Map", IconCategory.NAVIGATION, listOf("maps")),
        NativeIcon("navigation", "Navigation", IconCategory.NAVIGATION, listOf("near_me")),
        NativeIcon("my_location", "MyLocation", IconCategory.NAVIGATION, listOf()),
        NativeIcon("explore", "Explore", IconCategory.NAVIGATION, listOf("compass")),
        NativeIcon("language", "Language", IconCategory.NAVIGATION, listOf("locale", "globe")),
        NativeIcon("translate", "Translate", IconCategory.NAVIGATION, listOf("language_translate")),
        NativeIcon("public", "Public", IconCategory.NAVIGATION, listOf("world", "global")),
        NativeIcon("directions", "DirectionsRun", IconCategory.NAVIGATION, listOf("directions_run")),
        NativeIcon("walk", "DirectionsWalk", IconCategory.NAVIGATION, listOf("directions_walk")),
        NativeIcon("car", "DirectionsCar", IconCategory.NAVIGATION, listOf("directions_car")),
        NativeIcon("bus", "DirectionsBus", IconCategory.NAVIGATION, listOf("directions_bus")),
        NativeIcon("taxi", "LocalTaxi", IconCategory.NAVIGATION, listOf("local_taxi")),
        NativeIcon("parking", "LocalParking", IconCategory.NAVIGATION, listOf("local_parking")),
        NativeIcon("fuel", "LocalGasStation", IconCategory.NAVIGATION, listOf("local_gas_station")),
        NativeIcon("ev_charge", "EvStation", IconCategory.NAVIGATION, listOf("ev_station")),
        NativeIcon("call", "Call", IconCategory.COMMUNICATION, listOf("phone_call")),
        NativeIcon("email", "Email", IconCategory.COMMUNICATION, listOf("mail")),
        NativeIcon("message", "Message", IconCategory.COMMUNICATION, listOf("sms")),
        NativeIcon("chat", "Chat", IconCategory.COMMUNICATION, listOf("chat_bubble")),
        NativeIcon("forum", "Forum", IconCategory.COMMUNICATION, listOf("forum_discussion")),
        NativeIcon("comment", "Comment", IconCategory.COMMUNICATION, listOf("comments")),
        NativeIcon("question_answer", "QuestionAnswer", IconCategory.COMMUNICATION, listOf("qa")),
        NativeIcon("feedback", "Feedback", IconCategory.COMMUNICATION, listOf("review_feedback")),
        NativeIcon("reviews", "Reviews", IconCategory.COMMUNICATION, listOf("reviews_list")),
        NativeIcon("rate_review", "RateReview", IconCategory.COMMUNICATION, listOf()),
        NativeIcon("thumbs_up", "ThumbUp", IconCategory.COMMUNICATION, listOf("like")),
        NativeIcon("thumbs_down", "ThumbDown", IconCategory.COMMUNICATION, listOf("dislike")),
        NativeIcon("handshake", "Handshake", IconCategory.COMMUNICATION, listOf("deal_done", "agreement")),
        NativeIcon("restaurant", "Restaurant", IconCategory.FOOD, listOf()),
        NativeIcon("fastfood", "Fastfood", IconCategory.FOOD, listOf("fast_food", "burger")),
        NativeIcon("cafe", "LocalCafe", IconCategory.FOOD, listOf("local_cafe", "coffee")),
        NativeIcon("dining", "LocalDining", IconCategory.FOOD, listOf("local_dining")),
        NativeIcon("drink", "LocalDrink", IconCategory.FOOD, listOf("local_drink", "beverage")),
        NativeIcon("pizza", "LocalPizza", IconCategory.FOOD, listOf("local_pizza")),
        NativeIcon("bakery", "BakeryDining", IconCategory.FOOD, listOf("bakery_dining")),
        NativeIcon("liquor", "Liquor", IconCategory.FOOD, listOf("bar", "alcohol")),
        NativeIcon("pharmacy", "LocalPharmacy", IconCategory.FOOD, listOf("local_pharmacy", "drugstore")),
        NativeIcon("florist", "LocalFlorist", IconCategory.FOOD, listOf("local_florist", "flowers")),
        NativeIcon("pets", "Pets", IconCategory.FOOD, listOf("pet")),
        NativeIcon("toys", "Toys", IconCategory.FOOD, listOf("toy")),
        NativeIcon("child_care", "ChildCare", IconCategory.FOOD, listOf("childcare", "baby")),
        NativeIcon("celebration", "Celebration", IconCategory.FOOD, listOf("party")),
        NativeIcon("cake", "Cake", IconCategory.FOOD, listOf("birthday")),
        NativeIcon("settings", "Settings", IconCategory.GENERAL, listOf("config", "gear")),
        NativeIcon("tune", "Tune", IconCategory.GENERAL, listOf("adjust", "sliders")),
        NativeIcon("build", "Build", IconCategory.GENERAL, listOf("wrench", "tools")),
        NativeIcon("construction", "Construction", IconCategory.GENERAL, listOf("maintenance")),
        NativeIcon("engineering", "Engineering", IconCategory.GENERAL, listOf("engineer")),
        NativeIcon("handyman", "Handyman", IconCategory.GENERAL, listOf("repair")),
        NativeIcon("precision", "PrecisionManufacturing", IconCategory.GENERAL, listOf("machine")),
        NativeIcon("help_outline", "LiveHelp", IconCategory.GENERAL, listOf("live_help")),
        NativeIcon("favorite", "Favorite", IconCategory.GENERAL, listOf("heart", "like_filled")),
        NativeIcon("bookmark", "Bookmark", IconCategory.GENERAL, listOf("saved")),
        NativeIcon("flag", "Flag", IconCategory.GENERAL, listOf("report_flag_alt")),
        NativeIcon("star", "Star", IconCategory.GENERAL, listOf("favourite", "rating")),
        NativeIcon("stars", "Stars", IconCategory.GENERAL, listOf("featured")),
        NativeIcon("brush", "Brush", IconCategory.GENERAL, listOf("paint_brush")),
        NativeIcon("palette", "Palette", IconCategory.GENERAL, listOf("colors", "theme")),
        NativeIcon("format_paint", "FormatPaint", IconCategory.GENERAL, listOf("fill_color")),
        NativeIcon("calculate", "Calculate", IconCategory.GENERAL, listOf("calculator", "math")),
        NativeIcon("functions", "Functions", IconCategory.GENERAL, listOf("formula")),
        NativeIcon("numbers", "Numbers", IconCategory.GENERAL, listOf("digits")),
        NativeIcon("summarize", "Summarize", IconCategory.GENERAL, listOf("summary", "stats_summary")),
        NativeIcon("books", "MenuBook", IconCategory.GENERAL, listOf("menu_book", "catalog_book")),
        NativeIcon("book", "Book", IconCategory.GENERAL, listOf("manual")),
        NativeIcon("import_contacts", "ImportContacts", IconCategory.GENERAL, listOf("contacts_book")),
        NativeIcon("school", "School", IconCategory.GENERAL, listOf("training", "education")),
        NativeIcon("translate_alt", "GTranslate", IconCategory.GENERAL, listOf("g_translate")),
    )

    private val byName: Map<String, NativeIcon> = buildMap {
        icons.forEach { icon ->
            put(icon.name, icon)
            icon.aliases.forEach { alias -> put(alias, icon) }
        }
    }

    private val byMaterial: Map<String, NativeIcon> = icons.associateBy { it.material }

    private val byMaterialLower: Map<String, NativeIcon> =
        icons.associateBy { it.material.lowercase() }

    val size: Int get() = icons.size

    /** Canonical names in declaration order. */
    val names: List<String> get() = icons.map { it.name }

    /** All lookup keys (canonical names plus aliases). */
    val allKeys: Set<String> get() = byName.keys

    val categories: List<IconCategory> get() = IconCategory.entries

    fun byCategory(category: IconCategory): List<NativeIcon> = icons.filter { it.category == category }

    fun find(nameOrUri: String): NativeIcon? {
        val key = normalize(nameOrUri) ?: return null
        return byName[key]
    }

    fun findByMaterial(material: String): NativeIcon? = byMaterial[material]

    fun isKnown(nameOrUri: String): Boolean = find(nameOrUri) != null

    /** Canonical name for a reference, or null when unknown. */
    fun canonicalName(nameOrUri: String): String? = find(nameOrUri)?.name

    /** Material identifier for a reference, or null when unknown. */
    fun materialFor(nameOrUri: String): String? = find(nameOrUri)?.material

    /**
     * Extracts the icon key from a reference. Accepts:
     *  - `icons://native/cart`
     *  - `icons://native/cart?variant=filled`
     *  - `icons://cart`
     *  - `cart` / `Cart` / `cart_add`
     * Returns null when the reference is empty or another scheme (e.g. `https://`).
     */
    fun normalize(reference: String): String? {
        var ref = reference.trim()
        if (ref.isEmpty()) return null

        if (ref.startsWith(SCHEME, ignoreCase = true)) {
            ref = ref.substring(SCHEME.length)
            ref = ref.substringBefore('?').substringBefore('#')
            val segments = ref.split('/').filter { it.isNotBlank() }
            ref = when {
                segments.isEmpty() -> return null
                segments[0].equals(HOST, ignoreCase = true) -> segments.drop(1).joinToString("/")
                else -> segments.joinToString("/")
            }
        } else if (ref.contains("://")) {
            // A different, unsupported scheme (https://, file://, …).
            return null
        }

        ref = ref.trim()
        if (ref.isEmpty()) return null
        // Accept canonical names, aliases, and Material identifiers in any casing.
        byName[ref.lowercase()]?.let { return it.name }
        byMaterialLower[ref.lowercase()]?.let { return it.name }
        return ref.lowercase()
    }

    /** True when [reference] is an `icons://` URI (regardless of resolution). */
    fun isIconUri(reference: String): Boolean =
        reference.trim().startsWith(SCHEME, ignoreCase = true)

    /** Builds the canonical URI for an icon name. */
    fun uriFor(name: String): String = "$SCHEME$HOST/$name"
}
