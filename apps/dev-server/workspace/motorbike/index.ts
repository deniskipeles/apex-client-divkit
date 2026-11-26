import {
  boolVar, button, card, chart, container, divKitCard, expression, fab, fixed, formModal,
  grid, hero, inlineBanner, intVar, jsonList, keyValue, localDb, matchParent, metric, navPop,
  navPush, numpad, palette, progressRing, quickTile, radius, resultLine, sectionTitle, segmented,
  separator, setVariable, signature, space, stickyFooter, stringVar, text, textField, toast,
  tool, type as ramp,
} from '../kit/index.ts'
import type { DivNode } from '../kit/div.ts'
import type { WorkspaceDefinition } from '../types.ts'

const ACCENT = '#F5A83C'
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

const GUTTER = { left: space(5), right: space(5) }

const statusStrip = () =>
  grid(
    [
      metric({ label: 'TODAY', value: expression("'KES ' + user.earnings_today"), tone: palette.accent, id: 'metric_today' }),
      metric({ label: 'TRIPS', value: expression('user.trip_count'), id: 'metric_trips' }),
      metric({ label: 'NET', value: expression("'KES ' + user.net_profit"), id: 'metric_net' }),
    ],
    3,
    'metric_strip',
  )

const tripRow = (routeExpr: string, metaExpr: string, fareExpr: string, id: string): DivNode =>
  container({
    orientation: 'horizontal',
    spacing: space(2),
    padding: space(3),
    background: palette.surfaceAlt,
    radius: radius.md,
    stroke: { color: palette.line, width: 1 },
    width: matchParent(),
    items: [
      container({
        weight: 1,
        spacing: space(1),
        items: [
          text({ text: routeExpr, size: ramp.small, weight: 'bold', lines: 1 }),
          text({ text: metaExpr, size: ramp.label, color: palette.textMuted, lines: 1 }),
        ],
      }),
      text({ text: fareExpr, size: ramp.body, weight: 'bold', color: palette.accent, align: 'right' }),
    ],
    id,
  })

const hub = divKitCard({
  logId: 'motorbike_hub',
  variables,
  states: [{
    div: container({
      spacing: space(3),
      padding: { bottom: space(6) },
      items: [
        hero({
          title: 'BODA BODA',
          subtitle: 'Every trip, fare and fuel cost — in one place',
          accent: ACCENT,
          badge: 'RIDING',
          id: 'hub_hero',
        }),
        container({ ...GUTTER, spacing: space(3), items: [
          statusStrip(),
          card(
            [
              sectionTitle('THIS WEEK'),
              chart({
                id: 'chart_week',
                chartType: 'bar',
                title: 'Fares per day',
                subtitle: 'Tap a bar to inspect it',
                dataVar: 'user.weekly_json',
                labelField: 'day',
                valueField: 'fare',
                valuePrefix: 'KES ',
                accentColor: ACCENT,
                height: 190,
              }),
            ],
            { elevation: 'raised' },
          ),
          resultLine({
            label: 'LAST ACTION',
            value: expression("user.last_action == '' ? 'Ready' : user.last_action"),
            tone: 'info',
            id: 'banner_last',
          }),
          sectionTitle('WORKFLOWS'),
          grid(
            [
              quickTile({ label: 'Record a trip', subtitle: 'Fare, route, payment', icon: 'add', tone: 'primary', actions: [navPush('trip')], id: 'nav_trip' }),
              quickTile({ label: 'Earnings', subtitle: 'Fares by day', icon: 'trending_up', tone: 'accent', actions: [navPush('earnings')], id: 'nav_earnings' }),
              quickTile({ label: 'Expenses', subtitle: 'Fuel and repairs', icon: 'receipt', tone: 'warn', actions: [navPush('expenses')], id: 'nav_expenses' }),
              quickTile({ label: 'Handover', subtitle: 'Close the day', icon: 'task_complete', tone: 'info', actions: [navPush('handover')], id: 'nav_handover' }),
            ],
            2,
            'hub_tiles',
          ),
        ] }),
      ],
    }),
  }],
})

const trip = divKitCard({
  logId: 'motorbike_trip',
  variables,
  states: [{
    div: container({
      spacing: space(3),
      padding: { bottom: space(6) },
      items: [
        hero({
          title: 'Record a trip',
          subtitle: 'Fare, route and payment method',
          accent: ACCENT,
          back: true,
          id: 'trip_hero',
        }),
        container({
          ...GUTTER,
          spacing: space(3),
          items: [
            card([
              sectionTitle('FARE'),
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
              sectionTitle('PAYMENT'),
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
              ...GUTTER,
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
      spacing: space(3),
      padding: { bottom: space(6) },
      items: [
        hero({
          title: 'Earnings',
          subtitle: 'Fares by day and trip',
          accent: ACCENT,
          back: true,
          id: 'earnings_hero',
        }),
        container({
          ...GUTTER,
          spacing: space(3),
          items: [
            card([
              sectionTitle('FARES BY DAY'),
              chart({
                id: 'earnings_chart',
                chartType: 'line',
                title: 'Tap a point to inspect it',
                subtitle: 'Mon – Sun',
                dataVar: 'user.weekly_json',
                labelField: 'day',
                valueField: 'fare',
                valuePrefix: 'KES ',
                accentColor: ACCENT,
                height: 210,
              }),
            ]),
            sectionTitle('TRIP LOG'),
            jsonList({
              id: 'earnings_list',
              dataVar: 'user.trips_json',
              emptyText: 'No trips recorded yet',
              rowCard: tripRow('@{item.route}', '@{item.day} • @{item.payment}', 'KES @{item.fare}', 'trip_row'),
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
      spacing: space(3),
      padding: { bottom: space(6) },
      items: [
        hero({
          title: 'Expenses',
          subtitle: 'Fuel, repairs and licence',
          accent: ACCENT,
          back: true,
          id: 'expenses_hero',
        }),
        container({
          ...GUTTER,
          spacing: space(3),
          items: [
            segmented({
              activeValue: 'Fuel',
              items: ['Fuel', 'Repairs', 'Licence'].map((value) => ({ value, label: value })),
              id: 'expense_kind',
            }),
            card([
              sectionTitle('TODAY'),
              jsonList({
                id: 'expense_list',
                dataVar: 'user.expenses_json',
                emptyText: 'No expenses logged today',
                rowCard: tripRow('@{item.kind}', 'Expense', 'KES @{item.amount}', 'expense_row'),
              }),
            ]),
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
      spacing: space(3),
      padding: { bottom: space(6) },
      items: [
        hero({
          title: 'Handover',
          subtitle: 'Close the day with the owner',
          accent: ACCENT,
          back: true,
          id: 'handover_hero',
        }),
        container({
          ...GUTTER,
          spacing: space(3),
          items: [
            card([
              sectionTitle('CASH TARGET'),
              progressRing({
                value: 0.72,
                label: 'Cash target',
                caption: 'KES 3,600 of 5,000',
                id: 'handover_ring',
              }),
              keyValue('Day total', 'KES 3,600', { id: 'handover_total' }),
              keyValue('Expected', 'KES 5,000', { valueTone: palette.textMuted, id: 'handover_expected' }),
            ]),
            card([
              sectionTitle('RIDER SIGNATURE'),
              signature({ variable: 'user.handover_signature', label: 'Sign to confirm', id: 'handover_signature' }),
              resultLine({
                label: 'STATUS',
                value: expression('user.handover_status'),
                tone: 'accent',
                id: 'handover_status',
              }),
              toast({ text: expression("'Handover ' + user.handover_status"), id: 'handover_toast' }),
            ]),
          ],
        }),
        stickyFooter({
          body: [],
          footer: [
            container({
              ...GUTTER,
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
      { file: 'trip', label: 'Record a trip', icon: 'add', accent_color: ACCENT, subtitle: 'Fare, route and payment' },
      { file: 'earnings', label: 'Earnings', icon: 'trending_up', accent_color: ACCENT, subtitle: 'Fares by day' },
      { file: 'expenses', label: 'Expenses', icon: 'receipt', accent_color: ACCENT, subtitle: 'Fuel and repairs' },
      { file: 'handover', label: 'Handover', icon: 'task_complete', accent_color: ACCENT, subtitle: 'Close the day' },
    ],
  },
  collections: [
    { file: 'trips.json', json: collection(1, 'trips', { day: 'string', fare: 'number', payment: 'string', route: 'string' }, trips) },
    { file: 'expenses.json', json: collection(2, 'expenses', { kind: 'string', amount: 'number', note: 'string' }, []) },
    { file: 'handovers.json', json: collection(3, 'handovers', { status: 'string', signature: 'string' }, []) },
  ],
  targets: { android: true },
}
