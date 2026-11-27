// ---------------------------------------------------------------------------
// Boda Boda Driver App — DivKit card (server-driven UI)
// Generated with @divkitframework/jsonbuilder
//
// A rider-side card for boda boda (motorcycle taxi) drivers in East Africa:
//   * Home     — today's earnings hero, daily-target progress, cash / M-Pesa
//                split, live "this week" view and quick actions
//   * Trip     — record a trip: route chip, fare quick-picks + ±10 stepper,
//                Cash / M-Pesa payment chips, then "Record trip"
//   * Saved    — confirmation state with the trip just logged
//   * Earnings — today's totals, cash vs M-Pesa, full trip log, end-of-day
//                reset
//   * Expenses — fuel / oil / repair / other quick-add chips + net profit
//
// Patterns carried over from the waiter terminal generator (all previously
// debugged the hard way):
//   * every dynamic value (colors, visibility, urls, text) goes through
//     expression() — plain strings containing @{...} get escaped by the
//     builder and DivKit can no longer evaluate them
//   * actions are raw objects so `enabled` guards survive serialization
//   * money is whole KES, so integer-exact by construction (no cents math)
//   * a day holds at most 6 trips: fixed variable slots t1..t6 written with
//     guarded set_variable actions emulate a growing list
// ---------------------------------------------------------------------------
import {
  expression,
  divCard,
  DivContainer,
  DivText,
  DivSolidBackground,
  NumberVariable,
  StringVariable,
  DivState,
  fixed,
  matchParent,
  weighted,
  wrapContent,
} from '@divkitframework/jsonbuilder';
import type { WorkspaceDefinition } from '../../core/types.ts'

// ---------------------------------------------------------------------------
// 0. Design tokens & helpers
// ---------------------------------------------------------------------------
const C = {
  page: '#F4F6F5',
  surface: '#FFFFFF',
  line: '#E4E9E7',
  ink: '#0F172A',
  body: '#334155',
  muted: '#64748B',
  faint: '#94A3B8',
  primary: '#059669',
  primaryDeep: '#064E3B',
  primaryVivid: '#10B981',
  tint: '#ECFDF5',
  onTint: '#047857',
  amber: '#F59E0B',
  amberSoft: '#FFFBEB',
  amberLine: '#FDE68A',
  amberInk: '#B45309',
  danger: '#EF4444',
  dangerTint: '#FEE2E2',
  dangerInk: '#B91C1C',
  disabled: '#CBD5E1',
  track: '#E2E8F0',
  chipBg: '#F1F5F4',
  headerText: '#A7F3D0',
  headerTextDim: '#6EE7B7',
};

const solid = (color) => new DivSolidBackground({ color });
const box = (props) => new DivContainer(props);
const txt = (props) => new DivText(props);

// visibility helpers (expressions are legal for the visibility property)
const vis = (cond) => expression(`@{${cond} ? 'visible' : 'gone'}`);
const visNot = (cond) => expression(`@{${cond} ? 'gone' : 'visible'}`);

// Whole-KES formatting: fares are integers, so 'KES ' + toString() is exact.
const kes = (expr) => `@{'KES ' + toString(toInteger(${expr}))}`;

// ---------------------------------------------------------------------------
// 1. Domain data & aggregate expressions
// ---------------------------------------------------------------------------
const TARGET = 1500; // daily earnings target in KES
const SLOTS = 6; // trips per day

const ROUTES = [
  { id: 1, icon: '🛵', label: 'Stage → Market' },
  { id: 2, icon: '🏘️', label: 'Town → Estate' },
  { id: 3, icon: '🛫', label: 'Town → Airport' },
  { id: 4, icon: '🎒', label: 'School Run' },
  { id: 5, icon: '📦', label: 'Parcel Delivery' },
];

const FARES = [50, 100, 150, 200, 300];

const WEEK = [
  ['Mon', '1,240'],
  ['Tue', '980'],
  ['Wed', '1,610'],
  ['Thu', '1,050'],
  ['Fri', '1,870'],
  ['Sat', '2,240'],
];

const EXPENSES = [
  { key: 'fuel', icon: '⛽', name: 'Fuel', adds: [100, 200, 500] },
  { key: 'oil', icon: '🛢', name: 'Oil change', adds: [50, 100, 200] },
  { key: 'repair', icon: '🔧', name: 'Repair', adds: [100, 200, 500] },
  { key: 'other', icon: '🧾', name: 'Other', adds: [50, 100, 200] },
];

// route ids are stored as numbers; map them back to labels inside expressions
const routeNameRaw = (v) =>
  `${v} == 1 ? 'Stage → Market' : (${v} == 2 ? 'Town → Estate' : (${v} == 3 ? 'Town → Airport' : (${v} == 4 ? 'School Run' : (${v} == 5 ? 'Parcel Delivery' : 'Unknown route'))))`;
const routeNameExpr = (v) => `@{${routeNameRaw(v)}}`;

const earnToday = Array.from({ length: SLOTS }, (_, k) => `user.t${k + 1}_fare`).join(' + ');
const mpesaToday = Array.from({ length: SLOTS }, (_, k) => `(user.t${k + 1}_method == 'mpesa' ? user.t${k + 1}_fare : 0)`).join(' + ');
const cashToday = `(${earnToday}) - (${mpesaToday})`;
const expTotal = 'user.exp_fuel + user.exp_oil + user.exp_repair + user.exp_other';
const netToday = `(${earnToday}) - (${expTotal})`;
const targetHit = `(${earnToday}) >= ${TARGET}`;
const pctRaw = `${targetHit} ? 100 : toInteger((${earnToday}) * 100 / ${TARGET})`;

// ---------------------------------------------------------------------------
// 2. Global state
// ---------------------------------------------------------------------------
const variables = [
  new StringVariable({ name: 'user.active_tab', value: 'home' }),
  new NumberVariable({ name: 'user.trip_count', value: 0 }),
  new NumberVariable({ name: 'user.trip_route', value: 1 }),
  new NumberVariable({ name: 'user.trip_fare', value: 0 }),
  new StringVariable({ name: 'user.trip_method', value: 'cash' }),
  new NumberVariable({ name: 'user.last_fare', value: 0 }),
  new NumberVariable({ name: 'user.last_route', value: 0 }),
  new StringVariable({ name: 'user.last_method', value: 'cash' }),
  new NumberVariable({ name: 'user.exp_fuel', value: 0 }),
  new NumberVariable({ name: 'user.exp_oil', value: 0 }),
  new NumberVariable({ name: 'user.exp_repair', value: 0 }),
  new NumberVariable({ name: 'user.exp_other', value: 0 }),
];
for (let i = 1; i <= SLOTS; i++) {
  variables.push(
    new NumberVariable({ name: `user.t${i}_route`, value: 0 }),
    new NumberVariable({ name: `user.t${i}_fare`, value: 0 }),
    new StringVariable({ name: `user.t${i}_method`, value: 'cash' }),
  );
}

// ---------------------------------------------------------------------------
// 3. Small shared components
// ---------------------------------------------------------------------------
const sectionLabel = (text) =>
  txt({ text, font_size: 10, font_weight: 'bold', letter_spacing: 2, text_color: C.faint });

const card = (opts: any) => {
  const border: any = { corner_radius: opts.radius ?? 20 };
  if (!opts.noStroke) border.stroke = { color: opts.stroke ?? C.line, width: 1 };
  return box({
    orientation: 'vertical',
    width: matchParent(),
    background: [solid(opts.bg ?? C.surface)],
    border,
    paddings: opts.paddings ?? { left: 16, right: 16, top: 16, bottom: 16 },
    margins: opts.margins,
    items: opts.items,
  });
};

const divider = (margins) =>
  box({ height: fixed(1), width: matchParent(), background: [solid('#EDF2F0')], margins });

const dot = (color) => box({ width: fixed(8), height: fixed(8), background: [solid(color)], border: { corner_radius: 4 } });

const pill = (text: any, opts: any = {}) =>
  box({
    width: wrapContent(),
    paddings: { left: 10, right: 10, top: 5, bottom: 5 },
    background: [solid(opts.bg ?? C.tint)],
    border: { corner_radius: 12 },
    margins: opts.margins,
    items: [
      txt({ width: wrapContent(), text, font_size: opts.fontSize ?? 12, font_weight: 'bold', text_color: opts.color ?? C.onTint }),
    ],
  });

// chip whose selected/unselected look is driven by an expression condition
const exprChip = (opts) =>
  box({
    width: opts.width ?? wrapContent(),
    height: opts.height,
    background: [solid(expression(`@{${opts.cond} ? '${opts.selectedBg ?? C.tint}' : '${opts.bg ?? C.surface}'}`))],
    border: {
      corner_radius: opts.radius ?? 12,
      stroke: { color: expression(`@{${opts.cond} ? '${opts.selectedStroke ?? C.primary}' : '${opts.stroke ?? C.line}'}`), width: 1.5 },
    },
    paddings: opts.paddings ?? { left: 14, right: 14, top: 8, bottom: 8 },
    margins: opts.margins,
    content_alignment_horizontal: 'center',
    content_alignment_vertical: 'center',
    actions: [{ log_id: opts.logId, url: opts.url }],
    items: [
      txt({
        width: wrapContent(),
        text: opts.label,
        font_size: opts.fontSize ?? 13,
        font_weight: 'bold',
        text_alignment_horizontal: 'center',
        text_color: expression(`@{${opts.cond} ? '${opts.selectedColor ?? C.onTint}' : '${opts.color ?? C.body}'}`),
      }),
    ],
  });

const statTile = (opts) =>
  box({
    orientation: 'vertical',
    width: weighted(1),
    background: [solid(opts.bg ?? C.surface)],
    border: { corner_radius: 18, stroke: { color: opts.stroke ?? C.line, width: 1 } },
    paddings: { left: 14, right: 14, top: 12, bottom: 12 },
    margins: opts.margins,
    items: [
      box({
        orientation: 'horizontal',
        width: matchParent(),
        content_alignment_vertical: 'center',
        items: [
          txt({ width: wrapContent(), text: opts.icon, font_size: 14 }),
          txt({ width: wrapContent(), text: opts.label, font_size: 11, text_color: opts.labelColor ?? C.muted, margins: { left: 6 } }),
        ],
      }),
      opts.value,
    ],
  });

const backBtn = (target) =>
  box({
    width: fixed(40),
    height: fixed(40),
    background: [solid(C.surface)],
    border: { corner_radius: 12, stroke: { color: C.line, width: 1 } },
    content_alignment_horizontal: 'center',
    content_alignment_vertical: 'center',
    actions: [{ log_id: `back_${target}`, url: `div-action://set_variable?name=user.active_tab&value=${target}` }],
    items: [txt({ text: '←', font_size: 18, font_weight: 'bold', text_color: C.ink })],
  });

const topBar = (title, target, rightPill) =>
  box({
    orientation: 'horizontal',
    width: matchParent(),
    content_alignment_vertical: 'center',
    margins: { bottom: 12 },
    items: [
      backBtn(target),
      box({ width: weighted(1), items: [txt({ text: title, font_size: 20, font_weight: 'bold', text_color: C.ink, margins: { left: 12 } })] }),
      rightPill,
    ],
  });

// Scrollable body area. The open-source DivKit web renderer does not
// implement the `scroll` container type (it throws "Unknown component"), so
// we use a vertical `gallery` — the scrollable primitive DivKit web does
// ship. Native mobile SDKs accept both; gallery keeps the web preview honest.
const scrollArea = (items) => ({
  type: 'gallery',
  orientation: 'vertical',
  width: matchParent(),
  height: matchParent(),
  item_spacing: 0,
  items,
});

const bottomSpacer = box({ height: fixed(16), width: matchParent() });

// ---------------------------------------------------------------------------
// 4. HOME — gradient header + earnings hero + stats + week + quick actions
// ---------------------------------------------------------------------------
const homeHeader = box({
  orientation: 'vertical',
  width: matchParent(),
  background: [{ type: 'gradient', colors: [C.primaryDeep, C.primary, C.primaryVivid], angle: 135 }],
  paddings: { left: 20, right: 20, top: 24, bottom: 20 },
  items: [
    box({
      width: wrapContent(),
      paddings: { left: 10, right: 10, top: 4, bottom: 4 },
      background: [solid('#26FFFFFF')],
      border: { corner_radius: 20 },
      items: [
        txt({ width: wrapContent(), text: '🛵 BODA BODA • DRIVER APP', font_size: 10, font_weight: 'bold', letter_spacing: 2, text_color: C.headerTextDim }),
      ],
    }),
    txt({ width: wrapContent(), text: 'Habari, Rider 👋', font_size: 26, font_weight: 'bold', text_color: '#FFFFFF', margins: { top: 12 } }),
    txt({ width: wrapContent(), text: 'Every trip, fare and fuel cost — in one place.', font_size: 13, text_color: C.headerText, margins: { top: 6 } }),
    card({
      radius: 24,
      noStroke: true,
      margins: { top: 16 },
      items: [
        txt({ text: "TODAY'S EARNINGS", font_size: 10, font_weight: 'bold', letter_spacing: 2, text_color: C.faint }),
        txt({ text: expression(kes(earnToday)), font_size: 32, font_weight: 'bold', text_color: C.ink, margins: { top: 6 } }),
        box({
          orientation: 'horizontal',
          width: matchParent(),
          margins: { top: 14 },
          items: Array.from({ length: 10 }, (_, k) =>
            box({
              width: weighted(1),
              height: fixed(6),
              background: [solid(expression(`@{(${earnToday}) >= ${(TARGET / 10) * (k + 1)} ? '${C.primaryVivid}' : '${C.track}'}`))],
              border: { corner_radius: 3 },
              margins: { right: k === 9 ? 0 : 3 },
            })),
        }),
        box({
          orientation: 'horizontal',
          width: matchParent(),
          content_alignment_vertical: 'center',
          margins: { top: 8 },
          items: [
            txt({ width: weighted(1), text: 'Daily target • KES 1,500', font_size: 11, text_color: C.faint }),
            txt({
              width: wrapContent(),
              text: expression(`@{${targetHit} ? '🎉 Target reached!' : toString((${pctRaw})) + '% there'}`),
              font_size: 11,
              font_weight: 'bold',
              text_color: expression(`@{${targetHit} ? '${C.onTint}' : '${C.muted}'}`),
            }),
          ],
        }),
      ],
    }),
  ],
});

const statsRow1 = box({
  orientation: 'horizontal',
  width: matchParent(),
  items: [
    statTile({
      icon: '🛵',
      label: 'Trips today',
      margins: { right: 8 },
      value: txt({ text: expression('@{toString(toInteger(user.trip_count))}'), font_size: 18, font_weight: 'bold', text_color: C.ink, margins: { top: 8 } }),
    }),
    statTile({
      icon: '📈',
      label: 'Net profit',
      value: txt({
        text: expression(kes(netToday)),
        font_size: 18,
        font_weight: 'bold',
        text_color: expression(`@{${netToday} >= 0 ? '${C.primary}' : '${C.danger}'}`),
        margins: { top: 8 },
      }),
    }),
  ],
});

const statsRow2 = box({
  orientation: 'horizontal',
  width: matchParent(),
  margins: { top: 8 },
  items: [
    statTile({
      icon: '💵',
      label: 'Cash',
      bg: C.amberSoft,
      stroke: C.amberLine,
      margins: { right: 8 },
      value: txt({ text: expression(kes(cashToday)), font_size: 16, font_weight: 'bold', text_color: C.amberInk, margins: { top: 8 } }),
    }),
    statTile({
      icon: '📱',
      label: 'M-Pesa',
      bg: C.tint,
      stroke: '#A7F3D0',
      value: txt({ text: expression(kes(mpesaToday)), font_size: 16, font_weight: 'bold', text_color: C.onTint, margins: { top: 8 } }),
    }),
  ],
});

const weekRow = (day, amount) =>
  box({
    orientation: 'horizontal',
    width: matchParent(),
    content_alignment_vertical: 'center',
    items: [
      txt({ width: weighted(1), text: day, font_size: 13, text_color: C.body }),
      txt({ width: wrapContent(), text: `KES ${amount}`, font_size: 13, font_weight: 'bold', text_color: C.muted }),
    ],
  });

const weekCard = card({
  margins: { top: 12 },
  items: [
    box({
      orientation: 'horizontal',
      width: matchParent(),
      content_alignment_vertical: 'center',
      items: [
        txt({ width: weighted(1), text: 'This week', font_size: 16, font_weight: 'bold', text_color: C.ink }),
        pill(expression(`@{'Today • KES ' + toString(toInteger(${earnToday}))}`), { bg: C.tint, color: C.onTint }),
      ],
    }),
    box({
      orientation: 'vertical',
      width: matchParent(),
      margins: { top: 10 },
      items: WEEK.map(([d, a]) => weekRow(d, a)),
    }),
    box({
      orientation: 'horizontal',
      width: matchParent(),
      content_alignment_vertical: 'center',
      background: [solid(C.tint)],
      border: { corner_radius: 12 },
      paddings: { left: 12, right: 12, top: 10, bottom: 10 },
      margins: { top: 10 },
      items: [
        txt({ width: weighted(1), text: 'Today (live)', font_size: 13, font_weight: 'bold', text_color: C.onTint }),
        txt({ width: wrapContent(), text: expression(kes(earnToday)), font_size: 13, font_weight: 'bold', text_color: C.onTint }),
      ],
    }),
  ],
});

const qaBtn = (opts: any) => {
  const border: any = { corner_radius: 14 };
  if (opts.stroke) border.stroke = { color: opts.stroke, width: 1 };
  return box({
    orientation: 'horizontal',
    width: weighted(1),
    height: fixed(50),
    background: [solid(opts.bg)],
    border,
    content_alignment_horizontal: 'center',
    content_alignment_vertical: 'center',
    margins: opts.margins,
    actions: [{ log_id: opts.logId, url: opts.url }],
    items: [txt({ width: wrapContent(), text: opts.label, font_size: 14, font_weight: 'bold', text_color: opts.color })],
  });
};

const quickActions = box({
  orientation: 'horizontal',
  width: matchParent(),
  margins: { top: 12 },
  items: [
    qaBtn({
      label: '🛵 Record trip',
      bg: C.primary,
      color: '#FFFFFF',
      logId: 'qa_trip',
      url: 'div-action://set_variable?name=user.active_tab&value=trip',
      margins: { right: 8 },
    }),
    qaBtn({
      label: '⛽ Log expense',
      bg: C.amberSoft,
      stroke: C.amberLine,
      color: C.amberInk,
      logId: 'qa_expenses',
      url: 'div-action://set_variable?name=user.active_tab&value=expenses',
    }),
  ],
});

const demoNote = card({
  radius: 16,
  margins: { top: 12 },
  items: [
    txt({
      text: 'Demo mode — trips and expenses live in card variables. Reload the preview to reset the day.',
      font_size: 11,
      text_color: C.faint,
      max_lines: 3,
    }),
  ],
});

const homeView = box({
  orientation: 'vertical',
  width: matchParent(),
  height: matchParent(),
  items: [
    scrollArea([
      homeHeader,
      box({
        orientation: 'vertical',
        width: matchParent(),
        paddings: { left: 16, right: 16, top: 14 },
        items: [statsRow1, statsRow2, weekCard, quickActions, demoNote],
      }),
    ]),
  ],
});

// ---------------------------------------------------------------------------
// 5. TRIP — route / fare / method chips + summary + record button
// ---------------------------------------------------------------------------
const routeRows = [
  [ROUTES[0], ROUTES[1]],
  [ROUTES[2], ROUTES[3]],
  [ROUTES[4]],
].map((row, idx) =>
  box({
    orientation: 'horizontal',
    width: matchParent(),
    margins: { top: idx === 0 ? 12 : 8 },
    items: row.map((r, k) =>
      exprChip({
        cond: `user.trip_route == ${r.id}`,
        label: `${r.icon} ${r.label}`,
        logId: `route_${r.id}`,
        url: `div-action://set_variable?name=user.trip_route&value=${r.id}`,
        height: fixed(52),
        width: weighted(1),
        margins: k === 0 && row.length > 1 ? { right: 8 } : undefined,
      })),
  }));

const fareChips = box({
  orientation: 'horizontal',
  width: matchParent(),
  margins: { top: 12 },
  items: FARES.map((f, k) =>
    exprChip({
      cond: `user.trip_fare == ${f}`,
      label: `${f}`,
      logId: `fare_${f}`,
      url: `div-action://set_variable?name=user.trip_fare&value=${f}`,
      height: fixed(40),
      width: weighted(1),
      margins: k === FARES.length - 1 ? undefined : { right: 6 },
    })),
});

const stepBtn = (label, bg, fg, url, logId) =>
  box({
    width: fixed(44),
    height: fixed(44),
    background: [solid(bg)],
    border: { corner_radius: 12 },
    content_alignment_horizontal: 'center',
    content_alignment_vertical: 'center',
    actions: [{ log_id: logId, url: expression(url) }],
    items: [txt({ text: label, font_size: 20, font_weight: 'bold', text_color: fg })],
  });

const stepperRow = box({
  orientation: 'horizontal',
  width: matchParent(),
  content_alignment_vertical: 'center',
  margins: { top: 12 },
  items: [
    stepBtn('−', C.dangerTint, C.danger, 'div-action://set_variable?name=user.trip_fare&value=@{user.trip_fare >= 10 ? user.trip_fare - 10 : 0}', 'fare_minus'),
    box({
      width: weighted(1),
      content_alignment_horizontal: 'center',
      items: [txt({ width: wrapContent(), text: expression("@{'KES ' + toString(toInteger(user.trip_fare))}"), font_size: 22, font_weight: 'bold', text_color: C.ink })],
    }),
    stepBtn('+', C.primary, '#FFFFFF', 'div-action://set_variable?name=user.trip_fare&value=@{user.trip_fare + 10}', 'fare_plus'),
  ],
});

const methodRow = box({
  orientation: 'horizontal',
  width: matchParent(),
  margins: { top: 12 },
  items: [
    exprChip({
      cond: `user.trip_method == 'cash'`,
      label: '💵 Cash',
      logId: 'method_cash',
      url: 'div-action://set_variable?name=user.trip_method&value=cash',
      height: fixed(52),
      width: weighted(1),
      fontSize: 14,
      margins: { right: 8 },
      selectedBg: C.amberSoft,
      selectedStroke: C.amber,
      selectedColor: C.amberInk,
    }),
    exprChip({
      cond: `user.trip_method == 'mpesa'`,
      label: '📱 M-Pesa',
      logId: 'method_mpesa',
      url: 'div-action://set_variable?name=user.trip_method&value=mpesa',
      height: fixed(52),
      width: weighted(1),
      fontSize: 14,
    }),
  ],
});

const tripSummary = box({
  width: matchParent(),
  background: [solid(C.surface)],
  border: { corner_radius: 14, stroke: { color: C.line, width: 1 } },
  paddings: { left: 14, right: 14, top: 12, bottom: 12 },
  margins: { top: 12 },
  items: [
    txt({
      width: matchParent(),
      text: expression(`@{'🧾 ' + (${routeNameRaw('user.trip_route')}) + ' • KES ' + toString(toInteger(user.trip_fare)) + ' • ' + (user.trip_method == 'mpesa' ? 'M-Pesa' : 'Cash')}`),
      font_size: 13,
      text_color: C.body,
      max_lines: 2,
    }),
  ],
});

// The whole record flow only proceeds with a fare and an open slot. The web
// renderer does not honor per-action `enabled`, so every guarded action uses
// the conditional-value pattern instead: when the guard fails it writes the
// variable's own current value back (a no-op), otherwise the new value. This
// is pure DivKit expression semantics, so it also behaves identically on the
// Android / iOS clients.
const recordEnabled = `user.trip_fare > 0 && user.trip_count <= 5`;
const doRecord = `user.trip_fare > 0 && user.trip_count <= 5`;

const slotActions: any[] = [];
for (let i = 1; i <= SLOTS; i++) {
  // only the slot matching user.trip_count == i-1 accepts the pending trip;
  // every other slot self-writes and stays untouched
  slotActions.push(
    { log_id: `slot_${i}_route`, url: expression(`div-action://set_variable?name=user.t${i}_route&value=@{user.trip_count == ${i - 1} ? user.trip_route : user.t${i}_route}`) },
    { log_id: `slot_${i}_fare`, url: expression(`div-action://set_variable?name=user.t${i}_fare&value=@{user.trip_count == ${i - 1} ? user.trip_fare : user.t${i}_fare}`) },
    { log_id: `slot_${i}_method`, url: expression(`div-action://set_variable?name=user.t${i}_method&value=@{user.trip_count == ${i - 1} ? user.trip_method : user.t${i}_method}`) },
  );
}

const recordBtn = box({
  orientation: 'horizontal',
  width: matchParent(),
  height: fixed(54),
  background: [solid(expression(`@{${recordEnabled} ? '${C.primary}' : '${C.disabled}'}`))],
  border: { corner_radius: 16 },
  margins: { top: 4 },
  content_alignment_horizontal: 'center',
  content_alignment_vertical: 'center',
  actions: [
    ...slotActions,
    { log_id: 'inc_trip_count', url: expression(`div-action://set_variable?name=user.trip_count&value=@{${doRecord} ? user.trip_count + 1 : user.trip_count}`) },
    { log_id: 'save_last_fare', url: expression(`div-action://set_variable?name=user.last_fare&value=@{${doRecord} ? user.trip_fare : user.last_fare}`) },
    { log_id: 'save_last_route', url: expression(`div-action://set_variable?name=user.last_route&value=@{${doRecord} ? user.trip_route : user.last_route}`) },
    { log_id: 'save_last_method', url: expression(`div-action://set_variable?name=user.last_method&value=@{${doRecord} ? user.trip_method : user.last_method}`) },
    // navigation is guarded by user.trip_fare too, so it must run BEFORE the
    // reset below (the renderer executes actions strictly in order)
    { log_id: 'goto_saved', url: expression(`div-action://set_variable?name=user.active_tab&value=@{${doRecord} ? 'saved' : user.active_tab}`) },
    { log_id: 'reset_trip_fare', url: expression(`div-action://set_variable?name=user.trip_fare&value=@{${doRecord} ? 0 : user.trip_fare}`) },
  ],
  items: [
    txt({
      width: wrapContent(),
      text: expression(`@{user.trip_count >= 6 ? '✅ Day complete — 6/6 trips' : '🛵 Record trip'}`),
      font_size: 16,
      font_weight: 'bold',
      text_color: '#FFFFFF',
    }),
  ],
});

const tripView = box({
  orientation: 'vertical',
  width: matchParent(),
  height: matchParent(),
  paddings: { left: 16, right: 16, top: 12 },
  items: [
    topBar('Record trip', 'home', pill(expression("@{toString(toInteger(user.trip_count)) + '/6 trips'}"))),
    scrollArea([
      card({ items: [sectionLabel('ROUTE'), ...routeRows] }),
      card({
        margins: { top: 10 },
        items: [
          sectionLabel('FARE (KES)'),
          fareChips,
          stepperRow,
          txt({ text: 'Typical stage fares: KES 50 – 300. Use +/− to fine-tune by 10.', font_size: 11, text_color: C.faint, margins: { top: 10 } }),
        ],
      }),
      card({ margins: { top: 10 }, items: [sectionLabel('PAYMENT METHOD'), methodRow] }),
      tripSummary,
      recordBtn,
      bottomSpacer,
    ]),
  ],
});

// ---------------------------------------------------------------------------
// 6. SAVED — confirmation state after recording a trip
// ---------------------------------------------------------------------------
const savedView = box({
  orientation: 'vertical',
  width: matchParent(),
  height: matchParent(),
  paddings: { left: 24, right: 24, top: 44 },
  content_alignment_horizontal: 'center',
  items: [
    box({
      width: fixed(96),
      height: fixed(96),
      background: [solid(C.tint)],
      border: { corner_radius: 48 },
      content_alignment_horizontal: 'center',
      content_alignment_vertical: 'center',
      items: [txt({ text: '✓', font_size: 44, font_weight: 'bold', text_color: C.onTint })],
    }),
    txt({ width: wrapContent(), text: 'Trip recorded!', font_size: 24, font_weight: 'bold', text_color: C.ink, margins: { top: 20 } }),
    box({
      width: wrapContent(),
      paddings: { left: 14, right: 14, top: 8, bottom: 8 },
      background: [solid(C.tint)],
      border: { corner_radius: 14 },
      margins: { top: 12 },
      items: [
        txt({
          width: wrapContent(),
          text: expression(`@{'KES ' + toString(toInteger(user.last_fare)) + ' • ' + (${routeNameRaw('user.last_route')})}`),
          font_size: 13,
          font_weight: 'bold',
          text_color: C.onTint,
        }),
      ],
    }),
    txt({ width: wrapContent(), text: 'Saved to today’s earnings log.', font_size: 13, text_color: C.muted, margins: { top: 8 } }),
    card({
      radius: 16,
      noStroke: true,
      margins: { top: 24 },
      items: [
        box({
          orientation: 'horizontal',
          width: matchParent(),
          content_alignment_vertical: 'center',
          items: [
            box({
              orientation: 'vertical',
              width: weighted(1),
              items: [
                txt({ text: 'Earnings today', font_size: 11, text_color: C.faint }),
                txt({ text: expression(kes(earnToday)), font_size: 18, font_weight: 'bold', text_color: C.ink, margins: { top: 4 } }),
              ],
            }),
            box({ width: fixed(1), height: fixed(32), background: [solid(C.line)], margins: { left: 12, right: 12 } }),
            box({
              orientation: 'vertical',
              width: weighted(1),
              items: [
                txt({ text: 'Trips', font_size: 11, text_color: C.faint }),
                txt({ text: expression('@{toString(toInteger(user.trip_count))}'), font_size: 18, font_weight: 'bold', text_color: C.ink, margins: { top: 4 } }),
              ],
            }),
          ],
        }),
      ],
    }),
    box({
      orientation: 'horizontal',
      width: matchParent(),
      height: fixed(50),
      background: [solid(C.tint)],
      border: { corner_radius: 14 },
      margins: { top: 24 },
      content_alignment_horizontal: 'center',
      content_alignment_vertical: 'center',
      actions: [{ log_id: 'another_trip', url: 'div-action://set_variable?name=user.active_tab&value=trip' }],
      items: [txt({ width: wrapContent(), text: '＋ Record another trip', font_size: 15, font_weight: 'bold', text_color: C.onTint })],
    }),
    box({
      orientation: 'horizontal',
      width: matchParent(),
      height: fixed(50),
      background: [solid(C.surface)],
      border: { corner_radius: 14, stroke: { color: C.line, width: 1 } },
      margins: { top: 10 },
      content_alignment_horizontal: 'center',
      content_alignment_vertical: 'center',
      actions: [{ log_id: 'view_earnings', url: 'div-action://set_variable?name=user.active_tab&value=history' }],
      items: [txt({ width: wrapContent(), text: 'View earnings', font_size: 15, font_weight: 'bold', text_color: C.ink })],
    }),
  ],
});

// ---------------------------------------------------------------------------
// 7. EARNINGS — today summary, trip log (6 guarded slots), end-of-day reset
// ---------------------------------------------------------------------------
const summaryCard = card({
  items: [
    sectionLabel('TODAY'),
    txt({ text: expression(kes(earnToday)), font_size: 28, font_weight: 'bold', text_color: C.ink, margins: { top: 6 } }),
    box({
      orientation: 'horizontal',
      width: matchParent(),
      content_alignment_vertical: 'center',
      margins: { top: 14 },
      items: [
        dot(C.amber),
        txt({ width: weighted(1), text: 'Cash payments', font_size: 13, text_color: C.body, margins: { left: 8 } }),
        txt({ width: wrapContent(), text: expression(kes(cashToday)), font_size: 13, font_weight: 'bold', text_color: C.ink }),
      ],
    }),
    box({
      orientation: 'horizontal',
      width: matchParent(),
      content_alignment_vertical: 'center',
      margins: { top: 8 },
      items: [
        dot(C.primary),
        txt({ width: weighted(1), text: 'M-Pesa payments', font_size: 13, text_color: C.body, margins: { left: 8 } }),
        txt({ width: wrapContent(), text: expression(kes(mpesaToday)), font_size: 13, font_weight: 'bold', text_color: C.onTint }),
      ],
    }),
    divider({ top: 14 }),
    box({
      orientation: 'horizontal',
      width: matchParent(),
      content_alignment_vertical: 'center',
      margins: { top: 10 },
      items: [
        txt({ width: weighted(1), text: 'Net after expenses', font_size: 13, text_color: C.muted }),
        txt({
          width: wrapContent(),
          text: expression(kes(netToday)),
          font_size: 15,
          font_weight: 'bold',
          text_color: expression(`@{${netToday} >= 0 ? '${C.primary}' : '${C.danger}'}`),
        }),
      ],
    }),
  ],
});

const tripLogRow = (i) =>
  box({
    orientation: 'horizontal',
    width: matchParent(),
    content_alignment_vertical: 'center',
    margins: { top: 12 },
    visibility: vis(`user.trip_count >= ${i}`),
    items: [
      box({
        width: fixed(26),
        height: fixed(26),
        background: [solid(C.tint)],
        border: { corner_radius: 8 },
        content_alignment_horizontal: 'center',
        content_alignment_vertical: 'center',
        items: [txt({ text: `${i}`, font_size: 11, font_weight: 'bold', text_color: C.onTint })],
      }),
      box({
        orientation: 'vertical',
        width: weighted(1),
        margins: { left: 10 },
        items: [
          txt({ text: expression(routeNameExpr(`user.t${i}_route`)), font_size: 14, font_weight: 'bold', text_color: C.ink }),
          txt({
            text: expression(`@{user.t${i}_method == 'mpesa' ? '📱 M-Pesa' : '💵 Cash'}`),
            font_size: 11,
            text_color: expression(`@{user.t${i}_method == 'mpesa' ? '${C.onTint}' : '${C.amberInk}'}`),
            margins: { top: 2 },
          }),
        ],
      }),
      txt({ width: wrapContent(), text: expression(kes(`user.t${i}_fare`)), font_size: 14, font_weight: 'bold', text_color: C.ink, margins: { left: 10 } }),
    ],
  });

const tripLogCard = card({
  margins: { top: 10 },
  items: [
    box({
      orientation: 'horizontal',
      width: matchParent(),
      content_alignment_vertical: 'center',
      items: [
        txt({ width: weighted(1), text: 'Trip log', font_size: 16, font_weight: 'bold', text_color: C.ink }),
        box({
          width: wrapContent(),
          paddings: { left: 10, right: 10, top: 4, bottom: 4 },
          background: [solid(C.tint)],
          border: { corner_radius: 12 },
          visibility: vis('user.trip_count > 0'),
          items: [txt({ width: wrapContent(), text: expression("@{toString(toInteger(user.trip_count)) + ' trips'}"), font_size: 11, font_weight: 'bold', text_color: C.onTint })],
        }),
      ],
    }),
    box({
      orientation: 'vertical',
      width: matchParent(),
      margins: { top: 8 },
      visibility: visNot('user.trip_count > 0'),
      items: [
        txt({ text: 'No trips yet — record your first ride and it will show up here.', font_size: 13, text_color: C.faint, max_lines: 2 }),
        box({
          orientation: 'horizontal',
          width: matchParent(),
          height: fixed(44),
          background: [solid(C.tint)],
          border: { corner_radius: 12 },
          margins: { top: 12 },
          content_alignment_horizontal: 'center',
          content_alignment_vertical: 'center',
          actions: [{ log_id: 'first_trip', url: 'div-action://set_variable?name=user.active_tab&value=trip' }],
          items: [txt({ width: wrapContent(), text: '🛵 Record first trip', font_size: 14, font_weight: 'bold', text_color: C.onTint })],
        }),
      ],
    }),
    ...Array.from({ length: SLOTS }, (_, k) => tripLogRow(k + 1)),
  ],
});

const clearActions = [
  { log_id: 'reset_count', url: 'div-action://set_variable?name=user.trip_count&value=0' },
  { log_id: 'reset_cur_fare', url: 'div-action://set_variable?name=user.trip_fare&value=0' },
  { log_id: 'reset_last_fare', url: 'div-action://set_variable?name=user.last_fare&value=0' },
  { log_id: 'reset_last_route', url: 'div-action://set_variable?name=user.last_route&value=0' },
  { log_id: 'reset_last_method', url: 'div-action://set_variable?name=user.last_method&value=cash' },
];
for (let i = 1; i <= SLOTS; i++) {
  clearActions.push(
    { log_id: `wipe_${i}_route`, url: `div-action://set_variable?name=user.t${i}_route&value=0` },
    { log_id: `wipe_${i}_fare`, url: `div-action://set_variable?name=user.t${i}_fare&value=0` },
    { log_id: `wipe_${i}_method`, url: `div-action://set_variable?name=user.t${i}_method&value=cash` },
  );
}
clearActions.push(
  { log_id: 'wipe_exp_fuel', url: 'div-action://set_variable?name=user.exp_fuel&value=0' },
  { log_id: 'wipe_exp_oil', url: 'div-action://set_variable?name=user.exp_oil&value=0' },
  { log_id: 'wipe_exp_repair', url: 'div-action://set_variable?name=user.exp_repair&value=0' },
  { log_id: 'wipe_exp_other', url: 'div-action://set_variable?name=user.exp_other&value=0' },
);

const endOfDayCard = card({
  margins: { top: 10 },
  items: [
    sectionLabel('END OF DAY'),
    box({
      orientation: 'horizontal',
      width: matchParent(),
      height: fixed(48),
      background: [solid(C.dangerTint)],
      border: { corner_radius: 14 },
      margins: { top: 10 },
      content_alignment_horizontal: 'center',
      content_alignment_vertical: 'center',
      actions: clearActions,
      items: [txt({ width: wrapContent(), text: '🧹 Clear all data', font_size: 14, font_weight: 'bold', text_color: C.dangerInk })],
    }),
    txt({ text: 'Wipes trips, fares and expenses in this preview.', font_size: 11, text_color: C.faint, margins: { top: 8 } }),
  ],
});

const historyView = box({
  orientation: 'vertical',
  width: matchParent(),
  height: matchParent(),
  paddings: { left: 16, right: 16, top: 12 },
  items: [
    topBar('Earnings', 'home', pill(expression(kes(earnToday)))),
    scrollArea([summaryCard, tripLogCard, endOfDayCard, bottomSpacer]),
  ],
});

// ---------------------------------------------------------------------------
// 8. EXPENSES — fuel / oil / repair / other quick-adds + net summary
// ---------------------------------------------------------------------------
const expenseCard = (e) =>
  card({
    margins: { bottom: 10 },
    items: [
      box({
        orientation: 'horizontal',
        width: matchParent(),
        content_alignment_vertical: 'center',
        items: [
          txt({ width: wrapContent(), text: e.icon, font_size: 18 }),
          txt({ width: weighted(1), text: e.name, font_size: 15, font_weight: 'bold', text_color: C.ink, margins: { left: 10 } }),
          txt({ width: wrapContent(), text: expression(kes(`user.exp_${e.key}`)), font_size: 16, font_weight: 'bold', text_color: C.ink }),
        ],
      }),
      box({
        orientation: 'horizontal',
        width: matchParent(),
        margins: { top: 12 },
        items: e.adds.map((a, k) =>
          box({
            width: weighted(1),
            height: fixed(38),
            background: [solid(C.chipBg)],
            border: { corner_radius: 10 },
            content_alignment_horizontal: 'center',
            content_alignment_vertical: 'center',
            margins: k === e.adds.length - 1 ? undefined : { right: 6 },
            actions: [{ log_id: `add_${e.key}_${a}`, url: expression(`div-action://set_variable?name=user.exp_${e.key}&value=@{user.exp_${e.key} + ${a}}`) }],
            items: [txt({ width: wrapContent(), text: `+${a}`, font_size: 12, font_weight: 'bold', text_color: C.body })],
          })),
      }),
    ],
  });

const totalRow = (label, valueExpr, color, size, margins) =>
  box({
    orientation: 'horizontal',
    width: matchParent(),
    content_alignment_vertical: 'center',
    margins,
    items: [
      txt({ width: weighted(1), text: label, font_size: size, text_color: C.body }),
      txt({ width: wrapContent(), text: expression(valueExpr), font_size: size, font_weight: 'bold', text_color: color }),
    ],
  });

const totalsCard = card({
  items: [
    sectionLabel('SUMMARY'),
    totalRow('Gross earnings', kes(earnToday), C.ink, 14, { top: 10 }),
    totalRow('Total expenses', kes(expTotal), C.danger, 14, { top: 6 }),
    divider({ top: 12 }),
    box({
      orientation: 'horizontal',
      width: matchParent(),
      content_alignment_vertical: 'center',
      margins: { top: 10 },
      items: [
        txt({ width: weighted(1), text: 'Net profit', font_size: 15, font_weight: 'bold', text_color: C.muted }),
        txt({
          width: wrapContent(),
          text: expression(kes(netToday)),
          font_size: 18,
          font_weight: 'bold',
          text_color: expression(`@{${netToday} >= 0 ? '${C.primary}' : '${C.danger}'}`),
        }),
      ],
    }),
    txt({ text: 'Earnings minus fuel, oil and repairs.', font_size: 11, text_color: C.faint, margins: { top: 8 } }),
  ],
});

const expensesView = box({
  orientation: 'vertical',
  width: matchParent(),
  height: matchParent(),
  paddings: { left: 16, right: 16, top: 12 },
  items: [
    topBar('Expenses', 'home', pill(expression(kes(expTotal)), { bg: C.dangerTint, color: C.dangerInk })),
    scrollArea([...EXPENSES.map(expenseCard), totalsCard, bottomSpacer]),
  ],
});

// ---------------------------------------------------------------------------
// 9. Bottom navigation (Home / Trip / Earnings / Expenses)
// ---------------------------------------------------------------------------
const navBtn = (label, tabId, icon, badge = false) =>
  box({
    width: weighted(1),
    height: fixed(64),
    content_alignment_horizontal: 'center',
    content_alignment_vertical: 'center',
    actions: [{ log_id: `nav_${tabId}`, url: `div-action://set_variable?name=user.active_tab&value=${tabId}` }],
    items: [
      box({
        width: wrapContent(),
        orientation: 'horizontal',
        content_alignment_vertical: 'center',
        paddings: { left: 13, right: 13, top: 8, bottom: 8 },
        background: [solid(expression(`@{user.active_tab == '${tabId}' ? '${C.tint}' : '#00FFFFFF'}`))],
        border: { corner_radius: 14 },
        items: [
          txt({ width: wrapContent(), text: icon, font_size: 15, margins: { right: 6 } }),
          txt({
            width: wrapContent(),
            text: label,
            font_size: 12,
            font_weight: 'bold',
            text_color: expression(`@{user.active_tab == '${tabId}' ? '${C.onTint}' : '${C.faint}'}`),
          }),
          ...(badge
            ? [
                box({
                  width: wrapContent(),
                  paddings: { left: 5, right: 5, top: 1, bottom: 1 },
                  background: [solid(C.primary)],
                  border: { corner_radius: 10 },
                  margins: { left: 6 },
                  visibility: vis('user.trip_count > 0'),
                  items: [txt({ width: wrapContent(), text: expression('@{toString(toInteger(user.trip_count))}'), font_size: 10, font_weight: 'bold', text_color: '#FFFFFF' })],
                }),
              ]
            : []),
        ],
      }),
    ],
  });

const bottomNav = box({
  orientation: 'horizontal',
  width: matchParent(),
  height: fixed(64),
  background: [solid(C.surface)],
  border: { stroke: { color: C.line, width: 1 } },
  content_alignment_vertical: 'center',
  items: [
    navBtn('Home', 'home', '🏠'),
    navBtn('Trip', 'trip', '🛵'),
    navBtn('Earnings', 'history', '💰', true),
    navBtn('Expenses', 'expenses', '⛽'),
  ],
});

// ---------------------------------------------------------------------------
// 10. Root card
// ---------------------------------------------------------------------------
const root = box({
  orientation: 'vertical',
  width: matchParent(),
  height: matchParent(),
  background: [solid(C.page)],
  items: [
    box({
      orientation: 'vertical',
      width: matchParent(),
      height: matchParent(),
      items: [
        new DivState({
          id: 'main_navigation_view',
          width: matchParent(),
          height: matchParent(),
          state_id_variable: 'user.active_tab',
          default_state_id: 'home',
          states: [
            { state_id: 'home', div: homeView },
            { state_id: 'trip', div: tripView },
            { state_id: 'saved', div: savedView },
            { state_id: 'history', div: historyView },
            { state_id: 'expenses', div: expensesView },
          ],
        }),
      ],
    }),
    bottomNav,
  ],
});

const bodaCard = divCard({}, {
  log_id: 'sdui_boda_driver_app',
  variables,
  states: [{ state_id: 0, div: root }],
});


export const motorbike: WorkspaceDefinition = {
  slug: 'motorbike',
  manifestId: 'motorbike',
  meta: {
    title: 'BODA BODA',
    subtitle: 'Work & payments for motorcycle-taxi riders',
    terminal: 'BODA-001',
    accent: C.amber,
    mode: 'NATIVE',
    capabilities: ['haptics', 'clipboard'],
    tools: { hapticVibration: true, clipboardAccess: true, offlinePageCaching: true },
    unread: 1,
  },
  hub: bodaCard as WorkspaceDefinition['hub'],
  cards: [],
  catalog: {
    title: 'Today',
    subtitle: 'Home, trip, earnings and expenses in one card.',
    entries: [],
  },
  collections: [],
}
