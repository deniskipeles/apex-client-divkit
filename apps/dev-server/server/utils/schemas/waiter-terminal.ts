// ---------------------------------------------------------------------------
// Waiter Terminal — redesigned DivKit card (server-driven UI)
// Generated with @divkitframework/jsonbuilder
//
// Fixes vs. the previous version:
//   * dynamic background colors now use expression() directly (the old
//     { type: 'expression', ... } helper produced escaped "\@{...}" strings
//     that DivKit cannot evaluate -> colors never changed)
//   * DivActionSubmit serialized as an empty { "type": "submit" }; the submit
//     action is now a raw object so url/method/enabled survive serialization
//   * containers use content_alignment_horizontal/vertical (the real DivKit
//     properties; "alignment_*" is silently ignored)
//   * split rows use weighted() widths instead of percentage (no overflow)
//   * totals are formatted with exact integer-cent math (no more "$0.0")
//   * added a "sent" state, cart lines, empty states and disabled styling
// ---------------------------------------------------------------------------
import {
  expression,
  divCard,
  DivContainer,
  DivText,
  DivImage,
  DivSolidBackground,
  NumberVariable,
  StringVariable,
  DivState,
  matchParent,
  weighted,
} from '@divkitframework/jsonbuilder';

// ---------------------------------------------------------------------------
// 0. Design tokens & helpers
// ---------------------------------------------------------------------------
const C = {
  page: '#F1F5F9',
  surface: '#FFFFFF',
  line: '#E2E8F0',
  ink: '#0F172A',
  body: '#334155',
  muted: '#64748B',
  faint: '#94A3B8',
  primary: '#4F46E5',
  primaryDeep: '#312E81',
  primaryVivid: '#7C3AED',
  tint: '#EEF2FF',
  onTint: '#4F46E5',
  accent: '#059669',
  danger: '#EF4444',
  dangerTint: '#FEE2E2',
  disabled: '#CBD5E1',
  headerText: '#C7D2FE',
  headerTextDim: '#A5B4FC',
};

const fixed = (v) => ({ type: 'fixed', value: v });
const wrapContent = () => ({ type: 'wrap_content' });
const solid = (color) => new DivSolidBackground({ color });
const box = (props) => new DivContainer(props);
const txt = (props) => new DivText(props);
const img = (props) => new DivImage(props);

// visibility helpers (expressions are legal for the visibility property)
const vis = (cond) => expression(`@{${cond} ? 'visible' : 'gone'}`);
const visNot = (cond) => expression(`@{${cond} ? 'gone' : 'visible'}`);

// the whole "Send to Kitchen" flow is only allowed when the cart is non-empty
const readyExpr = () => expression('@{qty_burger + qty_pizza + qty_salad > 0}');

// Exact money formatting without number_format (not supported by the schema):
// work in integer cents, then build "$W.CC" with toString()/toInteger().
// The cents value is forced through toInteger() first: DivKit numbers are
// doubles and toString(98.0) renders as "98.0", while integers stay clean.
const money = (centsExpr) => {
  const cents = `toInteger(${centsExpr})`;
  const whole = `toInteger((${cents}) / 100)`;
  const rem = `((${cents}) - toInteger((${cents}) / 100) * 100)`;
  const remStr = `(${rem} < 10 ? '0' + toString(${rem}) : toString(${rem}))`;
  return `@{'$' + toString(${whole}) + '.' + ${remStr}}`;
};

// Prices in cents (integer-exact), tax 8%
const PRICE = { burger: 1299, pizza: 1550, salad: 800 };
const subtotalCents = 'qty_burger * 1299 + qty_pizza * 1550 + qty_salad * 800';
const totalCents = `toInteger((${subtotalCents}) * 108 / 100 + 0.5)`;

// ---------------------------------------------------------------------------
// 1. Global state
// ---------------------------------------------------------------------------
const variables = [
  new StringVariable({ name: 'active_tab', value: 'tables' }),
  new StringVariable({ name: 'selected_table', value: 'None' }),
  new NumberVariable({ name: 'qty_burger', value: 0 }),
  new NumberVariable({ name: 'qty_pizza', value: 0 }),
  new NumberVariable({ name: 'qty_salad', value: 0 }),
  new NumberVariable({ name: 'last_order_count', value: 0 }),
];

// ---------------------------------------------------------------------------
// 2. Header (shared, gradient)
// ---------------------------------------------------------------------------
const header = box({
  orientation: 'vertical',
  width: matchParent(),
  background: [{ type: 'gradient', colors: [C.primaryDeep, C.primary, C.primaryVivid], angle: 135 }],
  paddings: { left: 20, right: 20, top: 22, bottom: 18 },
  items: [
    txt({ text: 'TABLE SERVICE', font_size: 10, font_weight: 'bold', letter_spacing: 2, text_color: C.headerTextDim }),
    txt({ text: 'Waiter Terminal', font_size: 24, font_weight: 'bold', text_color: '#FFFFFF', margins: { top: 4 } }),
    txt({
      text: 'No table selected yet — pick one below',
      font_size: 13, text_color: C.headerText, margins: { top: 6 },
      visibility: vis(`selected_table == 'None'`),
    }),
    box({
      width: wrapContent(),
      orientation: 'horizontal', content_alignment_vertical: 'center', margins: { top: 6 },
      visibility: visNot(`selected_table == 'None'`),
      items: [
        txt({ width: wrapContent(), text: 'Now serving:', font_size: 13, text_color: C.headerText }),
        txt({ width: wrapContent(), text: expression('@{selected_table}'), font_size: 13, font_weight: 'bold', text_color: '#FFFFFF', margins: { left: 6 } }),
      ],
    }),
  ],
});

// ---------------------------------------------------------------------------
// 3. Tables view (floor plan)
// ---------------------------------------------------------------------------
const tableCard = (n) => box({
  width: weighted(1),
  height: fixed(148),
  margins: { left: 5, right: 5, bottom: 10 },
  orientation: 'vertical',
  content_alignment_horizontal: 'center',
  content_alignment_vertical: 'center',
  background: [solid(expression(`@{selected_table == 'Table ${n}' ? '${C.tint}' : '${C.surface}'}`))],
  border: {
    corner_radius: 18,
    stroke: { color: expression(`@{selected_table == 'Table ${n}' ? '${C.primary}' : '${C.line}'}`), width: 2 },
  },
  actions: [
    { log_id: `select_table_${n}`, url: expression(`div-action://set_variable?name=selected_table&value=Table ${n}`) },
    { log_id: `goto_menu_${n}`, url: 'div-action://set_variable?name=active_tab&value=menu' },
  ],
  items: [
    img({
      image_url: expression(`@{selected_table == 'Table ${n}' ? 'https://img.icons8.com/ios-filled/100/4F46E5/table.png' : 'https://img.icons8.com/ios/100/94A3B8/table.png'}`),
      width: fixed(44), height: fixed(44),
    }),
    txt({
      width: wrapContent(),
      text: `Table ${n}`, font_size: 17, font_weight: 'bold', margins: { top: 10 },
      text_color: expression(`@{selected_table == 'Table ${n}' ? '${C.primary}' : '${C.ink}'}`),
    }),
    box({
      width: wrapContent(),
      margins: { top: 8 },
      paddings: { left: 10, right: 10, top: 3, bottom: 3 },
      background: [solid(expression(`@{selected_table == 'Table ${n}' ? '${C.primary}' : '#F1F5F9'}`))],
      border: { corner_radius: 20 },
      items: [
        txt({
          width: wrapContent(),
          text: expression(`@{selected_table == 'Table ${n}' ? 'SELECTED' : 'AVAILABLE'}`),
          font_size: 10, font_weight: 'bold',
          text_color: expression(`@{selected_table == 'Table ${n}' ? '#FFFFFF' : '${C.muted}'}`),
        }),
      ],
    }),
  ],
});

const openMenuBtn = box({
  orientation: 'horizontal', width: matchParent(), height: fixed(52),
  background: [solid(expression(`@{selected_table == 'None' ? '${C.disabled}' : '${C.primary}'}`))],
  border: { corner_radius: 14 },
  margins: { top: 6 },
  content_alignment_horizontal: 'center',
  content_alignment_vertical: 'center',
  actions: [{ log_id: 'open_menu', url: 'div-action://set_variable?name=active_tab&value=menu', enabled: expression(`@{selected_table != 'None'}`) }],
  items: [
    txt({
      width: wrapContent(),
      text: 'Select a table first', font_size: 15, font_weight: 'bold', text_color: '#FFFFFF',
      visibility: vis(`selected_table == 'None'`),
    }),
    box({
      width: wrapContent(),
      orientation: 'horizontal', content_alignment_vertical: 'center',
      visibility: visNot(`selected_table == 'None'`),
      items: [
        txt({ width: wrapContent(), text: 'Open menu for', font_size: 15, font_weight: 'bold', text_color: '#FFFFFF' }),
        txt({ width: wrapContent(), text: expression('@{selected_table}'), font_size: 15, font_weight: 'bold', text_color: '#FFFFFF', margins: { left: 6 } }),
      ],
    }),
  ],
});

const tablesView = box({
  orientation: 'vertical', width: matchParent(), height: matchParent(),
  paddings: { left: 16, right: 16, top: 18 },
  items: [
    txt({ text: 'Floor plan', font_size: 20, font_weight: 'bold', text_color: C.ink }),
    txt({ text: 'Pick a table to start an order', font_size: 13, text_color: C.muted, margins: { top: 2, bottom: 14 } }),
    box({ orientation: 'horizontal', width: matchParent(), items: [tableCard(1), tableCard(2)] }),
    box({ orientation: 'horizontal', width: matchParent(), items: [tableCard(3), tableCard(4)] }),
    openMenuBtn,
  ],
});

// ---------------------------------------------------------------------------
// 4. Menu view
// ---------------------------------------------------------------------------
const stepperBtn = (label, bg, fg, url, logId) => box({
  width: fixed(32), height: fixed(32),
  background: [solid(bg)], border: { corner_radius: 10 },
  content_alignment_horizontal: 'center',
  content_alignment_vertical: 'center',
  actions: [{ log_id: logId, url: expression(url) }],
  items: [txt({ text: label, font_size: 18, font_weight: 'bold', text_color: fg })],
});

const menuItem = (id, name, desc, imageUrl) => box({
  orientation: 'horizontal', width: matchParent(),
  background: [solid(C.surface)],
  border: { corner_radius: 16, stroke: { color: C.line, width: 1 } },
  margins: { bottom: 8 },
  paddings: { left: 8, right: 8, top: 8, bottom: 8 },
  content_alignment_vertical: 'center',
  items: [
    img({
      image_url: imageUrl, width: fixed(56), height: fixed(56),
      border: { corner_radius: 12 }, margins: { right: 10 },
    }),
    box({
      orientation: 'vertical', width: weighted(1), margins: { right: 6 },
      items: [
        txt({ text: name, font_size: 15, font_weight: 'bold', text_color: C.ink }),
        txt({ text: desc, font_size: 11, text_color: C.muted, margins: { top: 2, bottom: 5 }, max_lines: 2 }),
        txt({ text: expression(money(`qty_${id} * ${PRICE[id]}`)), font_size: 15, font_weight: 'bold', text_color: C.accent }),
      ],
    }),
    box({
      width: fixed(112),
      content_alignment_horizontal: 'center',
      content_alignment_vertical: 'center',
      items: [
        // stepper — only when qty > 0
        box({
          orientation: 'horizontal', content_alignment_vertical: 'center',
          visibility: vis(`qty_${id} > 0`),
          items: [
            stepperBtn('−', C.dangerTint, C.danger, `div-action://set_variable?name=qty_${id}&value=@{qty_${id} > 0 ? qty_${id} - 1 : 0}`, `minus_${id}`),
            txt({ text: expression(`@{qty_${id}}`), font_size: 16, font_weight: 'bold', width: fixed(34), text_alignment_horizontal: 'center' }),
            stepperBtn('+', C.primary, '#FFFFFF', `div-action://set_variable?name=qty_${id}&value=@{qty_${id} + 1}`, `plus_${id}`),
          ],
        }),
        // tonal ADD pill — only when qty == 0
        box({
          width: wrapContent(), height: fixed(34), paddings: { left: 14, right: 14 },
          background: [solid(C.tint)], border: { corner_radius: 12 },
          content_alignment_vertical: 'center',
          visibility: visNot(`qty_${id} > 0`),
          actions: [{ log_id: `add_${id}`, url: `div-action://set_variable?name=qty_${id}&value=1` }],
          items: [txt({ text: '+ ADD', font_size: 12, font_weight: 'bold', text_color: C.onTint })],
        }),
      ],
    }),
  ],
});

const sendButton = box({
  orientation: 'horizontal', width: matchParent(), height: fixed(52),
  background: [solid(expression(`@{qty_burger + qty_pizza + qty_salad > 0 ? '${C.primary}' : '${C.disabled}'}`))],
  border: { corner_radius: 14 },
  margins: { top: 14 },
  content_alignment_horizontal: 'center',
  content_alignment_vertical: 'center',
  actions: [
    { log_id: 'submit_order', type: 'submit', method: 'POST', url: expression(`https://your-api.com/order?table=@{selected_table}&burger=@{qty_burger}&pizza=@{qty_pizza}&salad=@{qty_salad}`), enabled: readyExpr() },
    { log_id: 'save_order_count', url: expression(`div-action://set_variable?name=last_order_count&value=@{qty_burger + qty_pizza + qty_salad}`), enabled: readyExpr() },
    { log_id: 'reset_burger', url: 'div-action://set_variable?name=qty_burger&value=0', enabled: readyExpr() },
    { log_id: 'reset_pizza', url: 'div-action://set_variable?name=qty_pizza&value=0', enabled: readyExpr() },
    { log_id: 'reset_salad', url: 'div-action://set_variable?name=qty_salad&value=0', enabled: readyExpr() },
    { log_id: 'goto_sent', url: 'div-action://set_variable?name=active_tab&value=sent', enabled: readyExpr() },
  ],
  items: [
    img({ image_url: 'https://img.icons8.com/ios-filled/50/FFFFFF/waiter.png', width: fixed(20), height: fixed(20), margins: { right: 8 } }),
    txt({ width: wrapContent(), text: 'Send to Kitchen', font_size: 16, font_weight: 'bold', text_color: '#FFFFFF' }),
  ],
});

const cartLine = (name, id) => box({
  orientation: 'horizontal', width: matchParent(),
  content_alignment_vertical: 'center',
  visibility: vis(`qty_${id} > 0`),
  margins: { top: 10 },
  items: [
    txt({ text: name, font_size: 14, text_color: C.body, width: weighted(1) }),
    box({
      width: wrapContent(),
      paddings: { left: 8, right: 8, top: 2, bottom: 2 },
      background: [solid(C.tint)], border: { corner_radius: 8 },
      items: [txt({ width: wrapContent(), text: expression(`@{qty_${id}}`), font_size: 12, font_weight: 'bold', text_color: C.onTint })],
    }),
    txt({ width: wrapContent(), text: expression(money(`qty_${id} * ${PRICE[id]}`)), font_size: 14, font_weight: 'bold', text_color: C.ink, margins: { left: 10 } }),
  ],
});

const cartCard = box({
  orientation: 'vertical', width: matchParent(),
  background: [solid(C.surface)],
  border: { corner_radius: 20, stroke: { color: C.line, width: 1 } },
  paddings: { left: 16, right: 16, top: 16, bottom: 16 },
  margins: { top: 6, bottom: 16 },
  items: [
    txt({ text: 'Your order', font_size: 16, font_weight: 'bold', text_color: C.ink }),
    txt({
      text: 'Nothing here yet — tap + to start the order.',
      font_size: 13, text_color: C.faint, margins: { top: 8 },
      visibility: visNot('qty_burger + qty_pizza + qty_salad > 0'),
    }),
    cartLine('Classic Cheeseburger', 'burger'),
    cartLine('Margherita Pizza', 'pizza'),
    cartLine('Caesar Salad', 'salad'),
    box({
      height: fixed(1), width: matchParent(), background: [solid('#EEF2FF')],
      margins: { top: 12 },
      visibility: vis('qty_burger + qty_pizza + qty_salad > 0'),
    }),
    box({
      orientation: 'horizontal', width: matchParent(),
      content_alignment_vertical: 'center', margins: { top: 10 },
      items: [
        box({
          orientation: 'vertical', width: weighted(1),
          items: [
            txt({ text: 'Total', font_size: 15, font_weight: 'bold', text_color: C.ink }),
            txt({ text: 'Incl. 8% tax', font_size: 11, text_color: C.faint, margins: { top: 1 } }),
          ],
        }),
        txt({ width: wrapContent(), text: expression(money(totalCents)), font_size: 20, font_weight: 'bold', text_color: C.accent }),
      ],
    }),
    sendButton,
  ],
});

// ---------------------------------------------------------------------------
// 4c. Cart view (order review state)
// ---------------------------------------------------------------------------
const cartTopBar = box({
  orientation: 'horizontal', width: matchParent(),
  content_alignment_vertical: 'center',
  margins: { bottom: 10 },
  items: [
    box({
      width: fixed(40), height: fixed(40),
      background: [solid(C.surface)],
      border: { corner_radius: 12, stroke: { color: C.line, width: 1 } },
      content_alignment_horizontal: 'center',
      content_alignment_vertical: 'center',
      actions: [{ log_id: 'back_to_menu', url: 'div-action://set_variable?name=active_tab&value=menu' }],
      items: [txt({ text: '←', font_size: 18, font_weight: 'bold', text_color: C.ink })],
    }),
    box({ width: weighted(1), items: [txt({ text: 'Your order', font_size: 20, font_weight: 'bold', text_color: C.ink, margins: { left: 12 } })] }),
    box({
      width: wrapContent(),
      paddings: { left: 10, right: 10, top: 6, bottom: 6 },
      background: [solid(C.tint)], border: { corner_radius: 12 },
      visibility: visNot(`selected_table == 'None'`),
      items: [txt({ width: wrapContent(), text: expression('@{selected_table}'), font_size: 12, font_weight: 'bold', text_color: C.onTint })],
    }),
  ],
});

const cartView = box({
  orientation: 'vertical', width: matchParent(), height: matchParent(),
  paddings: { left: 16, right: 16, top: 12 },
  items: [
    cartTopBar,
    box({
      orientation: 'vertical', width: matchParent(),
      background: [solid(C.surface)],
      border: { corner_radius: 20, stroke: { color: C.line, width: 1 } },
      paddings: { left: 16, right: 16, top: 16, bottom: 16 },
      items: [
        txt({
          text: 'Nothing here yet — add dishes from the Menu tab.',
          font_size: 13, text_color: C.faint, margins: { top: 2 },
          visibility: visNot('qty_burger + qty_pizza + qty_salad > 0'),
        }),
        cartLine('Classic Cheeseburger', 'burger'),
        cartLine('Margherita Pizza', 'pizza'),
        cartLine('Caesar Salad', 'salad'),
        box({
          height: fixed(1), width: matchParent(), background: [solid('#EEF2FF')],
          margins: { top: 12 },
          visibility: vis('qty_burger + qty_pizza + qty_salad > 0'),
        }),
        box({
          orientation: 'horizontal', width: matchParent(),
          content_alignment_vertical: 'center', margins: { top: 10 },
          items: [
            box({
              orientation: 'vertical', width: weighted(1),
              items: [
                txt({ text: 'Total', font_size: 15, font_weight: 'bold', text_color: C.ink }),
                txt({ text: 'Incl. 8% tax', font_size: 11, text_color: C.faint, margins: { top: 1 } }),
              ],
            }),
            txt({ width: wrapContent(), text: expression(money(totalCents)), font_size: 20, font_weight: 'bold', text_color: C.accent }),
          ],
        }),
      ],
    }),
    sendButton,
  ],
});

const menuTopBar = box({
  orientation: 'horizontal', width: matchParent(),
  content_alignment_vertical: 'center',
  margins: { bottom: 10 },
  items: [
    box({
      width: fixed(40), height: fixed(40),
      background: [solid(C.surface)],
      border: { corner_radius: 12, stroke: { color: C.line, width: 1 } },
      content_alignment_horizontal: 'center',
      content_alignment_vertical: 'center',
      actions: [{ log_id: 'back_to_tables', url: 'div-action://set_variable?name=active_tab&value=tables' }],
      items: [txt({ text: '←', font_size: 18, font_weight: 'bold', text_color: C.ink })],
    }),
    box({ width: weighted(1), items: [txt({ text: 'Menu', font_size: 20, font_weight: 'bold', text_color: C.ink, margins: { left: 12 } })] }),
    box({
      width: wrapContent(),
      paddings: { left: 10, right: 10, top: 6, bottom: 6 },
      background: [solid(C.tint)], border: { corner_radius: 12 },
      visibility: visNot(`selected_table == 'None'`),
      items: [txt({ width: wrapContent(), text: expression('@{selected_table}'), font_size: 12, font_weight: 'bold', text_color: C.onTint })],
    }),
  ],
});

const menuView = box({
  orientation: 'vertical', width: matchParent(), height: matchParent(),
  paddings: { left: 16, right: 16, top: 8 },
  items: [
    menuTopBar,
    menuItem('burger', 'Classic Cheeseburger', 'Angus beef, cheddar, lettuce, tomato.', 'https://images.unsplash.com/photo-1568901346375-23c9450c58cd?w=200&h=200&fit=crop'),
    menuItem('pizza', 'Margherita Pizza', 'Fresh mozzarella, tomato sauce, basil.', 'https://images.unsplash.com/photo-1574071318508-1cdbab80d002?w=200&h=200&fit=crop'),
    menuItem('salad', 'Caesar Salad', 'Romaine, croutons, parmesan, Caesar dressing.', 'https://images.unsplash.com/photo-1550304943-4f24f54ddde9?w=200&h=200&fit=crop'),
  ],
});

// ---------------------------------------------------------------------------
// 5. Sent view (order confirmation state)
// ---------------------------------------------------------------------------
const sentView = box({
  orientation: 'vertical', width: matchParent(), height: matchParent(),
  paddings: { left: 24, right: 24, top: 44 },
  content_alignment_horizontal: 'center',
  items: [
    box({
      width: fixed(96), height: fixed(96),
      background: [solid('#D1FAE5')], border: { corner_radius: 48 },
      content_alignment_horizontal: 'center',
      content_alignment_vertical: 'center',
      items: [txt({ text: '✓', font_size: 44, font_weight: 'bold', text_color: C.accent })],
    }),
    txt({ width: wrapContent(), text: 'Order sent!', font_size: 24, font_weight: 'bold', text_color: C.ink, margins: { top: 20 } }),
    txt({ width: wrapContent(), text: 'The kitchen is preparing it right now.', font_size: 14, text_color: C.muted, margins: { top: 6 } }),
    box({
      width: wrapContent(),
      orientation: 'horizontal', content_alignment_vertical: 'center',
      background: [solid(C.tint)], border: { corner_radius: 14 },
      paddings: { left: 14, right: 14, top: 8, bottom: 8 },
      margins: { top: 16 },
      visibility: vis('last_order_count > 0'),
      items: [
        txt({ width: wrapContent(), text: expression('@{last_order_count}'), font_size: 13, font_weight: 'bold', text_color: C.onTint }),
        txt({ width: wrapContent(), text: ' items on the way', font_size: 13, font_weight: 'bold', text_color: C.onTint }),
      ],
    }),
    box({
      orientation: 'horizontal', width: matchParent(), height: fixed(50),
      background: [solid(C.tint)], border: { corner_radius: 14 },
      margins: { top: 28 },
      content_alignment_horizontal: 'center',
      content_alignment_vertical: 'center',
      actions: [{ log_id: 'new_order', url: 'div-action://set_variable?name=active_tab&value=tables' }],
      items: [txt({ width: wrapContent(), text: 'Start new order', font_size: 15, font_weight: 'bold', text_color: C.onTint })],
    }),
  ],
});

// ---------------------------------------------------------------------------
// 6. Bottom navigation (Tables / Menu / Cart)
// ---------------------------------------------------------------------------
const navBtn = (label, tabId, iconName, badge = false) => box({
  width: weighted(1), height: fixed(64),
  content_alignment_horizontal: 'center',
  content_alignment_vertical: 'center',
  actions: [{ log_id: `nav_${tabId}`, url: `div-action://set_variable?name=active_tab&value=${tabId}` }],
  items: [
    box({
      width: wrapContent(),
      orientation: 'horizontal', content_alignment_vertical: 'center',
      paddings: { left: 16, right: 16, top: 9, bottom: 9 },
      background: [solid(expression(`@{active_tab == '${tabId}' ? '${C.tint}' : '#00FFFFFF'}`))],
      border: { corner_radius: 14 },
      items: [
        img({
          image_url: expression(`@{active_tab == '${tabId}' ? 'https://img.icons8.com/ios-filled/50/4F46E5/${iconName}.png' : 'https://img.icons8.com/ios/50/94A3B8/${iconName}.png'}`),
          width: fixed(20), height: fixed(20), margins: { right: 7 },
        }),
        txt({
          width: wrapContent(),
          text: label, font_size: 13, font_weight: 'bold',
          text_color: expression(`@{active_tab == '${tabId}' ? '${C.primary}' : '${C.faint}'}`),
        }),
        ...(badge ? [box({
          width: wrapContent(),
          paddings: { left: 5, right: 5, top: 1, bottom: 1 },
          background: [solid(C.primary)], border: { corner_radius: 10 },
          margins: { left: 6 },
          visibility: vis('qty_burger + qty_pizza + qty_salad > 0'),
          items: [txt({ width: wrapContent(), text: expression('@{qty_burger + qty_pizza + qty_salad}'), font_size: 10, font_weight: 'bold', text_color: '#FFFFFF' })],
        })] : []),
      ],
    }),
  ],
});

const bottomNav = box({
  orientation: 'horizontal', width: matchParent(), height: fixed(64),
  background: [solid(C.surface)],
  border: { stroke: { color: C.line, width: 1 } },
  content_alignment_vertical: 'center',
  items: [
    navBtn('Tables', 'tables', 'table'),
    box({ width: fixed(1), height: fixed(28), background: [solid(C.line)] }),
    navBtn('Menu', 'menu', 'restaurant-menu'),
    box({ width: fixed(1), height: fixed(28), background: [solid(C.line)] }),
    navBtn('Cart', 'cart', 'shopping-cart', true),
  ],
});

// ---------------------------------------------------------------------------
// 7. Root card
// ---------------------------------------------------------------------------
const root = box({
  orientation: 'vertical', width: matchParent(), height: matchParent(),
  background: [solid(C.page)],
  items: [
    header,
    box({
      orientation: 'vertical', width: matchParent(), height: matchParent(),
      items: [
        new DivState({
          id: 'main_navigation_view',
          width: matchParent(),
          height: matchParent(),
          state_id_variable: 'active_tab',
          default_state_id: 'tables',
          states: [
            { state_id: 'tables', div: tablesView },
            { state_id: 'menu', div: menuView },
            { state_id: 'cart', div: cartView },
            { state_id: 'sent', div: sentView },
          ],
        }),
      ],
    }),
    bottomNav,
  ],
});

const card = divCard({}, {
  log_id: 'sdui_waiter_app',
  variables,
  states: [{ state_id: 0, div: root }],
});

// ---------------------------------------------------------------------------
// Export for the Nitro server route (server-driven generation) and the CLI
// static build (scripts/build-schemas.mjs). Pure JS — runs on node, workers
// and at prerender time without any node builtins.
// ---------------------------------------------------------------------------
export { card as waiterCard };
