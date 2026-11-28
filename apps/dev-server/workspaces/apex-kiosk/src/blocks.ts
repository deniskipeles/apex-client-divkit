import {
  DivContainer,
  DivImage,
  DivInput,
  DivSeparator,
  DivSolidBackground,
  DivText,
  expression as rawExpression,
  fixed,
  matchParent,
  weighted,
  wrapContent,
  type IDivAction,
} from '@divkitframework/jsonbuilder'
import { TOKENS } from './tokens.ts'

const expression = (body: string) => rawExpression(body.trim().startsWith('@{') ? body : `@{${body}}`)

export type Div = any
export type DivAction = IDivAction

export const opAction = (
  logId: string,
  op: string,
  params: Record<string, any> = {},
): DivAction => {
  const qs = Object.entries(params)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join('&')
  return { log_id: logId, url: qs ? `local-db://op/${op}?${qs}` : `local-db://op/${op}` }
}

export const setVarAction = (logId: string, name: string, value: any): DivAction => ({
  log_id: logId,
  url: `div-action://set_variable?name=${encodeURIComponent(name)}&value=${encodeURIComponent(String(value))}`,
})

/**
 * General local-db query. Rows land in `listVar` (a `json_list` reads them), the row count in
 * `countVar`. Nothing here knows what a "product" is - the card supplies table, filter and the
 * variables it wants back.
 */
export const queryAction = (
  logId: string,
  table: string,
  opts: {
    filter?: unknown
    orderBy?: string
    limit?: number
    listVar?: string
    countVar?: string
  } = {},
): DivAction => {
  const payload: Record<string, unknown> = { table }
  if (opts.filter !== undefined) payload.filter = opts.filter
  if (opts.orderBy) payload.order_by = opts.orderBy
  if (opts.limit !== undefined) payload.limit = opts.limit
  if (opts.listVar) payload.json_list_var = opts.listVar
  if (opts.countVar) payload.output = { _count: opts.countVar }
  return { log_id: logId, url: 'local-db://op/db.query', payload } as DivAction
}

/** General local-db delete of one record id. */
export const deleteAction = (logId: string, table: string, id: string): DivAction =>
  ({ log_id: logId, url: 'local-db://op/db.delete', payload: { table, id } }) as DivAction

/** General local-db upsert. A blank id inserts; schema validation answers in `output`. */
export const upsertAction = (
  logId: string,
  table: string,
  data: Record<string, unknown>,
  opts: { id?: string; output?: Record<string, string> } = {},
): DivAction =>
  ({
    log_id: logId,
    url: 'local-db://op/db.upsert',
    payload: {
      table,
      data,
      ...(opts.id ? { id: opts.id } : {}),
      ...(opts.output ? { output: opts.output } : {}),
    },
  }) as DivAction

/**
 * Native `set_variable` actions - they run inside the card, so no engine op is involved.
 * String values may hold `@{...}` expressions, including row fields (`@{item.name}`).
 */
export const setVars = (
  logId: string,
  values: Record<string, string | number | boolean>,
): DivAction[] =>
  Object.entries(values).map(
    ([name, value]) =>
      ({
        log_id: `${logId}_${name.replace(/[^a-zA-Z0-9]+/g, '_')}`,
        type: 'set_variable',
        variable_name: name,
        value:
          typeof value === 'number'
            ? { type: 'integer', value: String(value) }
            : typeof value === 'boolean'
              ? { type: 'boolean', value }
              : { type: 'string', value },
      }) as unknown as DivAction,
  )

export const displayText = (text: string, color: string = TOKENS.color.ink) =>
  new DivText({ text, font_size: 32, line_height: 40, font_weight: 'bold', text_color: color })

export const titleText = (text: string, color: string = TOKENS.color.ink, tabular = false) =>
  new DivText({
    text,
    font_size: 22,
    line_height: 28,
    font_weight: 'bold',
    text_color: color,
    ...(tabular ? { font_feature_settings: 'tnum' } : {}),
  })

export const bodyText = (text: string, color: string = TOKENS.color.ink) =>
  new DivText({ text, font_size: 16, line_height: 22, font_weight: 'regular', text_color: color })

export const labelText = (text: string, color: string = TOKENS.color.muted) =>
  new DivText({ text, font_size: 13, line_height: 18, font_weight: 'medium', text_color: color })

export function bottomTabs(
  selectedVar: string,
  tabs: Array<{ id: string; label: string; icon: string; card: string; actions?: DivAction[] }>,
): Div {
  return {
    type: 'bottom_tabs',
    custom_props: {
      active_value: expression(selectedVar),
      items: tabs.map((tab) => ({
        value: tab.id,
        label: tab.label,
        icon: tab.icon,
        actions: [
          { log_id: `tab_${tab.id}`, url: `nav://push?id=${tab.card}` },
          ...(tab.actions ?? []),
        ],
      })),
    },
  }
}
export function stickyFooter(props: {
  summaryExpr: string
  actionLabel: string
  enabledIf: string
  onTap: DivAction[]
}): Div {
  return new DivContainer({
    orientation: 'horizontal',
    width: matchParent(),
    background: [new DivSolidBackground({ color: TOKENS.color.surface })],
    paddings: { left: 20, right: 20, top: 12, bottom: 12 },
    alignment_vertical: 'center',
    border: { stroke: { color: TOKENS.color.line, width: 1 } },
    items: [
      new DivText({
        text: expression(props.summaryExpr),
        width: weighted(1),
        font_size: 22,
        line_height: 28,
        font_weight: 'bold',
        text_color: TOKENS.color.ink,
        font_feature_settings: 'tnum',
      }),
      new DivContainer({
        width: fixed(160),
        height: fixed(TOKENS.touch.primary),
        border: { corner_radius: TOKENS.radius.button },
        background: [
          new DivSolidBackground({
            color: expression(`@{${props.enabledIf} ? '${TOKENS.color.accent}' : '${TOKENS.color.line}'}`),
          }),
        ],
        alpha: expression(`@{${props.enabledIf} ? 1.0 : 0.4}`),
        content_alignment_horizontal: 'center',
        content_alignment_vertical: 'center',
        actions: props.onTap,
        items: [
          new DivText({
            text: props.actionLabel,
            font_size: 16,
            font_weight: 'bold',
            text_alignment_horizontal: 'center',
            text_color: expression(
              `@{${props.enabledIf} ? '${TOKENS.color.surface}' : '${TOKENS.color.muted}'}`,
            ),
          }),
        ],
      }),
    ],
  })
}

export function segmented(
  selectedVar: string,
  segments: Array<{ id: string; label: string }>,
): Div {
  return new DivContainer({
    orientation: 'horizontal',
    width: matchParent(),
    height: fixed(48),
    background: [new DivSolidBackground({ color: TOKENS.color.line })],
    border: { corner_radius: TOKENS.radius.button },
    paddings: { top: 4, bottom: 4, left: 4, right: 4 },
    items: segments.map(
      (seg) =>
        new DivContainer({
          width: weighted(1),
          height: matchParent(),
          border: { corner_radius: 10 },
          background: [
            new DivSolidBackground({
              color: expression(`@{${selectedVar} == '${seg.id}' ? '${TOKENS.color.surface}' : '#00000000'}`),
            }),
          ],
          content_alignment_horizontal: 'center',
          content_alignment_vertical: 'center',
          actions: [setVarAction(`seg_${seg.id}`, selectedVar, seg.id)],
          items: [
            new DivText({
              text: seg.label,
              font_size: 13,
              line_height: 18,
              font_weight: 'medium',
              text_alignment_horizontal: 'center',
              text_color: expression(
                `@{${selectedVar} == '${seg.id}' ? '${TOKENS.color.ink}' : '${TOKENS.color.muted}'}`,
              ),
            }),
          ],
        }),
    ),
  })
}

export function fab(props: { icon: string; accessibilityLabel: string; onTap: DivAction[] }): Div {
  return {
    type: 'fab',
    custom_props: { label: props.accessibilityLabel, icon: props.icon, actions: props.onTap },
  }
}
export function bottomSheet(
  id: string,
  title: string,
  children: Div[],
  heightPct = 0.65,
  dismissUrl = 'sheet://dismiss',
): Div {
  return {
    id,
    type: 'bottom_sheet',
    custom_props: {
      visible: true,
      title,
      body: children,
      height_fraction: heightPct,
      dismiss: { actions: [{ log_id: 'sheet_dismiss', url: dismissUrl }] },
    },
  }
}
export function cameraViewfinder(props: {
  id: string
  modeVar?: string
  cutWindowShape?: string
  cutWindowSizeDp?: number
  hintExpr: string
  collapsedIf?: string
  unavailableCopy: string
  manualAction?: DivAction[]
  onScanOp: string
  onScanArgs?: Record<string, string>
}): Div {
  return {
    id: props.id,
    type: 'camera_viewfinder',
    custom_props: {
      hint: props.hintExpr,
      unavailable_copy: props.unavailableCopy,
      mode_var: props.modeVar ?? '',
      on_scan_op: props.onScanOp,
      on_scan_args: props.onScanArgs ?? {},
    },
    ...(props.manualAction ? { actions: props.manualAction } : {}),
  }
}
export function qrSequenceViewer(props: {
  chunksVar: string
  indexVar: string
  autoMs: number
  autoVar: string
}): Div {
  return {
    type: 'qr_controls',
    custom_props: {
      index: expression(props.indexVar),
      count: expression(props.autoVar),
      chunks_var: props.chunksVar,
      auto_ms: props.autoMs,
    },
  }
}
export function progressRing(props: { currentVar: string; totalVar: string; stateVar: string }): Div {
  return {
    type: 'progress_ring',
    custom_props: {
      value: expression(`${props.currentVar} / (${props.totalVar} || 1)`),
      label: expression(props.currentVar),
      caption: expression(props.totalVar),
      state_var: props.stateVar,
    },
  }
}
export function scanProgressChips(props: {
  chunksVar: string
  collectedVar: string
  corruptVar: string
}): Div {
  return {
    type: 'scan_chips',
    custom_props: {
      chunks_var: props.chunksVar,
      expected: expression(`${props.chunksVar}.length`),
      collected_var: props.collectedVar,
      corrupt_var: props.corruptVar,
    },
  }
}
export function formModal(props: {
  id: string
  titleExpr: string
  dirtyVar: string
  validVar: string
  onCancel: DivAction[]
  onSave: DivAction[]
  children: Div[]
}): Div {
  return {
    id: props.id,
    type: 'form_modal',
    custom_props: {
      visible: true,
      title: props.titleExpr,
      body: props.children,
      primary_label: 'Save',
      primary: { actions: props.onSave },
      secondary_label: 'Cancel',
      secondary: { actions: props.onCancel },
      dirty_var: props.dirtyVar,
      valid_var: props.validVar,
    },
  }
}
export function textField(props: {
  id: string
  label: string
  kind: 'text' | 'numeric' | 'multiline' | 'code'
  valueVar: string
  hint: string
  helper?: string
  prefix?: string
  maxLength?: number
  scannedChipVar?: string
  errorExpr?: string
  onBlurOp?: DivAction[]
  onScanTap?: DivAction[]
  trailingAction?: { label: string; onTap: DivAction[] }
}): Div {
  const input = new DivInput({
    id: props.id,
    text_variable: props.valueVar,
    hint_text: props.hint,
    hint_color: TOKENS.color.muted,
    text_color: TOKENS.color.ink,
    font_size: 16,
    width: weighted(1),
    keyboard_type:
      props.kind === 'numeric'
        ? 'number'
        : props.kind === 'multiline'
          ? 'multi_line_text'
          : 'single_line_text',
    max_visible_lines: props.kind === 'multiline' ? 4 : 1,
    ...(props.onBlurOp ? { focus: { on_blur: props.onBlurOp } } : {}),
  })

  return new DivContainer({
    orientation: 'vertical',
    width: matchParent(),
    margins: { bottom: TOKENS.space.group },
    items: [
      ...(props.label ? [labelText(props.label, TOKENS.color.ink)] : []),
      new DivContainer({
        orientation: 'horizontal',
        width: matchParent(),
        height: fixed(52),
        margins: { top: 6 },
        paddings: { left: 14, right: props.kind === 'code' ? 4 : 14, top: 8, bottom: 8 },
        background: [new DivSolidBackground({ color: TOKENS.color.surface })],
        border: {
          corner_radius: TOKENS.radius.button,
          stroke: {
            color: props.errorExpr
              ? expression(
                  `@{${props.errorExpr} != '' ? '${TOKENS.color.danger}' : '${TOKENS.color.line}'}`,
                )
              : TOKENS.color.line,
            width: 1,
          },
        },
        alignment_vertical: 'center',
        items: [
          ...(props.prefix
            ? [
                new DivText({
                  text: props.prefix,
                  font_size: 16,
                  text_color: TOKENS.color.muted,
                  margins: { right: 6 },
                }),
              ]
            : []),
          input,
          ...(props.scannedChipVar
            ? [
                new DivContainer({
                  visibility: expression(`@{${props.scannedChipVar} ? 'visible' : 'gone'}`),
                  background: [new DivSolidBackground({ color: TOKENS.color.successTint })],
                  border: { corner_radius: TOKENS.radius.pill },
                  paddings: { left: 10, right: 10, top: 4, bottom: 4 },
                  margins: { right: 8 },
                  items: [
                    new DivText({
                      text: '✓ scanned',
                      font_size: 13,
                      font_weight: 'medium',
                      text_color: TOKENS.color.success,
                    }),
                  ],
                }),
              ]
            : []),
          ...(props.kind === 'code' && props.onScanTap
            ? [
                new DivContainer({
                  width: fixed(52),
                  height: fixed(48),
                  border: { corner_radius: 10 },
                  background: [new DivSolidBackground({ color: TOKENS.color.accentTint })],
                  content_alignment_horizontal: 'center',
                  content_alignment_vertical: 'center',
                  actions: props.onScanTap,
                  items: [
                    new DivText({
                      text: 'Scan',
                      font_size: 13,
                      font_weight: 'bold',
                      text_color: TOKENS.color.accent,
                      text_alignment_horizontal: 'center',
                    }),
                  ],
                }),
              ]
            : []),
          ...(props.trailingAction
            ? [
                new DivContainer({
                  height: fixed(48),
                  paddings: { left: 10, right: 10 },
                  content_alignment_horizontal: 'center',
                  content_alignment_vertical: 'center',
                  actions: props.trailingAction.onTap,
                  items: [
                    new DivText({
                      text: props.trailingAction.label,
                      font_size: 14,
                      font_weight: 'medium',
                      text_color: TOKENS.color.accent,
                    }),
                  ],
                }),
              ]
            : []),
        ],
      }),
      ...(props.helper ? [labelText(props.helper, TOKENS.color.muted)] : []),
      ...(props.errorExpr
        ? [
            new DivText({
              visibility: expression(`@{${props.errorExpr} != '' ? 'visible' : 'gone'}`),
              text: expression(`@{${props.errorExpr}}`),
              font_size: 13,
              text_color: TOKENS.color.danger,
              margins: { top: 4 },
            }),
          ]
        : []),
    ],
  })
}

export function photoPicker(photoUrlVar: string): Div {
  return new DivContainer({
    orientation: 'vertical',
    width: fixed(96),
    height: fixed(96),
    border: { corner_radius: TOKENS.radius.photo },
    background: [new DivSolidBackground({ color: TOKENS.color.line })],
    content_alignment_horizontal: 'center',
    content_alignment_vertical: 'center',
    items: [
      new DivImage({
        image_url: expression(photoUrlVar),
        width: fixed(96),
        height: fixed(72),
        border: { corner_radius: TOKENS.radius.photo },
        placeholder_color: TOKENS.color.line,
      }),
      new DivText({ text: 'Add photo', font_size: 11, text_color: TOKENS.color.muted }),
    ],
  })
}
export function inlineBanner(props: {
  id: string
  visibleIf: string
  severity: 'warning' | 'danger'
  messageExpr: string
  primaryAction?: { label: string; onTap: DivAction[] }
  secondaryAction?: { label: string; onTap: DivAction[] }
}): Div {
  const isDanger = props.severity === 'danger'
  const bg = isDanger ? TOKENS.color.dangerTint : TOKENS.color.warningTint
  const fg = isDanger ? TOKENS.color.danger : TOKENS.color.warning

  return new DivContainer({
    id: props.id,
    visibility: expression(`@{${props.visibleIf} ? 'visible' : 'gone'}`),
    width: matchParent(),
    border: { corner_radius: TOKENS.radius.button },
    background: [new DivSolidBackground({ color: bg })],
    paddings: { left: 14, right: 14, top: 12, bottom: 12 },
    orientation: 'vertical',
    items: [
      new DivText({
        text: expression(props.messageExpr),
        font_size: 13,
        line_height: 18,
        text_color: TOKENS.color.ink,
      }),
      ...(props.primaryAction || props.secondaryAction
        ? [
            new DivContainer({
              orientation: 'horizontal',
              margins: { top: 8 },
              items: [
                ...(props.primaryAction
                  ? [
                      new DivContainer({
                        height: fixed(48),
                        paddings: { right: 16 },
                        content_alignment_vertical: 'center',
                        actions: props.primaryAction.onTap,
                        items: [
                          new DivText({
                            text: props.primaryAction.label,
                            font_size: 13,
                            font_weight: 'bold',
                            text_color: fg,
                          }),
                        ],
                      }),
                    ]
                  : []),
                ...(props.secondaryAction
                  ? [
                      new DivContainer({
                        height: fixed(48),
                        content_alignment_vertical: 'center',
                        actions: props.secondaryAction.onTap,
                        items: [
                          new DivText({
                            text: props.secondaryAction.label,
                            font_size: 13,
                            font_weight: 'medium',
                            text_color: TOKENS.color.muted,
                          }),
                        ],
                      }),
                    ]
                  : []),
              ],
            }),
          ]
        : []),
    ],
  })
}

export function toastBlock(props: {
  visibleVar: string
  messageVar: string
  severityVar: string
  actionLabelVar: string
  onAction: DivAction[]
  dwellMs?: number
}): Div {
  return {
    id: 'global_toast',
    type: 'toast',
    visibility: expression(`${props.visibleVar} ? 'visible' : 'gone'`),
    custom_props: {
      text: expression(props.messageVar),
      severity_var: props.severityVar,
      action_label_var: props.actionLabelVar,
      dwell_ms: props.dwellMs ?? 4000,
      actions: props.onAction,
    },
  }
}
export const jsonList = (
  itemsVar: string,
  itemTemplate: Div,
  gap = 12,
  emptyText = 'No records',
): Div => ({
  type: 'json_list',
  data_var: itemsVar,
  empty_text: emptyText,
  item_spacing: gap,
  row_card: rewriteItem(itemTemplate),
})
export const disclosure = (title: string, children: Div[]): Div =>
  new DivContainer({
    orientation: 'vertical',
    width: matchParent(),
    items: [
      new DivText({ text: title, font_size: 16, font_weight: 'bold', text_color: TOKENS.color.ink }),
      ...children,
    ],
  })
export const progressBar = (currentVar: string, totalVar: string): Div => ({
  type: 'progress_ring',
  custom_props: {
    value: expression(`${currentVar} / (${totalVar} || 1)`),
    label: expression(currentVar),
    caption: expression(totalVar),
  },
})
export const divider = () =>
  new DivSeparator({
    width: matchParent(),
    delimiter_style: { color: TOKENS.color.line, orientation: 'horizontal' },
    margins: { top: 16, bottom: 16 },
  })


function rewriteItem(value: unknown): unknown {
  if (typeof value === 'string') {
    if (value.startsWith('@{')) return value.replace(/\$item\./g, 'item.')
    return value.replace(/\$item\.([a-zA-Z0-9_]+)/g, '@{item.$1}')
  }
  if (Array.isArray(value)) return value.map(rewriteItem)
  if (value && typeof value === 'object') {
    // Builder objects keep their payload behind getters, so a JSON round trip is what exposes it.
    const plain = JSON.parse(JSON.stringify(value)) as Record<string, unknown>
    return Object.fromEntries(Object.entries(plain).map(([k, v]) => [k, rewriteItem(v)]))
  }
  return value
}

export function chip(label: string, actions: DivAction[]): Div {
  return new DivContainer({
    height: fixed(48),
    paddings: { left: 12, right: 12 },
    border: { corner_radius: TOKENS.radius.pill },
    background: [new DivSolidBackground({ color: TOKENS.color.accentTint })],
    content_alignment_horizontal: 'center',
    content_alignment_vertical: 'center',
    actions,
    items: [new DivText({ text: label, font_size: 14, font_weight: 'medium', text_color: TOKENS.color.accent })],
  })
}

export function statusChip(label: string, severity: 'warning' | 'danger' | string): Div {
  const color = severity === 'danger' ? TOKENS.color.danger : TOKENS.color.warning
  const tint = severity === 'danger' ? TOKENS.color.dangerTint : TOKENS.color.warningTint
  return new DivContainer({
    paddings: { left: 8, right: 8, top: 2, bottom: 2 },
    border: { corner_radius: TOKENS.radius.pill },
    background: [new DivSolidBackground({ color: tint })],
    items: [new DivText({ text: label, font_size: 11, font_weight: 'bold', text_color: color })],
  })
}

export function stepper(valueExpr: string, lineIdExpr: string): Div {
  return new DivContainer({
    orientation: 'horizontal',
    width: wrapContent(),
    height: fixed(48),
    alignment_vertical: 'center',
    items: [
      new DivContainer({
        width: fixed(40),
        height: fixed(40),
        content_alignment_horizontal: 'center',
        content_alignment_vertical: 'center',
        actions: [opAction('qty_minus', 'pos.set_qty', { line_id: expression(lineIdExpr), qty: expression(`${valueExpr} - 1`) })],
        items: [new DivText({ text: '−', font_size: 20, text_color: TOKENS.color.ink })],
      }),
      new DivText({ text: expression(valueExpr), font_size: 16, font_weight: 'bold', text_color: TOKENS.color.ink }),
      new DivContainer({
        width: fixed(40),
        height: fixed(40),
        content_alignment_horizontal: 'center',
        content_alignment_vertical: 'center',
        actions: [opAction('qty_plus', 'pos.set_qty', { line_id: expression(lineIdExpr), qty: expression(`${valueExpr} + 1`) })],
        items: [new DivText({ text: '+', font_size: 20, text_color: TOKENS.color.ink })],
      }),
    ],
  })
}
