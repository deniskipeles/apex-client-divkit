import {
  divCard,
  DivContainer,
  DivGallery,
  DivImage,
  DivSolidBackground,
  DivText,
  fixed,
  matchParent,
  weighted,
} from '@divkitframework/jsonbuilder'
import {
  type DivAction,
  bodyText,
  bottomSheet,
  chip,
  bottomTabs,
  cameraViewfinder,
  disclosure,
  displayText,
  divider,
  fab,
  formModal,
  inlineBanner,
  deleteAction,
  jsonList,
  labelText,
  opAction,
  photoPicker,
  progressBar,
  progressRing,
  queryAction,
  qrSequenceViewer,
  scanProgressChips,
  segmented,
  statusChip,
  stepper,
  setVarAction,
  setVars,
  stickyFooter,
  textField,
  titleText,
  toastBlock,
  upsertAction,
} from './blocks.ts'
import { TOKENS } from './tokens.ts'
import { customerVariables, ownerVariables, productFormVariables } from './variables.ts'

/**
 * DivKit expressions travel as plain `@{...}` strings: every renderer (Android, iOS, preview)
 * evaluates that form, while an expression *object* is only understood by some of them.
 */
const expression = (body: string) => (body.trim().startsWith('@{') ? body : `@{${body}}`)

export function buildOwnerPosCard() {
  return divCard(
    {},
    {
      log_id: 'owner_pos',
      variables: ownerVariables(),
      states: [
        {
          state_id: 0,
          div: new DivContainer({
            orientation: 'vertical',
            width: matchParent(),
            height: matchParent(),
            background: [new DivSolidBackground({ color: TOKENS.color.canvas })],
            items: [
              toastBlock({
                visibleVar: 'owner.toast_visible',
                messageVar: 'owner.toast_msg',
                severityVar: 'owner.toast_severity',
                actionLabelVar: 'owner.toast_action',
                onAction: [opAction('owner_toast_act', 'pos.toast_action')],
              }),
              new DivGallery({
                orientation: 'vertical',
                width: matchParent(),
                height: weighted(1),
                paddings: { left: 20, right: 20, top: 16, bottom: 20 },
                items: [
                  inlineBanner({
                    id: 'pending_qr_banner',
                    visibleIf: 'owner.pending_qr_visible',
                    severity: 'warning',
                    messageExpr: '@{owner.pending_qr_msg}',
                    primaryAction: {
                      label: 'Reopen QR',
                      onTap: [
                        opAction('reopen_qr', 'pos.regenerate_qr', {
                          order_id: '@{owner.pending_qr_order_id}',
                        }),
                      ],
                    },
                  }),
                  inlineBanner({
                    id: 'resumed_draft_banner',
                    visibleIf: 'owner.draft_resumed_visible',
                    severity: 'warning',
                    messageExpr: '@{owner.draft_resumed_msg}',
                    primaryAction: {
                      label: 'Start new',
                      onTap: [opAction('discard_draft', 'pos.discard_draft')],
                    },
                  }),
                  new DivContainer({
                    width: matchParent(),
                    margins: { top: 8, bottom: 16 },
                    items: [
                      segmented('owner.mode', [
                        { id: 'barcode', label: 'Barcode' },
                        { id: 'qr', label: 'QR' },
                        { id: 'ocr', label: 'OCR' },
                      ]),
                    ],
                  }),
                  cameraViewfinder({
                    id: 'pos_viewfinder',
                    modeVar: 'owner.mode',
                    collapsedIf: 'owner.cart_count > 0 && !owner.viewfinder_expanded',
                    cutWindowShape: 'rect',
                    hintExpr:
                      "@{owner.mode == 'qr' ? 'Center the QR code' : owner.mode == 'ocr' ? 'Align the product label' : 'Center the barcode'}",
                    unavailableCopy: 'Camera unavailable on this device — enter the code manually',
                    manualAction: [
                      { log_id: 'pos_manual_code', url: 'sheet://present?card_id=owner_scan_sheet' },
                    ],
                    onScanOp: 'pos.scan_resolve',
                    onScanArgs: { raw: '$scan.raw', mode: '@{owner.mode}' },
                  }),
                  new DivContainer({
                    visibility: expression(
                      "@{owner.mode == 'ocr' && owner.ocr_candidates_count > 0 ? 'visible' : 'gone'}",
                    ),
                    width: matchParent(),
                    margins: { top: 12 },
                    paddings: { left: 16, right: 16, top: 16, bottom: 16 },
                    background: [new DivSolidBackground({ color: TOKENS.color.surface })],
                    border: { corner_radius: TOKENS.radius.card },
                    items: [
                      labelText('Tap a match to add'),
                      jsonList(
                        'owner.ocr_candidates',
                        chip('$item.name', [
                          opAction('ocr_pick', 'pos.add_by_sku', { sku: '$item.sku', qty: 1 }),
                        ]),
                        8,
                      ),
                    ],
                  }),
                  new DivContainer({
                    visibility: expression("@{owner.last_scan_visible ? 'visible' : 'gone'}"),
                    orientation: 'horizontal',
                    width: matchParent(),
                    margins: { top: 12 },
                    paddings: { left: 16, right: 16, top: 14, bottom: 14 },
                    background: [new DivSolidBackground({ color: TOKENS.color.surface })],
                    border: { corner_radius: TOKENS.radius.card },
                    alignment_vertical: 'center',
                    items: [
                      new DivText({
                        text: expression('@{owner.last_scan}'),
                        width: weighted(1),
                        font_size: 16,
                        text_color: TOKENS.color.ink,
                      }),
                      new DivContainer({
                        visibility: expression(
                          "@{owner.last_scan_can_undo ? 'visible' : 'gone'}",
                        ),
                        height: fixed(48),
                        paddings: { left: 12, right: 4 },
                        content_alignment_vertical: 'center',
                        actions: [opAction('undo_scan', 'pos.undo_last')],
                        items: [
                          new DivText({
                            text: 'Undo',
                            font_size: 16,
                            font_weight: 'bold',
                            text_color: TOKENS.color.accent,
                          }),
                        ],
                      }),
                    ],
                  }),
                  inlineBanner({
                    id: 'scan_error_banner',
                    visibleIf: 'owner.scan_error_visible',
                    severity: 'danger',
                    messageExpr: '@{owner.scan_error_msg}',
                    primaryAction: {
                      label: 'Map to product',
                      onTap: [
                        { log_id: 'open_map_picker', url: 'sheet://present?card_id=map_barcode_picker' },
                      ],
                    },
                  }),
                  new DivContainer({
                    visibility: expression("@{owner.cart_count > 0 ? 'visible' : 'gone'}"),
                    width: matchParent(),
                    margins: { top: 16 },
                    items: [
                      jsonList(
                        'owner.cart_lines',
                        new DivContainer({
                          orientation: 'horizontal',
                          width: matchParent(),
                          paddings: { left: 16, right: 12, top: 14, bottom: 14 },
                          background: [new DivSolidBackground({ color: TOKENS.color.surface })],
                          border: { corner_radius: TOKENS.radius.card },
                          alignment_vertical: 'center',
                          items: [
                            new DivContainer({
                              orientation: 'vertical',
                              width: weighted(1),
                              items: [
                                bodyText('$item.name'),
                                new DivText({
                                  text: '$item.unit_price_display',
                                  font_size: 13,
                                  text_color: TOKENS.color.muted,
                                  font_feature_settings: 'tnum',
                                }),
                              ],
                            }),
                            stepper('item.qty', 'item.line_id'),
                            new DivText({
                              text: '$item.line_total_display',
                              font_size: 22,
                              line_height: 28,
                              font_weight: 'bold',
                              text_color: TOKENS.color.ink,
                              font_feature_settings: 'tnum',
                              text_alignment_horizontal: 'end',
                              width: fixed(84),
                              margins: { left: 8 },
                            }),
                            new DivContainer({
                              width: fixed(48),
                              height: fixed(48),
                              content_alignment_horizontal: 'center',
                              content_alignment_vertical: 'center',
                              actions: [
                                opAction('remove_line', 'pos.remove_line', {
                                  line_id: '$item.line_id',
                                }),
                              ],
                              items: [
                                new DivText({
                                  text: '⋯',
                                  font_size: 20,
                                  text_color: TOKENS.color.muted,
                                }),
                              ],
                            }),
                          ],
                        }),
                        12,
                      ),
                    ],
                  }),
                ],
              }),
              stickyFooter({
                summaryExpr: '@{owner.cart_footer_summary}',
                actionLabel: 'Checkout',
                enabledIf: 'owner.cart_count > 0',
                onTap: [{ log_id: 'open_checkout', url: 'sheet://present?card_id=owner_checkout_sheet' }],
              }),
              bottomTabs('owner.tab', [
                { id: 'pos', label: 'POS', icon: 'pos', card: 'owner_pos' },
                {
                  id: 'inventory',
                  label: 'Inventory',
                  icon: 'inventory',
                  card: 'owner_inventory',
                  actions: [loadInventory()],
                },
              ]),
            ],
          }),
        },
      ],
    },
  )
}

export function buildOwnerCheckoutSheet() {
  return divCard(
    {},
    {
      log_id: 'owner_checkout_sheet',
      variables: ownerVariables(),
      states: [
        {
          state_id: 0,
          div: bottomSheet('owner_checkout_sheet', 'Confirm order', [
            jsonList(
              'owner.cart_lines',
              new DivContainer({
                orientation: 'horizontal',
                width: matchParent(),
                paddings: { top: 8, bottom: 8 },
                alignment_vertical: 'center',
                items: [
                  new DivText({
                    text: '$item.name_and_qty',
                    width: weighted(1),
                    font_size: 16,
                    text_color: TOKENS.color.ink,
                  }),
                  titleText('$item.line_total_display', TOKENS.color.ink, true),
                ],
              }),
              8,
            ),
            divider(),
            new DivContainer({
              orientation: 'horizontal',
              width: matchParent(),
              margins: { bottom: 24 },
              alignment_vertical: 'center',
              items: [
                new DivText({
                  text: 'Total',
                  width: weighted(1),
                  font_size: 22,
                  font_weight: 'bold',
                  text_color: TOKENS.color.ink,
                }),
                titleText('@{owner.cart_total}', TOKENS.color.ink, true),
              ],
            }),
            new DivContainer({
              orientation: 'horizontal',
              width: matchParent(),
              alignment_vertical: 'center',
              items: [
                new DivContainer({
                  height: fixed(48),
                  paddings: { left: 16, right: 20 },
                  content_alignment_vertical: 'center',
                  actions: [{ log_id: 'cancel_checkout', url: 'sheet://dismiss' }],
                  items: [bodyText('Cancel', TOKENS.color.muted)],
                }),
                new DivContainer({
                  width: weighted(1),
                  height: fixed(56),
                  border: { corner_radius: TOKENS.radius.button },
                  background: [new DivSolidBackground({ color: TOKENS.color.accent })],
                  content_alignment_horizontal: 'center',
                  content_alignment_vertical: 'center',
                  actions: [
                    opAction('checkout_qr', 'pos.checkout', {
                      order_id: '@{owner.cart_id}',
                      chunk_bytes: 500,
                    }),
                    { log_id: 'open_qr_sequence', url: 'nav://push?id=owner_qr_sequence' },
                  ],
                  items: [
                    new DivText({
                      text: 'Generate QR',
                      font_size: 16,
                      font_weight: 'bold',
                      text_color: TOKENS.color.surface,
                      text_alignment_horizontal: 'center',
                    }),
                  ],
                }),
              ],
            }),
          ]),
        },
      ],
    },
  )
}

export function buildOwnerQrSequenceCard() {
  return divCard(
    {},
    {
      log_id: 'owner_qr_sequence',
      variables: ownerVariables(),
      states: [
        {
          state_id: 0,
          div: new DivContainer({
            orientation: 'vertical',
            width: matchParent(),
            height: matchParent(),
            background: [new DivSolidBackground({ color: TOKENS.color.canvas })],
            paddings: { left: 20, right: 20, top: 16, bottom: 24 },
            items: [
              new DivContainer({
                orientation: 'horizontal',
                width: matchParent(),
                height: fixed(56),
                alignment_vertical: 'center',
                items: [
                  new DivContainer({
                    width: fixed(48),
                    height: fixed(48),
                    content_alignment_horizontal: 'center',
                    content_alignment_vertical: 'center',
                    actions: [
                      opAction('qr_back', 'pos.leave_qr', { order_id: '@{owner.cart_id}' }),
                      { log_id: 'qr_back_nav', url: 'nav://pop' },
                    ],
                    items: [
                      new DivText({ text: '←', font_size: 22, text_color: TOKENS.color.ink }),
                    ],
                  }),
                  titleText('@{owner.qr_order_label}'),
                ],
              }),
              new DivContainer({
                orientation: 'vertical',
                width: matchParent(),
                background: [new DivSolidBackground({ color: TOKENS.color.surface })],
                border: { corner_radius: TOKENS.radius.card },
                paddings: { left: 24, right: 24, top: 24, bottom: 24 },
                margins: { top: 12 },
                content_alignment_horizontal: 'center',
                items: [
                  qrSequenceViewer({
                    chunksVar: 'owner.qr_chunks',
                    indexVar: 'owner.qr_index',
                    autoVar: 'owner.qr_auto',
                    autoMs: 3000,
                  }),
                  new DivText({
                    text: expression('@{owner.qr_progress_label}'),
                    font_size: 16,
                    font_weight: 'bold',
                    text_color: TOKENS.color.ink,
                    text_alignment_horizontal: 'center',
                    margins: { top: 16, bottom: 8 },
                  }),
                  progressBar('owner.qr_index', 'owner.qr_total'),
                  new DivContainer({
                    orientation: 'horizontal',
                    width: matchParent(),
                    margins: { top: 20 },
                    alignment_vertical: 'center',
                    items: [
                      new DivContainer({
                        height: fixed(48),
                        paddings: { left: 12, right: 12 },
                        content_alignment_vertical: 'center',
                        actions: [opAction('qr_prev', 'pos.qr_step', { delta: -1 })],
                        items: [bodyText('Prev', TOKENS.color.muted)],
                      }),
                      new DivContainer({
                        width: weighted(1),
                        height: fixed(48),
                        border: { corner_radius: TOKENS.radius.pill },
                        background: [new DivSolidBackground({ color: TOKENS.color.accentTint })],
                        content_alignment_horizontal: 'center',
                        content_alignment_vertical: 'center',
                        actions: [setVarAction('toggle_auto', 'owner.qr_auto', '@{!owner.qr_auto}')],
                        items: [
                          new DivText({
                            text: expression(
                              "@{owner.qr_auto ? 'Auto-advance on' : 'Auto-advance paused'}",
                            ),
                            font_size: 13,
                            font_weight: 'medium',
                            text_color: TOKENS.color.accent,
                            text_alignment_horizontal: 'center',
                          }),
                        ],
                      }),
                      new DivContainer({
                        height: fixed(48),
                        paddings: { left: 12, right: 12 },
                        content_alignment_vertical: 'center',
                        actions: [opAction('qr_next', 'pos.qr_step', { delta: 1 })],
                        items: [bodyText('Next', TOKENS.color.accent)],
                      }),
                    ],
                  }),
                ],
              }),
              new DivText({
                text: 'Customer scans each chunk in order. Auto-advances every 3 seconds.',
                font_size: 13,
                line_height: 18,
                text_color: TOKENS.color.muted,
                text_alignment_horizontal: 'center',
                margins: { top: 16, bottom: 20 },
              }),
              disclosure('Advanced', [
                new DivContainer({
                  orientation: 'horizontal',
                  width: matchParent(),
                  items: [
                    new DivContainer({
                      height: fixed(48),
                      paddings: { right: 20 },
                      content_alignment_vertical: 'center',
                      actions: [
                        opAction('regen_qr', 'pos.regenerate_qr', { order_id: '@{owner.cart_id}' }),
                      ],
                      items: [bodyText('Regenerate QR', TOKENS.color.accent)],
                    }),
                    new DivContainer({
                      height: fixed(48),
                      content_alignment_vertical: 'center',
                      actions: [
                        opAction('copy_qr_payload', 'pos.copy_qr_payload', {
                          order_id: '@{owner.cart_id}',
                        }),
                      ],
                      items: [bodyText('Copy payload', TOKENS.color.muted)],
                    }),
                  ],
                }),
              ]),
            ],
          }),
        },
      ],
    },
  )
}

export function buildOwnerInventoryCard() {
  return divCard(
    {},
    {
      log_id: 'owner_inventory',
      variables: [...ownerVariables(), ...productFormVariables()],
      states: [
        {
          state_id: 0,
          div: new DivContainer({
            orientation: 'vertical',
            width: matchParent(),
            height: matchParent(),
            background: [new DivSolidBackground({ color: TOKENS.color.canvas })],
            items: [
              toastBlock({
                visibleVar: 'owner.toast_visible',
                messageVar: 'owner.toast_msg',
                severityVar: 'owner.toast_severity',
                actionLabelVar: 'owner.toast_action',
                onAction: setVars('inv_toast_dismiss', { 'owner.toast_visible': false }),
              }),
              new DivContainer({
                orientation: 'vertical',
                width: matchParent(),
                height: weighted(1),
                items: [
                  new DivGallery({
                    orientation: 'vertical',
                    width: matchParent(),
                    height: matchParent(),
                    paddings: { left: 20, right: 20, top: 16, bottom: 88 },
                    items: [
                      displayText('Inventory'),
                      new DivContainer({
                        width: matchParent(),
                        margins: { top: 16, bottom: 12 },
                        items: [
                          textField({
                            id: 'inv_search',
                            label: '',
                            kind: 'text',
                            valueVar: 'owner.inv_query',
                            hint: 'Search name, SKU, or code',
                            onBlurOp: [loadInventory('search_inv')],
                            trailingAction: {
                              label: 'Search',
                              onTap: [loadInventory('search_inv')],
                            },
                          }),
                        ],
                      }),
                      new DivContainer({
                        visibility: expression("@{owner.inv_count == 0 ? 'visible' : 'gone'}"),
                        orientation: 'vertical',
                        width: matchParent(),
                        paddings: { top: 48, bottom: 48, left: 20, right: 20 },
                        content_alignment_horizontal: 'center',
                        items: [
                          titleText('No products yet'),
                          new DivText({
                            text: 'Add the first one to start scanning',
                            font_size: 16,
                            text_color: TOKENS.color.muted,
                            text_alignment_horizontal: 'center',
                            margins: { top: 8, bottom: 20 },
                          }),
                          new DivContainer({
                            height: fixed(48),
                            paddings: { left: 20, right: 20 },
                            border: { corner_radius: TOKENS.radius.button },
                            background: [new DivSolidBackground({ color: TOKENS.color.accentTint })],
                            content_alignment_horizontal: 'center',
                            content_alignment_vertical: 'center',
                            actions: openProductForm('empty_add_prod'),
                            items: [
                              new DivText({
                                text: 'Add product',
                                font_size: 16,
                                font_weight: 'bold',
                                text_color: TOKENS.color.accent,
                              }),
                            ],
                          }),
                        ],
                      }),
                      jsonList(
                        'owner.inv_items',
                        new DivContainer({
                          orientation: 'horizontal',
                          width: matchParent(),
                          height: fixed(76),
                          paddings: { left: 16, right: 16, top: 10, bottom: 10 },
                          background: [new DivSolidBackground({ color: TOKENS.color.surface })],
                          border: { corner_radius: TOKENS.radius.card },
                          alignment_vertical: 'center',
                          actions: openProductForm('edit_prod', true),
                          items: [
                            new DivImage({
                              image_url: '$item.photo_url',
                              width: fixed(48),
                              height: fixed(48),
                              border: { corner_radius: TOKENS.radius.photo },
                              placeholder_color: TOKENS.color.line,
                              margins: { right: 14 },
                            }),
                            new DivContainer({
                              orientation: 'vertical',
                              width: weighted(1),
                              items: [
                                bodyText('$item.name'),
                                labelText('$item.sku'),
                                new DivContainer({
                                  orientation: 'horizontal',
                                  alignment_vertical: 'center',
                                  items: [
                                    labelText(expression("$item.barcode != '' ? $item.barcode : $item.code")),
                                    new DivContainer({
                                      visibility: expression(
                                        "@{$item.barcode == '' && $item.code == '' ? 'visible' : 'gone'}",
                                      ),
                                      paddings: { left: 8 },
                                      actions: [
                                        {
                                          log_id: 'row_map_code',
                                          url: 'sheet://present?card_id=owner_scan_sheet',
                                        },
                                      ],
                                      items: [labelText('Map', TOKENS.color.accent)],
                                    }),
                                  ],
                                }),
                              ],
                            }),
                            new DivContainer({
                              orientation: 'vertical',
                              alignment_horizontal: 'end',
                              items: [
                                titleText(
                                  expression(
                                    "'$' + $item.price + ($item.price * 1 == round($item.price * 1) ? '.00' : $item.price * 10 == round($item.price * 10) ? '0' : '')",
                                  ),
                                  TOKENS.color.ink,
                                  true,
                                ),
                                statusChip(
                                  expression(
                                    "$item.stock == 0 ? 'Out of stock' : $item.stock + ' in stock'",
                                  ),
                                  'warning',
                                ),
                              ],
                            }),
                          ],
                        }),
                        12,
                        '',
                      ),
                    ],
                  }),
                  fab({
                    icon: 'add',
                    accessibilityLabel: 'Add product',
                    onTap: openProductForm('fab_add_prod'),
                  }),
                ],
              }),
              bottomTabs('owner.tab', [
                { id: 'pos', label: 'POS', icon: 'pos', card: 'owner_pos' },
                {
                  id: 'inventory',
                  label: 'Inventory',
                  icon: 'inventory',
                  card: 'owner_inventory',
                  actions: [loadInventory()],
                },
              ]),
            ],
          }),
        },
      ],
    },
  )
}

export function buildProductModalCard() {
  return divCard(
    {},
    {
      log_id: 'product_modal',
      variables: productFormVariables(),
      states: [
        {
          state_id: 0,
          div: formModal({
            id: 'product_form_modal',
            titleExpr: "@{product.is_edit ? 'Edit product' : 'New product'}",
            dirtyVar: 'product.dirty',
            validVar: 'product.valid',
            onCancel: [{ log_id: 'cancel_product_form', url: 'nav://pop' }],
            onSave: [
              upsertAction(
                'save_product_form',
                'inventory',
                {
                  name: '@{product.name}',
                  sku: '@{product.sku}',
                  price: '@{product.price}',
                  code: '@{product.code}',
                  barcode: '@{product.code}',
                  description: '@{product.description}',
                  photo_url: '@{product.photo_url}',
                  stock: '@{product.is_edit ? product.stock : 0}',
                },
                { id: '@{product.id}', output: { errors_json: 'product.errors_json' } },
              ),
              loadInventory('reload_after_save'),
              { log_id: 'close_product_form', url: 'nav://pop' },
            ],
            children: [
              new DivContainer({
                orientation: 'vertical',
                width: matchParent(),
                paddings: { left: 20, right: 20, top: 20, bottom: 32 },
                items: [
                  new DivContainer({ margins: { bottom: 24 }, items: [photoPicker('product.photo_url')] }),
                  textField({
                    id: 'field_name',
                    label: 'Name',
                    kind: 'text',
                    valueVar: 'product.name',
                    hint: 'Latte',
                    maxLength: 60,
                    errorExpr: 'product.errors.name',
                  }),
                  textField({
                    id: 'field_price',
                    label: 'Price',
                    kind: 'numeric',
                    prefix: '$',
                    valueVar: 'product.price',
                    hint: '0.00',
                    errorExpr: 'product.errors.price',
                  }),
                  textField({
                    id: 'field_code',
                    label: 'Code',
                    kind: 'code',
                    valueVar: 'product.code',
                    hint: 'Barcode or QR value',
                    helper: 'Scan the barcode or type it in',
                    scannedChipVar: 'product.code_scanned',
                    errorExpr: 'product.errors.code',
                    onScanTap: [
                      { log_id: 'open_code_scan_sheet', url: 'sheet://present?card_id=owner_scan_sheet' },
                    ],

                  }),
                  inlineBanner({
                    id: 'save_error_banner',
                    visibleIf: "product.errors_json != ''",
                    severity: 'danger',
                    messageExpr: '@{product.errors_json}',
                    secondaryAction: {
                      label: 'Dismiss',
                      onTap: [setVarAction('dismiss_save_error', 'product.errors_json', '')],
                    },
                  }),
                  textField({
                    id: 'field_sku',
                    label: 'SKU',
                    kind: 'text',
                    valueVar: 'product.sku',
                    hint: 'LAT-01',
                    errorExpr: 'product.errors.sku',
                  }),
                  textField({
                    id: 'field_desc',
                    label: 'Description',
                    kind: 'multiline',
                    valueVar: 'product.description',
                    hint: 'Optional notes',
                    helper: 'Up to 240 characters',
                    maxLength: 240,
                  }),
                  new DivContainer({
                    visibility: expression("@{product.is_edit ? 'visible' : 'gone'}"),
                    width: matchParent(),
                    height: fixed(48),
                    margins: { top: 8 },
                    content_alignment_horizontal: 'center',
                    content_alignment_vertical: 'center',
                    actions: [
                      { log_id: 'confirm_delete_prod', url: 'nav://push?id=confirm_delete_product' },
                    ],
                    items: [
                      new DivText({
                        text: 'Delete product',
                        font_size: 16,
                        text_color: TOKENS.color.muted,
                      }),
                    ],
                  }),
                ],
              }),
            ],
          }),
        },
      ],
    },
  )
}

export function buildScanSheetCard() {
  return divCard(
    {},
    {
      log_id: 'owner_scan_sheet',
      variables: productFormVariables(),
      states: [
        {
          state_id: 0,
          div: bottomSheet(
            'owner_scan_sheet',
            'Scan code',
            [
              cameraViewfinder({
                id: 'sheet_viewfinder',
                cutWindowShape: 'rect',
                hintExpr: 'Center the barcode',
                unavailableCopy: 'Camera unavailable on this device — enter the code manually',
                onScanOp: 'inventory.scan_resolve',
                onScanArgs: { raw: '$scan.raw' },
              }),
              new DivContainer({
                margins: { top: 20 },
                width: matchParent(),
                items: [
                  textField({
                    id: 'sheet_manual_code',
                    label: 'Manual entry',
                    kind: 'text',
                    valueVar: 'product.scan_pending_code',
                    hint: 'Enter barcode or SKU',
                    trailingAction: {
                      label: 'Use',
                      onTap: [
                        ...setVars('use_scanned_code', {
                          'product.code': '@{product.scan_pending_code}',
                          'product.code_scanned': true,
                        }),
                        { log_id: 'pop_scan_sheet', url: 'nav://pop' },
                      ],
                    },
                  }),
                ],
              }),
            ],
            0.9,
          ),
        },
      ],
    },
  )
}

export function buildCustomerShopCard() {
  return divCard(
    {},
    {
      log_id: 'customer_shop',
      variables: customerVariables(),
      states: [
        {
          state_id: 0,
          div: new DivContainer({
            orientation: 'vertical',
            width: matchParent(),
            height: matchParent(),
            background: [new DivSolidBackground({ color: TOKENS.color.canvas })],
            items: [
              new DivContainer({
                width: matchParent(),
                height: weighted(1),
                items: [
                  new DivGallery({
                    orientation: 'vertical',
                    width: matchParent(),
                    height: matchParent(),
                    paddings: { left: 20, right: 20, top: 20, bottom: 88 },
                    items: [
                      displayText('@{cust.shop_name}'),
                      labelText('@{cust.shop_terminal}'),
                      new DivContainer({
                        visibility: expression("@{cust.has_recent_order ? 'visible' : 'gone'}"),
                        width: matchParent(),
                        margins: { top: 24 },
                        paddings: { left: 16, right: 16, top: 16, bottom: 16 },
                        background: [new DivSolidBackground({ color: TOKENS.color.surface })],
                        border: { corner_radius: TOKENS.radius.card },
                        items: [
                          labelText('Your last order'),
                          new DivContainer({
                            orientation: 'horizontal',
                            width: matchParent(),
                            margins: { top: 8 },
                            alignment_vertical: 'center',
                            items: [
                              new DivContainer({
                                orientation: 'vertical',
                                width: weighted(1),
                                items: [
                                  titleText('@{cust.recent_order.short_id}'),
                                  labelText('@{cust.recent_order.subtitle}'),
                                ],
                              }),
                              titleText('@{cust.recent_order.total}', TOKENS.color.ink, true),
                            ],
                          }),
                          new DivContainer({
                            height: fixed(48),
                            margins: { top: 8 },
                            content_alignment_vertical: 'center',
                            actions: [{ log_id: 'view_recent_hist', url: 'nav://push?id=customer_history' }],
                            items: [bodyText('View', TOKENS.color.accent)],
                          }),
                        ],
                      }),
                      new DivContainer({
                        visibility: expression("@{cust.catalog_count == 0 ? 'visible' : 'gone'}"),
                        width: matchParent(),
                        paddings: { top: 56, bottom: 24 },
                        content_alignment_horizontal: 'center',
                        items: [
                          new DivText({
                            text: 'Nothing yet — tap scan to receive your order ↘',
                            font_size: 16,
                            text_color: TOKENS.color.muted,
                            text_alignment_horizontal: 'center',
                          }),
                        ],
                      }),
                      new DivContainer({
                        visibility: expression("@{cust.catalog_count > 0 ? 'visible' : 'gone'}"),
                        width: matchParent(),
                        margins: { top: 24 },
                        items: [
                          disclosure('Catalog preview', [
                            jsonList(
                              'cust.catalog_preview',
                              new DivContainer({
                                orientation: 'horizontal',
                                width: matchParent(),
                                paddings: { top: 10, bottom: 10 },
                                items: [
                                  bodyText('$item.name'),
                                  new DivContainer({ width: weighted(1), items: [] }),
                                  bodyText('$item.price_display'),
                                ],
                              }),
                              4,
                            ),
                          ]),
                        ],
                      }),
                    ],
                  }),
                  fab({
                    icon: 'qr_code_scanner',
                    accessibilityLabel: 'Scan order',
                    onTap: [{ log_id: 'push_cust_scan', url: 'nav://push?id=customer_scan' }],
                  }),
                ],
              }),
              bottomTabs('cust.tab', [
                { id: 'shop', label: 'Shop', icon: 'storefront', card: 'customer_shop' },
                { id: 'history', label: 'History', icon: 'receipt_long', card: 'customer_history' },
              ]),
            ],
          }),
        },
      ],
    },
  )
}

export function buildCustomerHistoryCard() {
  return divCard(
    {},
    {
      log_id: 'customer_history',
      variables: customerVariables(),
      states: [
        {
          state_id: 0,
          div: new DivContainer({
            orientation: 'vertical',
            width: matchParent(),
            height: matchParent(),
            background: [new DivSolidBackground({ color: TOKENS.color.canvas })],
            items: [
              new DivContainer({
                width: matchParent(),
                height: weighted(1),
                items: [
                  new DivGallery({
                    orientation: 'vertical',
                    width: matchParent(),
                    height: matchParent(),
                    paddings: { left: 20, right: 20, top: 20, bottom: 88 },
                    items: [
                      displayText('History'),
                      new DivContainer({
                        visibility: expression("@{cust.history_count == 0 ? 'visible' : 'gone'}"),
                        width: matchParent(),
                        paddings: { top: 56 },
                        items: [
                          new DivText({
                            text: 'No orders yet — tap scan to receive your first one.',
                            font_size: 16,
                            text_color: TOKENS.color.muted,
                            text_alignment_horizontal: 'center',
                          }),
                        ],
                      }),
                      jsonList(
                        'cust.history',
                        new DivContainer({
                          orientation: 'horizontal',
                          width: matchParent(),
                          paddings: { left: 16, right: 16, top: 14, bottom: 14 },
                          background: [new DivSolidBackground({ color: TOKENS.color.surface })],
                          border: { corner_radius: TOKENS.radius.card },
                          alignment_vertical: 'center',
                          actions: [
                            opAction('open_hist_detail', 'cust.open_history_detail', { id: '$item.id' }),
                          ],
                          items: [
                            new DivContainer({
                              orientation: 'vertical',
                              width: weighted(1),
                              items: [bodyText('$item.short_id'), labelText('$item.meta_line')],
                            }),
                            titleText('$item.total', TOKENS.color.ink, true),
                          ],
                        }),
                        12,
                      ),
                    ],
                  }),
                  fab({
                    icon: 'qr_code_scanner',
                    accessibilityLabel: 'Scan order',
                    onTap: [{ log_id: 'hist_push_scan', url: 'nav://push?id=customer_scan' }],
                  }),
                ],
              }),
              bottomTabs('cust.tab', [
                { id: 'shop', label: 'Shop', icon: 'storefront', card: 'customer_shop' },
                { id: 'history', label: 'History', icon: 'receipt_long', card: 'customer_history' },
              ]),
            ],
          }),
        },
      ],
    },
  )
}

export function buildCustomerScanCard() {
  return divCard(
    {},
    {
      log_id: 'customer_scan',
      variables: customerVariables(),
      states: [
        {
          state_id: 0,
          div: new DivContainer({
            orientation: 'vertical',
            width: matchParent(),
            height: matchParent(),
            background: [new DivSolidBackground({ color: TOKENS.color.canvas })],
            paddings: { left: 20, right: 20, top: 16, bottom: 24 },
            items: [
              new DivContainer({
                visibility: expression(
                  "@{cust.scan_state == 'idle' || cust.scan_state == 'scanning' ? 'visible' : 'gone'}",
                ),
                orientation: 'vertical',
                width: matchParent(),
                items: [
                  cameraViewfinder({
                    id: 'cust_viewfinder',
                    cutWindowShape: 'square',
                    cutWindowSizeDp: 240,
                    hintExpr: '@{cust.scan_hint}',
                    unavailableCopy: 'Camera unavailable on this device — enter the order chunk manually',
                    onScanOp: 'cust.scan_append',
                    onScanArgs: { chunk_string: '$scan.raw' },
                  }),
                  new DivContainer({
                    margins: { top: 20 },
                    content_alignment_horizontal: 'center',
                    items: [
                      progressRing({
                        currentVar: 'cust.scan_current',
                        totalVar: 'cust.scan_total',
                        stateVar: 'cust.scan_state',
                      }),
                      new DivText({
                        text: expression('@{cust.scan_ring_label}'),
                        font_size: 16,
                        font_weight: 'bold',
                        text_color: TOKENS.color.ink,
                        text_alignment_horizontal: 'center',
                        margins: { top: 8 },
                      }),
                    ],
                  }),
                  new DivContainer({
                    margins: { top: 16 },
                    items: [
                      scanProgressChips({
                        chunksVar: 'cust.scan_chunks',
                        collectedVar: 'cust.scan_collected',
                        corruptVar: 'cust.scan_corrupt',
                      }),
                    ],
                  }),
                ],
              }),
              new DivContainer({
                visibility: expression("@{cust.scan_state == 'assembling' ? 'visible' : 'gone'}"),
                orientation: 'vertical',
                width: matchParent(),
                paddings: { top: 96 },
                content_alignment_horizontal: 'center',
                items: [
                  titleText('Verifying checksum…'),
                  new DivContainer({
                    width: matchParent(),
                    margins: { top: 16 },
                    items: [progressBar('cust.scan_current', 'cust.scan_total')],
                  }),
                ],
              }),
              new DivContainer({
                visibility: expression("@{cust.scan_state == 'verified' ? 'visible' : 'gone'}"),
                orientation: 'vertical',
                width: matchParent(),
                height: weighted(1),
                items: [
                  displayText('✓ Order received', TOKENS.color.success),
                  titleText('@{cust.received_order.short_id}'),
                  labelText('@{cust.received_order.summary}'),
                  new DivContainer({
                    margins: { top: 12, bottom: 20 },
                    paddings: { left: 12, right: 12, top: 6, bottom: 6 },
                    border: { corner_radius: TOKENS.radius.pill },
                    background: [new DivSolidBackground({ color: TOKENS.color.successTint })],
                    items: [labelText('✓ Checksum verified', TOKENS.color.success)],
                  }),
                  jsonList(
                    'cust.received_order.lines',
                    new DivContainer({
                      orientation: 'horizontal',
                      width: matchParent(),
                      paddings: { top: 8, bottom: 8 },
                      items: [
                        new DivText({
                          text: '$item.name_and_qty',
                          width: weighted(1),
                          font_size: 16,
                          text_color: TOKENS.color.ink,
                        }),
                        titleText('$item.line_total_display', TOKENS.color.ink, true),
                      ],
                    }),
                    4,
                  ),
                  new DivContainer({ width: weighted(1), height: weighted(1), items: [] }),
                  new DivContainer({
                    width: matchParent(),
                    height: fixed(56),
                    border: { corner_radius: TOKENS.radius.button },
                    background: [new DivSolidBackground({ color: TOKENS.color.accent })],
                    content_alignment_horizontal: 'center',
                    content_alignment_vertical: 'center',
                    actions: [
                      opAction('save_cust_order', 'cust.save_order'),
                      { log_id: 'save_to_history', url: 'nav://push?id=customer_history' },
                    ],
                    items: [
                      new DivText({
                        text: 'Save to history',
                        font_size: 16,
                        font_weight: 'bold',
                        text_color: TOKENS.color.surface,
                      }),
                    ],
                  }),
                ],
              }),
              new DivContainer({
                visibility: expression("@{cust.scan_state == 'error' ? 'visible' : 'gone'}"),
                orientation: 'vertical',
                width: matchParent(),
                paddings: { top: 64 },
                items: [
                  displayText('✗ Checksum mismatch', TOKENS.color.danger),
                  bodyText(
                    'The order did not arrive intact. Ask the owner to regenerate the QR and rescan.',
                    TOKENS.color.muted,
                  ),
                  new DivContainer({
                    orientation: 'horizontal',
                    width: matchParent(),
                    margins: { top: 24 },
                    items: [
                      new DivContainer({
                        height: fixed(48),
                        paddings: { right: 20 },
                        content_alignment_vertical: 'center',
                        actions: [{ log_id: 'cancel_scan', url: 'nav://pop' }],
                        items: [bodyText('Cancel', TOKENS.color.muted)],
                      }),
                      new DivContainer({
                        width: weighted(1),
                        height: fixed(56),
                        border: { corner_radius: TOKENS.radius.button },
                        background: [new DivSolidBackground({ color: TOKENS.color.accent })],
                        content_alignment_horizontal: 'center',
                        content_alignment_vertical: 'center',
                        actions: [opAction('rescan_order', 'cust.scan_reset')],
                        items: [
                          new DivText({
                            text: 'Rescan',
                            font_size: 16,
                            font_weight: 'bold',
                            text_color: TOKENS.color.surface,
                          }),
                        ],
                      }),
                    ],
                  }),
                ],
              }),
              disclosure('Advanced', [
                new DivContainer({
                  orientation: 'horizontal',
                  width: matchParent(),
                  items: [
                    new DivContainer({
                      height: fixed(48),
                      paddings: { right: 12 },
                      actions: [opAction('demo_inject', 'cust.demo_inject_chunk')],
                      items: [labelText('Inject chunk', TOKENS.color.accent)],
                    }),
                    new DivContainer({
                      height: fixed(48),
                      paddings: { right: 12 },
                      actions: [opAction('demo_corrupt', 'cust.demo_inject_corrupt')],
                      items: [labelText('Inject corrupt chunk', TOKENS.color.danger)],
                    }),
                    new DivContainer({
                      height: fixed(48),
                      actions: [opAction('demo_reset', 'cust.scan_reset')],
                      items: [labelText('Reset', TOKENS.color.muted)],
                    }),
                  ],
                }),
              ]),
            ],
          }),
        },
      ],
    },
  )
}

/** Browse is a plain collection query: the card decides table, filter and target vars. */
const loadInventory = (logId = 'load_inventory'): DivAction =>
  queryAction(logId, 'inventory', {
    filter: {
      $or: [
        { name: { $contains: '@{owner.inv_query}' } },
        { sku: { $contains: '@{owner.inv_query}' } },
        { barcode: { $contains: '@{owner.inv_query}' } },
        { code: { $contains: '@{owner.inv_query}' } },
      ],
    },
    orderBy: 'name',
    limit: 100,
    listVar: 'owner.inv_items',
    countVar: 'owner.inv_count',
  })

/** Seeds the product form from a row (or blanks it) with in-card `set_variable` actions. */
const openProductForm = (logId: string, fromRow = false): DivAction[] => [
  ...(fromRow
    ? setVars(logId, {
        'product.id': '@{item.id}',
        'product.name': '@{item.name}',
        'product.sku': '@{item.sku}',
        'product.price': '@{item.price}',
        'product.stock': '@{item.stock}',
        'product.code': "@{item.barcode != '' ? item.barcode : item.code}",
        'product.photo_url': '@{item.photo_url}',
        'product.description': '@{item.description}',
        'product.is_edit': true,
      })
    : setVars(logId, {
        'product.id': '',
        'product.name': '',
        'product.sku': '',
        'product.price': '',
        'product.stock': 0,
        'product.code': '',
        'product.photo_url': '',
        'product.description': '',
        'product.is_edit': false,
      })),
  ...setVars(`${logId}_reset`, {
    'product.errors_json': '',
    'product.dirty': false,
    'product.valid': true,
  }),
  { log_id: 'open_product_modal', url: 'nav://push?id=product_modal' },
]

function navRow(
  title: string,
  subtitle: string,
  targetId: string,
  icon: string,
  extraActions: DivAction[] = [],
) {
  return new DivContainer({
    orientation: 'horizontal',
    width: matchParent(),
    height: fixed(56),
    content_alignment_vertical: 'center',
    actions: [
      { log_id: `nav_${targetId}`, url: `nav://push?id=${targetId}` },
      ...extraActions,
    ],
    items: [
      new DivContainer({
        orientation: 'vertical',
        width: weighted(1),
        items: [
          new DivText({ text: title, font_size: 16, font_weight: 'bold', text_color: TOKENS.color.ink }),
          new DivText({ text: subtitle, font_size: 12, text_color: TOKENS.color.muted, margins: { top: 2 } }),
        ],
      }),
      new DivText({ text: '›', font_size: 24, text_color: TOKENS.color.muted }),
    ],
  })
}

export function buildHomeCard() {
  return divCard(
    {},
    {
      log_id: 'home',
      variables: [...ownerVariables(), ...productFormVariables(), ...customerVariables()],
      states: [
        {
          state_id: 0,
          div: new DivContainer({
            orientation: 'vertical',
            width: matchParent(),
            height: matchParent(),
            background: [new DivSolidBackground({ color: TOKENS.color.canvas })],
            items: [
              new DivContainer({
                orientation: 'vertical',
                width: matchParent(),
                paddings: { left: 20, right: 20, top: 24, bottom: 20 },
                background: [new DivSolidBackground({ color: TOKENS.color.surface })],
                items: [
                  titleText('APEX POS & Handoff'),
                  new DivText({
                    text: 'Owner POS terminal and customer verification',
                    font_size: 14,
                    text_color: TOKENS.color.muted,
                    margins: { top: 4 },
                  }),
                ],
              }),
              new DivContainer({
                orientation: 'vertical',
                width: matchParent(),
                paddings: { left: 20, right: 20, top: 20, bottom: 20 },
                items: [
                  titleText('Owner Operations'),
                  new DivContainer({
                    orientation: 'vertical',
                    width: matchParent(),
                    margins: { top: 12 },
                    background: [new DivSolidBackground({ color: TOKENS.color.surface })],
                    border: { corner_radius: TOKENS.radius.card },
                    paddings: { left: 16, right: 16, top: 8, bottom: 8 },
                    items: [
                      navRow('Point of Sale', 'Cart, barcode scan & checkout', 'owner_pos', 'pos'),
                      divider(),
                      navRow('Inventory', 'Manage catalog, stock & pricing', 'owner_inventory', 'box', [
                        loadInventory(),
                      ]),
                      divider(),
                      navRow('QR Handoff', 'Deterministic QR sequence', 'owner_qr_sequence', 'qr'),
                    ],
                  }),
                  new DivContainer({
                    margins: { top: 24 },
                    items: [titleText('Customer Handoff')],
                  }),
                  new DivContainer({
                    orientation: 'vertical',
                    width: matchParent(),
                    margins: { top: 12 },
                    background: [new DivSolidBackground({ color: TOKENS.color.surface })],
                    border: { corner_radius: TOKENS.radius.card },
                    paddings: { left: 16, right: 16, top: 8, bottom: 8 },
                    items: [
                      navRow('Customer Shop', 'Browse items & start order', 'customer_shop', 'shop'),
                      divider(),
                      navRow('Customer History', 'Past orders & receipts', 'customer_history', 'history'),
                      divider(),
                      navRow('Scan Order', 'Verify QR order chunks', 'customer_scan', 'scan'),
                    ],
                  }),
                ],
              }),
            ],
          }),
        },
      ],
    },
  )
}

export function buildMapBarcodePickerCard() {
  return divCard(
    {},
    {
      log_id: 'map_barcode_picker',
      variables: ownerVariables(),
      states: [
        {
          state_id: 0,
          div: bottomSheet('map_barcode_picker_sheet', 'Map Barcode to Product', [
            new DivText({
              text: 'Select an inventory item to assign the scanned barcode.',
              font_size: 14,
              text_color: TOKENS.color.muted,
              margins: { bottom: 12 },
            }),
            jsonList(
              'owner.inventory_list',
              new DivContainer({
                orientation: 'horizontal',
                width: matchParent(),
                height: fixed(56),
                content_alignment_vertical: 'center',
                actions: [
                  opAction('map_barcode_item', 'pos.map_barcode', {
                    raw: expression('owner.scanned_code'),
                    sku: expression('item.sku'),
                  }),
                  { log_id: 'pop_after_map', url: 'nav://pop' },
                ],
                items: [
                  new DivContainer({
                    orientation: 'vertical',
                    width: weighted(1),
                    items: [
                      new DivText({ text: expression('item.name'), font_size: 16, font_weight: 'bold', text_color: TOKENS.color.ink }),
                      new DivText({ text: expression('item.sku'), font_size: 12, text_color: TOKENS.color.muted }),
                    ],
                  }),
                  new DivText({ text: expression('item.price'), font_size: 14, font_weight: 'bold', text_color: TOKENS.color.accent }),
                ],
              }),
            ),
          ]),
        },
      ],
    },
  )
}

export function buildConfirmDeleteProductCard() {
  return divCard(
    {},
    {
      log_id: 'confirm_delete_product',
      variables: productFormVariables(),
      states: [
        {
          state_id: 0,
          // Pushed as a workspace page (not a host sheet) so its pops unwind the page stack the
          // product modal lives on: Cancel closes the dialog, Delete closes dialog + modal.
          div: bottomSheet('confirm_delete_product_sheet', 'Delete Product?', [
            new DivText({
              text: 'This product will be permanently removed unless referenced by past orders.',
              font_size: 14,
              text_color: TOKENS.color.danger,
              margins: { bottom: 16 },
            }),
            new DivContainer({
              orientation: 'horizontal',
              width: matchParent(),
              height: fixed(56),
              items: [
                new DivContainer({
                  width: weighted(1),
                  height: fixed(48),
                  border: { corner_radius: TOKENS.radius.button, stroke: { color: TOKENS.color.line, width: 1 } },
                  content_alignment_horizontal: 'center',
                  content_alignment_vertical: 'center',
                  actions: [{ log_id: 'cancel_delete', url: 'nav://pop' }],
                  items: [bodyText('Cancel', TOKENS.color.ink)],
                }),
                new DivContainer({
                  width: weighted(1),
                  height: fixed(48),
                  margins: { left: 12 },
                  border: { corner_radius: TOKENS.radius.button },
                  background: [new DivSolidBackground({ color: TOKENS.color.danger })],
                  content_alignment_horizontal: 'center',
                  content_alignment_vertical: 'center',
                  actions: [
                    deleteAction('delete_product', 'inventory', '@{product.id}'),
                    loadInventory('reload_after_delete'),
                    // Pop dismisses the confirm sheet, the second pop closes the edit modal it
                    // was opened from, so a deleted product's form cannot be re-saved.
                    { log_id: 'pop_after_delete', url: 'nav://pop' },
                    { log_id: 'pop_after_delete_form', url: 'nav://pop' },
                  ],
                  items: [new DivText({ text: 'Delete', font_size: 16, font_weight: 'bold', text_color: TOKENS.color.surface })],
                }),
              ],
            }),
          ], 0.65, 'nav://pop'),
        },
      ],
    },
  )
}
