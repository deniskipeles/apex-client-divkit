import {
  boolVar, button, card, chart, container, divKitCard, expression, fab, fixed, formModal,
  inlineBanner, intVar, jsonList, localDb, matchParent, metric, navPop, navPush, numpad,
  palette, progressRing, radius, segmented, separator, setVariable, signature, space,
  stickyFooter, stringVar, text, textField, toast, tool, type as ramp,
} from '../kit/index.ts'
import type { WorkspaceDefinition } from '../types.ts'

const ACCENT = '#F59E0B'
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

const weekly = DAYS.map((day, index) => ({ day, fare: [1240, 980, 1610, 1050, 1870, 2240, 0][index] }))
const trips = [
  { id: 't1', day: 'Sat', fare: 350, payment: 'M-Pesa', route: 'Kawangware → CBD' },
  { id: 't2', day: 'Sat', fare: 220, payment: 'Cash', route: 'Westlands → Parklands' },
  { id: 't3', day: 'Fri', fare: 480, payment: 'M-Pesa', route: 'CBD → Karen' },
]

const variables = [
  stringVar('user.workspace_title', 'BODA BODA'),
  stringVar('user.rider_name', 'Rider'),
  stringVar('user.earnings_today', '0'),
  intVar('user.trip_count', 0),
  stringVar('user.net_profit', '0'),
  stringVar('user.fare_input', '150'),
  stringVar('user.payment_method', 'M-Pesa'),
  stringVar('user.route_input', ''),
  stringVar('user.weekly_json', JSON.stringify(weekly)),
  stringVar('user.trips_json', JSON.stringify(trips)),
  stringVar('user.expenses_json', '[]'),
  stringVar('user.handover_status', 'NOT_STARTED'),
  boolVar('user.trip_modal', false),
  stringVar('user.last_action', 'Ready'),
]

const header = (title: string, subtitle: string, back: boolean) =>
  container({
    orientation: 'horizontal',
    spacing: space(2),
    padding: { left: space(4), top: space(8), right: space(4), bottom: space(2) },
    items: [
      ...(back
        ? [button({ text: 'Back', actions: [navPop()], tone: 'secondary', width: fixed(80), height: space(10), size: ramp.small, id: 'btn_back' })]
        : []),
      container({
        weight: 1,
        items: [
          text({ text: title, size: ramp.display, weight: 'bold', lines: 2 }),
          text({ text: subtitle, size: ramp.small, color: palette.textMuted, lines: 2 }),
        ],
      }),
    ],
  })

const statusStrip = () =>
  container({
    orientation: 'horizontal',
    spacing: space(2),
    padding: { left: space(4), right: space(4), top: space(1), bottom: space(2) },
    items: [
      metric({ label: 'TODAY', value: expression("'KES ' + user.earnings_today"), tone: palette.accent, width: matchParent(), id: 'metric_today' }),
      metric({ label: 'TRIPS', value: expression('user.trip_count'), width: matchParent(), id: 'metric_trips' }),
      metric({ label: 'NET', value: expression("'KES ' + user.net_profit"), width: matchParent(), id: 'metric_net' }),
    ],
  })

const hub = divKitCard({
  logId: 'motorbike_hub',
  variables,
  states: [{
    div: container({
      spacing: space(2),
      padding: { bottom: space(4) },
      items: [
        header('BODA BODA', 'Every trip, fare and fuel cost — in one place', false),
        statusStrip(),
        container({
          padding: { left: space(4), right: space(4) },
          spacing: space(2),
          items: [
            chart({
              id: 'chart_week',
              chartType: 'bar',
              title: 'THIS WEEK',
              subtitle: 'Fares per day',
              dataVar: 'user.weekly_json',
              labelField: 'day',
              valueField: 'fare',
              valuePrefix: 'KES ',
              height: 190,
            }),
            inlineBanner({ text: expression("'Last action: ' + user.last_action"), tone: 'info', id: 'banner_last' }),
          ],
        }),
        container({
          padding: { left: space(4), right: space(4), top: space(2) },
          spacing: space(2),
          items: [
            button({ text: 'Record a trip', actions: [navPush('trip')], id: 'nav_trip' }),
            button({ text: 'Earnings', actions: [navPush('earnings')], tone: 'secondary', id: 'nav_earnings' }),
            button({ text: 'Expenses', actions: [navPush('expenses')], tone: 'secondary', id: 'nav_expenses' }),
            button({ text: 'Handover', actions: [navPush('handover')], tone: 'secondary', id: 'nav_handover' }),
          ],
        }),
      ],
    }),
  }],
})

const trip = divKitCard({
  logId: 'motorbike_trip',
  variables,
  states: [{
    div: container({
      spacing: space(2),
      padding: { bottom: space(4) },
      items: [
        header('Record a trip', 'Fare, route and payment method', true),
        container({
          padding: { left: space(4), right: space(4) },
          spacing: space(3),
          items: [
            card([
              text({ text: 'FARE', size: ramp.label, weight: 'bold', color: palette.textMuted }),
              text({ text: expression("'KES ' + user.fare_input"), size: ramp.display, weight: 'bold', color: palette.accent, id: 'trip_fare' }),
              numpad({ variable: 'user.fare_input', label: 'Amount', id: 'trip_numpad' }),
              container({
                orientation: 'horizontal',
                spacing: space(2),
                items: [100, 150, 200, 300].map((amount) =>
                  button({
                    text: String(amount),
                    tone: 'secondary',
                    size: ramp.small,
                    height: space(10),
                    width: matchParent(),
                    actions: [setVariable('user.fare_input', String(amount))],
                    id: `fare_${amount}`,
                  })),
              }),
            ]),
            card([
              text({ text: 'PAYMENT', size: ramp.label, weight: 'bold', color: palette.textMuted }),
              segmented({
                activeValue: 'M-Pesa',
                items: ['Cash', 'M-Pesa', 'Fuel advance'].map((value) => ({
                  value,
                  label: value,
                  actions: [setVariable('user.payment_method', value)],
                })),
                id: 'trip_payment',
              }),
              textField({ label: 'Route', variable: 'user.route_input', placeholder: 'Kawangware → CBD', id: 'trip_route' }),
            ]),
          ],
        }),
        stickyFooter({
          body: [],
          footer: [
            container({
              padding: { left: space(4), right: space(4) },
              spacing: space(2),
              items: [
                button({
                  text: 'Save trip',
                  actions: [
                    localDb('db.insert', {
                      collection: 'trips',
                      data: { day: 'Today', fare: expression('user.fare_input'), payment: expression('user.payment_method'), route: expression('user.route_input') },
                    }),
                    setVariable('user.last_action', 'Trip saved'),
                    navPop(),
                  ],
                  id: 'trip_save',
                }),
                button({ text: 'Cancel', tone: 'ghost', actions: [navPop()], id: 'trip_cancel' }),
              ],
            }),
          ],
          id: 'trip_footer',
        }),
      ],
    }),
  }],
})

const earnings = divKitCard({
  logId: 'motorbike_earnings',
  variables,
  states: [{
    div: container({
      spacing: space(2),
      padding: { bottom: space(4) },
      items: [
        header('Earnings', 'Fares by day and trip', true),
        container({
          padding: { left: space(4), right: space(4) },
          spacing: space(3),
          items: [
            chart({
              id: 'earnings_chart',
              chartType: 'line',
              title: 'FARES BY DAY',
              subtitle: 'Tap a point to inspect it',
              dataVar: 'user.weekly_json',
              labelField: 'day',
              valueField: 'fare',
              valuePrefix: 'KES ',
              height: 210,
            }),
            text({ text: 'TRIP LOG', size: ramp.label, weight: 'bold', color: palette.textMuted }),
            jsonList({
              id: 'earnings_list',
              dataVar: 'user.trips_json',
              emptyText: 'No trips recorded yet',
              rowCard: container({
                orientation: 'horizontal',
                spacing: space(2),
                padding: space(2),
                background: palette.surfaceAlt,
                radius: radius.md,
                stroke: { color: palette.line, width: 1 },
                items: [
                  container({
                    width: matchParent(),
                    items: [
                      text({ text: '@{item.route}', size: ramp.small, weight: 'bold' }),
                      text({ text: '@{item.day} • @{item.payment}', size: ramp.label, color: palette.textMuted }),
                    ],
                  }),
                  text({ text: 'KES @{item.fare}', size: ramp.body, weight: 'bold', color: palette.accent }),
                ],
              }),
            }),
          ],
        }),
      ],
    }),
  }],
})

const expenses = divKitCard({
  logId: 'motorbike_expenses',
  variables,
  states: [{
    div: container({
      spacing: space(2),
      padding: { bottom: space(4) },
      items: [
        header('Expenses', 'Fuel, repairs and licence', true),
        container({
          padding: { left: space(4), right: space(4) },
          spacing: space(3),
          items: [
            segmented({
              activeValue: 'Fuel',
              items: ['Fuel', 'Repairs', 'Licence'].map((value) => ({ value, label: value })),
              id: 'expense_kind',
            }),
            jsonList({
              id: 'expense_list',
              dataVar: 'user.expenses_json',
              emptyText: 'No expenses logged today',
              rowCard: container({
                orientation: 'horizontal',
                padding: space(2),
                background: palette.surfaceAlt,
                radius: radius.md,
                items: [
                  text({ text: '@{item.kind}', size: ramp.small, width: matchParent() }),
                  text({ text: 'KES @{item.amount}', size: ramp.small, weight: 'bold', color: palette.warn }),
                ],
              }),
            }),
            fab({ label: 'Log expense', actions: [setVariable('user.expense_modal', 'true')], id: 'expense_fab' }),
            formModal({
              visible: false,
              title: 'Log an expense',
              body: [
                text({ text: 'Amount and note', size: ramp.small, color: palette.textMuted }),
                textField({ label: 'Amount', variable: 'user.expense_amount', placeholder: '500' }),
              ],
              primaryLabel: 'Save',
              primary: localDb('db.insert', {
                collection: 'expenses',
                data: { kind: expression('user.expense_kind'), amount: expression('user.expense_amount') },
              }),
              secondaryLabel: 'Cancel',
              secondary: setVariable('user.expense_modal', 'false'),
              id: 'expense_modal',
            }),
          ],
        }),
      ],
    }),
  }],
})

const handover = divKitCard({
  logId: 'motorbike_handover',
  variables,
  states: [{
    div: container({
      spacing: space(2),
      padding: { bottom: space(4) },
      items: [
        header('Handover', 'Close the day with the owner', true),
        container({
          padding: { left: space(4), right: space(4) },
          spacing: space(3),
          items: [
            progressRing({
              value: 0.72,
              label: 'Cash target',
              caption: 'KES 3,600 of 5,000',
              id: 'handover_ring',
            }),
            text({ text: 'Rider signature', size: ramp.label, weight: 'bold', color: palette.textMuted }),
            signature({ variable: 'user.handover_signature', label: 'Sign to confirm', id: 'handover_signature' }),
            inlineBanner({ text: expression("'Status: ' + user.handover_status"), tone: 'success', id: 'handover_status' }),
            toast({ text: expression("'Handover ' + user.handover_status"), id: 'handover_toast' }),
          ],
        }),
        stickyFooter({
          body: [],
          footer: [
            container({
              padding: { left: space(4), right: space(4) },
              spacing: space(2),
              items: [
                button({
                  text: 'Confirm handover',
                  actions: [
                    localDb('db.insert', { collection: 'handovers', data: { status: 'CONFIRMED', signature: expression('user.handover_signature') } }),
                    setVariable('user.handover_status', 'CONFIRMED'),
                  ],
                  id: 'handover_confirm',
                }),
                button({ text: 'Back to hub', tone: 'ghost', actions: [navPop()], id: 'handover_back' }),
              ],
            }),
          ],
          id: 'handover_footer',
        }),
      ],
    }),
  }],
})

const collection = (id: number, name: string, fields: Record<string, string>, seed: Array<Record<string, unknown>>) => ({
  id,
  name,
  schema: {
    fields: Object.fromEntries(
      Object.entries(fields).map(([field, type]) => [field, { type, required: false, uid: `${name}_${field}` }]),
    ),
  },
  seed,
})

export const motorbike: WorkspaceDefinition = {
  slug: 'motorbike',
  manifestId: 'motorbike',
  meta: {
    title: 'BODA BODA',
    subtitle: 'Work & payments for motorcycle-taxi riders',
    terminal: 'BODA-001',
    accent: ACCENT,
    mode: 'NATIVE',
    capabilities: ['haptics', 'clipboard'],
    tools: { hapticVibration: true, clipboardAccess: true, offlinePageCaching: true },
    unread: 1,
  },
  hub,
  cards: [
    { name: 'trip', card: trip },
    { name: 'earnings', card: earnings },
    { name: 'expenses', card: expenses },
    { name: 'handover', card: handover },
  ],
  catalog: {
    title: 'Today',
    subtitle: 'Pick a workflow to begin.',
    entries: [
      { file: 'trip', label: 'Record a trip', icon: 'add_circle', accent_color: ACCENT, subtitle: 'Fare, route and payment' },
      { file: 'earnings', label: 'Earnings', icon: 'trending_up', accent_color: ACCENT, subtitle: 'Fares by day' },
      { file: 'expenses', label: 'Expenses', icon: 'receipt', accent_color: ACCENT, subtitle: 'Fuel and repairs' },
      { file: 'handover', label: 'Handover', icon: 'check_circle', accent_color: ACCENT, subtitle: 'Close the day' },
    ],
  },
  collections: [
    { file: 'trips.json', json: collection(1, 'trips', { day: 'string', fare: 'number', payment: 'string', route: 'string' }, trips) },
    { file: 'expenses.json', json: collection(2, 'expenses', { kind: 'string', amount: 'number', note: 'string' }, []) },
    { file: 'handovers.json', json: collection(3, 'handovers', { status: 'string', signature: 'string' }, []) },
  ],
}
