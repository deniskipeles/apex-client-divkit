# Architectural Model

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SDUI DOCUMENT / APP                             │
│   (Toolbar Buttons, POS Tiles, Status Chips, Empty States)             │
│                                                                        │
│  Node:  { "type": "image", "image_url": "icons://native/cart" }        │
│  Button:{ "type": "button", "icon": "icons://native/printer" }         │
│  Action:{ "url": "icons://native/bell", "payload": {...} }             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                  NATIVE ICON CATALOG + VECTOR RESOLVER                 │
│                                                                        │
│  1. NativeIconCatalog.normalize()  → canonical icon name               │
│     • icons://native/<name>  • icons://<name>  • <name>  • aliases     │
│  2. NativeIconCatalog.find()       → NativeIcon(name, material, cat)   │
│  3. NativeIconVectors.resolve()    → Compose ImageVector               │
│  4. Unknown names → Icons.Default.FlashOn (never crashes)              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼ Resolved state (user.* variables)
┌────────────────────────────────────────────────────────────────────────┐
│  { "icon_name": "cart", "icon_material": "ShoppingCart",              │
│    "icon_category": "Commerce", "state": "ok" }                        │
└────────────────────────────────────────────────────────────────────────┘
```

# Native Icon Scheme (`icons://native/<name>`)

The **Native Icon Scheme** resolves a stable POS-facing icon name to a bundled Compose
`ImageVector`. It is unopinionated: it ships a curated catalog of 285 icons (well over the
100+ requested) and never fetches anything from the network, so icons render instantly and
offline.

The whole set is backed by `material-icons-core` + `material-icons-extended`, which are
already app dependencies. Because every icon is a vector (`ImageVector`), the shipped
payload is tiny — the catalog adds a few hundred KB of code, far below the 4 MB budget
(see [Size budget](#4-size-budget)).

---

## 1. Parameters & Options

Invoked in three positions:

| Position | Example |
| :--- | :--- |
| `image` node | `{ "type": "image", "image_url": "icons://native/cart", "size": 24 }` |
| `button` node | `{ "type": "button", "text": "Print", "icon": "icons://native/printer", ... }` |
| Action URL | `{ "url": "icons://native/bell", "payload": { "var": "user.chosen_icon" } }` |

### Reference forms

| Form | Example | Notes |
| :--- | :--- | :--- |
| Canonical URI | `icons://native/cart` | Preferred. |
| Shorthand URI | `icons://cart` | Host omitted; first path segment is the name. |
| Bare name | `cart` | Accepted anywhere an `icon` value is expected. |
| Alias | `shopping_cart`, `trolley` | Resolves to the canonical icon. |
| Material id | `ShoppingCart` | Case-insensitive; useful when porting from Material docs. |

Query strings and fragments are ignored (`icons://native/cart?variant=filled` → `cart`).
Any other scheme (`https://`, `file://`, …) is rejected rather than silently misinterpreted.

### Action payload (`icons://native/<name>` as an action)

| Field | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `var` | `string` | `user.icon_name` | Variable to receive the canonical icon name. |
| `output.icon` | `string` | — | Alternative to `var`. |
| `result_var` | `string` | — | Alternative to `var`. |

The action always also publishes `user.icon_name`, `user.icon_material`, and
`user.icon_category` so a card can bind the resolved icon without extra configuration.

---

## 2. Output Schema

| Field | Type | Description |
| :--- | :--- | :--- |
| `user.icon_name` | `string` | Canonical icon name (e.g. `cart`). |
| `user.icon_material` | `string` | Material identifier backing it (e.g. `ShoppingCart`). |
| `user.icon_category` | `string` | Human-readable category label (e.g. `Commerce`). |
| `state` | `string` | `"ok"`; unknown names return an error instead. |

Unknown icons produce an `Error` outcome (`"Unknown native icon: <uri>"`) rather than a
silent no-op, so typos surface immediately.

---

## 3. DivKit Usage Examples

### Icon gallery (image nodes)

```json
{
  "type": "container",
  "orientation": "horizontal",
  "item_spacing": 10,
  "items": [
    { "type": "image", "image_url": "icons://native/cart", "size": 26, "tint": "#C7D2FE" },
    { "type": "image", "image_url": "icons://native/bell", "size": 26, "tint": "#C7D2FE" },
    { "type": "image", "image_url": "icons://native/receipt", "size": 26, "tint": "#C7D2FE" }
  ]
}
```

The `image` node also accepts `icon`, `content_description`, `tint`, `text_color`,
`size`, `width`/`height`, and `alignment_horizontal` (`left`/`center`/`right`).

### Button with a native icon

```json
{
  "type": "button",
  "text": "Print Receipt",
  "icon": "icons://native/printer",
  "background_color": "#2563EB",
  "text_color": "#F8FAFC",
  "action": { "url": "tools://print/print" }
}
```

### Resolve an icon into state

```json
{
  "type": "button",
  "text": "Pick Bell",
  "icon": "icons://native/bell",
  "action": {
    "url": "icons://native/bell",
    "payload": { "var": "user.chosen_icon" }
  }
}
```

Then render it dynamically:

```json
{ "type": "image", "image_url": "icons://native/@{user.chosen_icon}", "size": 32 }
```

### Dynamic binding through state

```json
{ "type": "text", "text": "Icon: @{user.icon_name} (@{user.icon_category})" }
```

---

## 4. Size budget

The scheme adds **no image assets**. Every icon is a code-generated `ImageVector`, and the
underlying `material-icons-*` artifacts were already dependencies. Concretely:

| Item | Payload |
| :--- | :--- |
| `NativeIconCatalog.kt` | ~31 KB of Kotlin source (285 names + aliases, no drawables). |
| `NativeIconVectors.kt` | ~14 KB of Kotlin source (one `when` branch per icon). |
| New image/vector assets | 0 bytes. |
| R8 (release) | Inlines each vector builder into `NativeIconVectors.resolve`; no per-icon classes survive. |

Measured on the R8-shrunk release APK by building with and without the vector table:

| Build | Universal release APK |
| :--- | :--- |
| Without icon vectors | 58,160,585 bytes |
| With icon vectors | 58,226,121 bytes |
| **Delta** | **~64 KB** |

The catalog therefore adds roughly 64 KB to the release APK — far below the 4 MB limit.

---

## 5. Icon reference (285 icons)

Every icon is addressed as `icons://native/<name>`. The **Aliases** column lists extra
names that resolve to the same icon.

### Commerce (23)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `cart` | `ShoppingCart` | `shopping_cart`, `trolley` |
| `cart_add` | `AddShoppingCart` | `add_to_cart` |
| `cart_remove` | `RemoveShoppingCart` | `remove_from_cart` |
| `checkout` | `ShoppingCartCheckout` | `cart_checkout`, `buy` |
| `bag` | `ShoppingBag` | `shopping_bag` |
| `basket` | `ShoppingBasket` | `shopping_basket` |
| `product` | `Inventory2` | `item`, `box` |
| `stock` | `Inventory` | `inventory`, `on_hand` |
| `category` | `Category` | `categories` |
| `tag` | `Sell` | `price_tag`, `sell`, `pricetag` |
| `label` | `Label` | `labels`, `barcode_label` |
| `offer` | `LocalOffer` | `local_offer`, `deal` |
| `redeem` | `Redeem` | `coupon`, `gift` |
| `loyalty` | `Loyalty` | `rewards`, `points` |
| `storefront` | `Storefront` | `store`, `shop` |
| `mall` | `StoreMallDirectory` | `store_mall`, `shopping_mall` |
| `branch` | `Business` | `store_branch` |
| `mall_bag` | `LocalMall` | `local_mall` |
| `convenience` | `LocalConvenienceStore` | `convenience_store` |
| `new_business` | `AddBusiness` | `add_business` |
| `new_order` | `PostAdd` | `post_add`, `create_order` |
| `view_module` | `ViewModule` | `catalogue`, `catalog_grid` |
| `production` | `ProductionQuantityLimits` | `limits`, `qty` |

### Payment (16)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `payment` | `Payments` | `pay`, `payments` |
| `pos` | `PointOfSale` | `point_of_sale`, `terminal` |
| `card` | `CreditCard` | `credit_card`, `debit_card` |
| `wallet` | `AccountBalanceWallet` | `balance_wallet` |
| `cash` | `AttachMoney` | `money`, `dollar` |
| `paid` | `Paid` | `paid_stamp` |
| `exchange` | `CurrencyExchange` | `currency_exchange`, `fx` |
| `atm` | `LocalAtm` | `cash_machine` |
| `bank` | `AccountBalance` | `account_balance` |
| `receipt` | `ReceiptLong` | `receipt_long`, `bill`, `invoice` |
| `receipt_short` | `RequestPage` | — |
| `quote` | `RequestQuote` | `request_quote`, `estimate` |
| `discount` | `Percent` | `percent`, `sale` |
| `gift_card` | `CardGiftcard` | `giftcard`, `card_giftcard` |
| `ticket` | `ConfirmationNumber` | `confirmation_number` |
| `refund` | `AssignmentReturn` | `return_payment`, `refund_order` |

### Inventory (13)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `warehouse` | `Warehouse` | `storage`, `depot` |
| `shipping` | `LocalShipping` | `truck`, `delivery_truck` |
| `delivery` | `DeliveryDining` | `delivery_dining` |
| `factory` | `Factory` | `manufacturing` |
| `new_box` | `AddBox` | `add_box` |
| `all_inbox` | `AllInbox` | `inbox_all` |
| `shelves` | `Garage` | `shelf`, `rack`, `garage` |
| `moving` | `Moving` | `transfer_stock` |
| `scan` | `QrCodeScanner` | `scanner`, `barcode`, `scan_code` |
| `qr` | `QrCode2` | `qr_code`, `qrcode` |
| `nfc` | `Nfc` | `tap`, `contactless_pay` |
| `contactless` | `Contactless` | `tap_to_pay` |
| `sensors` | `Sensors` | `sensor` |

### Customer (15)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `customer` | `Person` | `person`, `user`, `profile` |
| `customers` | `People` | `people`, `users` |
| `group` | `Groups` | `groups`, `team` |
| `person_add` | `PersonAdd` | `add_customer`, `new_customer` |
| `manage_accounts` | `ManageAccounts` | `account_manage` |
| `supervisor` | `SupervisorAccount` | `supervisor_account` |
| `support` | `SupportAgent` | `support_agent`, `help_desk` |
| `contact_phone` | `ContactPhone` | `phone_contact` |
| `contacts` | `Contacts` | `address_book` |
| `assignment_ind` | `AssignmentInd` | `assigned_staff` |
| `membership` | `CardMembership` | `card_membership` |
| `premium` | `WorkspacePremium` | `workspace_premium`, `vip` |
| `achievement` | `MilitaryTech` | `military_tech`, `tier` |
| `trophy` | `EmojiEvents` | `emoji_events`, `award` |
| `badge` | `Badge` | `staff_badge`, `id_badge` |

### Alerts (11)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `bell` | `Notifications` | `notification`, `notifications`, `push` |
| `bell_add` | `NotificationAdd` | `notification_add` |
| `bell_active` | `NotificationsActive` | `notifications_active` |
| `bell_important` | `NotificationImportant` | `notification_important` |
| `campaign` | `Campaign` | `broadcast`, `megaphone` |
| `announcement` | `Announcement` | `news` |
| `push_pin` | `PushPin` | `pin_message` |
| `mute` | `DoNotDisturb` | `do_not_disturb` |
| `priority` | `PriorityHigh` | `priority_high`, `urgent` |
| `report` | `Report` | `report_flag` |
| `report_problem` | `ReportProblem` | `problem` |

### Status (15)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `error` | `Error` | `failed`, `danger` |
| `error_outline` | `ErrorOutline` | `error_alt` |
| `warning` | `Warning` | `alert`, `caution` |
| `warning_amber` | `WarningAmber` | `warning_alt` |
| `info` | `Info` | `information` |
| `help` | `Help` | `question` |
| `help_center` | `HelpCenter` | `support_center` |
| `check` | `CheckCircle` | `success`, `ok`, `checkout_ok` |
| `check_outline` | `CheckCircleOutline` | `success_alt` |
| `cancel` | `Cancel` | `cancelled`, `void` |
| `close` | `Close` | `dismiss`, `x` |
| `done` | `Done` | `tick` |
| `done_all` | `DoneAll` | `all_done` |
| `pending` | `PendingActions` | `pending_actions` |
| `task_alt` | `TaskAlt` | `task_complete` |

### Time (10)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `clock` | `Schedule` | `schedule`, `time` |
| `hourglass` | `HourglassEmpty` | `waiting`, `hourglass_empty` |
| `hourglass_full` | `HourglassBottom` | `hourglass_bottom` |
| `timer` | `Timer` | `stopwatch` |
| `calendar` | `CalendarMonth` | `calendar_month`, `date` |
| `today` | `Today` | `today_date` |
| `event` | `Event` | `appointment` |
| `alarm` | `Alarm` | `alarm_clock` |
| `update` | `Update` | `update_time` |
| `history` | `History` | `recent`, `log` |

### Connectivity (19)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `sync` | `Sync` | `refresh_sync` |
| `autorenew` | `Autorenew` | `retry`, `cycle` |
| `cloud` | `CloudSync` | `cloud_sync` |
| `cloud_download` | `CloudDownload` | `download_cloud` |
| `cloud_upload` | `CloudUpload` | `upload_cloud` |
| `cloud_done` | `CloudDone` | `cloud_ok` |
| `cloud_off` | `CloudOff` | `offline`, `cloud_offline` |
| `wifi` | `Wifi` | `network` |
| `wifi_off` | `WifiOff` | `no_network` |
| `bluetooth` | `Bluetooth` | `bt` |
| `bluetooth_connected` | `BluetoothConnected` | `bt_connected` |
| `router` | `Router` | `gateway` |
| `memory` | `Memory` | `chip` |
| `usb` | `Usb` | `usb_port` |
| `battery` | `BatteryFull` | `battery_full` |
| `battery_low` | `BatteryAlert` | `battery_alert` |
| `power` | `Power` | `power_on` |
| `cable` | `Cable` | `wired` |
| `lan` | `Lan` | `ethernet` |

### Device (25)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `printer` | `Print` | `print`, `receipt_printer` |
| `print_off` | `PrintDisabled` | `printer_off` |
| `scanner_device` | `DocumentScanner` | `scanner_hw` |
| `fax` | `Fax` | `fax_machine` |
| `camera` | `PhotoCamera` | `photo_camera` |
| `photo` | `Image` | `image`, `picture` |
| `photo_library` | `PhotoLibrary` | `gallery`, `photos` |
| `collections` | `Collections` | `media` |
| `video` | `Videocam` | `videocam` |
| `movie` | `Movie` | `film` |
| `tv` | `Tv` | `display` |
| `smart_display` | `SmartDisplay` | `kiosk` |
| `phone` | `PhoneAndroid` | `mobile`, `smartphone` |
| `tablet` | `TabletMac` | `tablet_mac` |
| `laptop` | `LaptopMac` | `laptop_mac`, `computer` |
| `desktop` | `DesktopWindows` | `desktop_windows`, `pc` |
| `watch` | `Watch` | `smartwatch` |
| `devices` | `Devices` | `multi_device` |
| `volume` | `VolumeUp` | `audio`, `sound` |
| `volume_off` | `VolumeOff` | `mute_audio` |
| `mic` | `Mic` | `microphone` |
| `headphones` | `Headphones` | `headset` |
| `speaker` | `Speaker` | `speaker_audio` |
| `music` | `MusicNote` | `music_note`, `beeper`, `tone` |
| `haptics` | `Vibration` | `vibration`, `buzz` |

### Analytics (14)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `dashboard` | `Dashboard` | `overview` |
| `insights` | `Insights` | `insight` |
| `bar_chart` | `BarChart` | `chart_bar`, `sales_chart` |
| `pie_chart` | `PieChart` | `chart_pie` |
| `trending_up` | `TrendingUp` | `growth` |
| `trending_down` | `TrendingDown` | `decline` |
| `line_chart` | `ShowChart` | `show_chart` |
| `stacked_chart` | `StackedLineChart` | `stacked_line_chart` |
| `query_stats` | `QueryStats` | — |
| `analytics` | `Analytics` | `metrics` |
| `assessment` | `Assessment` | `report_card` |
| `leaderboard` | `Leaderboard` | `ranking` |
| `speed` | `Speed` | `performance` |
| `health` | `MonitorHeart` | `monitor_heart`, `health_check` |

### Security (11)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `security` | `Security` | `sec` |
| `shield` | `Shield` | `e2ee`, `protected` |
| `verified` | `VerifiedUser` | `verified_user` |
| `gpp_good` | `GppGood` | `compliance` |
| `lock` | `Lock` | `locked`, `secure` |
| `unlock` | `LockOpen` | `lock_open`, `unlocked` |
| `key` | `Key` | `keys` |
| `vpn_key` | `VpnKey` | `token` |
| `password` | `Password` | `passcode`, `pin` |
| `fingerprint` | `Fingerprint` | `biometrics`, `biometric` |
| `face_id` | `Face` | `face`, `facial` |

### Actions (30)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `add` | `AddCircle` | `plus`, `new` |
| `remove` | `RemoveCircle` | `minus` |
| `edit` | `Edit` | `pencil`, `modify` |
| `edit_note` | `EditNote` | — |
| `note_add` | `NoteAdd` | — |
| `delete` | `DeleteOutline` | `trash`, `remove_item` |
| `delete_filled` | `DeleteForever` | `trash_filled` |
| `copy` | `ContentCopy` | `content_copy`, `duplicate` |
| `cut` | `ContentCut` | `content_cut`, `scissors` |
| `crop` | `Crop` | `crop_image` |
| `paste` | `ContentPaste` | `clipboard`, `content_paste` |
| `clear` | `Clear` | `reset` |
| `search` | `Search` | `find`, `magnify`, `ocr`, `read_text`, `text` |
| `filter` | `FilterList` | `filter_list` |
| `filter_alt` | `FilterAlt` | — |
| `sort` | `Sort` | `sort_by` |
| `refresh` | `Refresh` | `reload` |
| `upload` | `Upload` | `upload_file` |
| `download` | `Download` | `download_file` |
| `file_download` | `FileDownload` | — |
| `file_upload` | `FileUpload` | — |
| `folder` | `Folder` | `directory` |
| `folder_open` | `FolderOpen` | `open_folder` |
| `new_folder` | `CreateNewFolder` | `create_folder` |
| `file` | `InsertDriveFile` | `document`, `doc` |
| `note` | `Note` | `memo` |
| `article` | `Article` | `page` |
| `description` | `Description` | `doc_text` |
| `share` | `Share` | `send_to` |
| `send` | `Send` | `submit` |

### Navigation (30)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `home` | `Home` | `hub`, `start` |
| `menu` | `Menu` | `hamburger` |
| `more` | `MoreVert` | `overflow`, `more_vert` |
| `more_horizontal` | `MoreHoriz` | `more_horiz` |
| `apps` | `Apps` | `app_grid` |
| `grid` | `GridView` | `grid_view`, `tiles` |
| `list` | `ViewList` | `view_list` |
| `columns` | `ViewColumn` | `view_column` |
| `bulleted_list` | `FormatListBulleted` | `list_bullets` |
| `checklist` | `Checklist` | — |
| `fact_check` | `FactCheck` | — |
| `assignment` | `Assignment` | — |
| `assignment_done` | `AssignmentTurnedIn` | `assignment_turned_in` |
| `rule` | `Rule` | `rule_check` |
| `location` | `LocationOn` | `place`, `pin_drop` |
| `map` | `Map` | `maps` |
| `navigation` | `Navigation` | `near_me` |
| `my_location` | `MyLocation` | — |
| `explore` | `Explore` | `compass` |
| `language` | `Language` | `locale`, `globe` |
| `translate` | `Translate` | `language_translate` |
| `public` | `Public` | `world`, `global` |
| `directions` | `DirectionsRun` | `directions_run` |
| `walk` | `DirectionsWalk` | `directions_walk` |
| `car` | `DirectionsCar` | `directions_car` |
| `bus` | `DirectionsBus` | `directions_bus` |
| `taxi` | `LocalTaxi` | `local_taxi` |
| `parking` | `LocalParking` | `local_parking` |
| `fuel` | `LocalGasStation` | `local_gas_station` |
| `ev_charge` | `EvStation` | `ev_station` |

### Communication (13)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `call` | `Call` | `phone_call` |
| `email` | `Email` | `mail` |
| `message` | `Message` | `sms` |
| `chat` | `Chat` | `chat_bubble` |
| `forum` | `Forum` | `forum_discussion` |
| `comment` | `Comment` | `comments` |
| `question_answer` | `QuestionAnswer` | `qa` |
| `feedback` | `Feedback` | `review_feedback` |
| `reviews` | `Reviews` | `reviews_list` |
| `rate_review` | `RateReview` | — |
| `thumbs_up` | `ThumbUp` | `like` |
| `thumbs_down` | `ThumbDown` | `dislike` |
| `handshake` | `Handshake` | `deal_done`, `agreement` |

### Food (15)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `restaurant` | `Restaurant` | — |
| `fastfood` | `Fastfood` | `fast_food`, `burger` |
| `cafe` | `LocalCafe` | `local_cafe`, `coffee` |
| `dining` | `LocalDining` | `local_dining` |
| `drink` | `LocalDrink` | `local_drink`, `beverage` |
| `pizza` | `LocalPizza` | `local_pizza` |
| `bakery` | `BakeryDining` | `bakery_dining` |
| `liquor` | `Liquor` | `bar`, `alcohol` |
| `pharmacy` | `LocalPharmacy` | `local_pharmacy`, `drugstore` |
| `florist` | `LocalFlorist` | `local_florist`, `flowers` |
| `pets` | `Pets` | `pet` |
| `toys` | `Toys` | `toy` |
| `child_care` | `ChildCare` | `childcare`, `baby` |
| `celebration` | `Celebration` | `party` |
| `cake` | `Cake` | `birthday` |

### General (25)

| `icons://native/<name>` | Material | Aliases |
| :--- | :--- | :--- |
| `settings` | `Settings` | `config`, `gear` |
| `tune` | `Tune` | `adjust`, `sliders` |
| `build` | `Build` | `wrench`, `tools` |
| `construction` | `Construction` | `maintenance` |
| `engineering` | `Engineering` | `engineer` |
| `handyman` | `Handyman` | `repair` |
| `precision` | `PrecisionManufacturing` | `machine` |
| `help_outline` | `LiveHelp` | `live_help` |
| `favorite` | `Favorite` | `heart`, `like_filled` |
| `bookmark` | `Bookmark` | `saved` |
| `flag` | `Flag` | `report_flag_alt` |
| `star` | `Star` | `favourite`, `rating` |
| `stars` | `Stars` | `featured` |
| `brush` | `Brush` | `paint_brush` |
| `palette` | `Palette` | `colors`, `theme` |
| `format_paint` | `FormatPaint` | `fill_color` |
| `calculate` | `Calculate` | `calculator`, `math` |
| `functions` | `Functions` | `formula` |
| `numbers` | `Numbers` | `digits` |
| `summarize` | `Summarize` | `summary`, `stats_summary` |
| `books` | `MenuBook` | `menu_book`, `catalog_book` |
| `book` | `Book` | `manual` |
| `import_contacts` | `ImportContacts` | `contacts_book` |
| `school` | `School` | `training`, `education` |
| `translate_alt` | `GTranslate` | `g_translate` |

---

## 6. Extending the set

1. Add a `NativeIcon(name, material, category, aliases)` entry to
   [`NativeIconCatalog.kt`](../../app/src/main/java/engine/icons/NativeIconCatalog.kt).
   `material` must be a real `androidx.compose.material.icons.filled.*` identifier.
2. Add the matching `"<material>" -> Icons.Default.<material>` branch to
   [`NativeIconVectors.kt`](../../app/src/main/java/engine/icons/NativeIconVectors.kt).
3. Run `./gradlew :app:testDebugUnitTest`. `NativeIconCatalogTest` checks that every key is
   unique and resolvable, and the Robolectric suite asserts every catalog icon maps to a
   Compose vector — so a missing branch fails the build.
