// ---------------------------------------------------------------------------
// Custom Components Demo — DivKit card rendered by playground-registered
// web components.
//
// DivKit's web runtime supports a `custom` div: a div that carries
//   { type: 'custom', custom_type: '<registry key>', custom_props: {...} }
// and is rendered by a host-registered CustomComponentDescription
// (see client/src/divkit/customComponents.ts).
//
// This card demonstrates both extension patterns:
//   1. `stat-ring`   — template snapshot: the template function receives
//                      { props, variables } at render time and emits the
//                      element's inner HTML (an SVG ring).
//   2. `note-frame`  — wrapping children: the card passes `items`, which the
//                      runtime mounts INSIDE the custom element (through the
//                      template's <slot>). Those children are regular DivKit
//                      divs, so they stay fully reactive to variables.
//
// Written as raw JSON on purpose — it doubles as a copy-paste reference for
// the exact wire format of `custom` divs.
// ---------------------------------------------------------------------------
const card = {
  templates: {},
  card: {
    log_id: 'sdui_custom_components_demo',
    variables: [
      { name: 'progress', type: 'number', value: 68 },
      { name: 'week_total', type: 'number', value: 3420 },
      { name: 'week_target', type: 'number', value: 5000 },
      { name: 'rider', type: 'string', value: 'John Otieno' },
      { name: 'rider_city', type: 'string', value: 'Nairobi · Kilimani loop' },
    ],
    states: [{ state_id: 0, div: root() }],
  },
}

function root() {
  return {
    type: 'container',
    orientation: 'vertical',
    width: { type: 'match_parent' },
    height: { type: 'match_parent' },
    background: [{ type: 'solid', color: '#F5F3FF' }],
    paddings: { left: 20, right: 20, top: 28, bottom: 24 },
    content_alignment_horizontal: 'left',
    items: [
      header(),
      statRing(),
      noteFrame(),
      controls(),
      footnote(),
    ],
  }
}

function header() {
  return {
    type: 'container',
    orientation: 'vertical',
    width: { type: 'match_parent' },
    items: [
      {
        type: 'text',
        text: '🧩 CUSTOM COMPONENTS',
        font_size: 10,
        font_weight: 'bold',
        letter_spacing: 2,
        text_color: '#7C3AED',
      },
      {
        type: 'text',
        text: 'Runtime extension slot',
        font_size: 24,
        font_weight: 'bold',
        text_color: '#1E1B4B',
        margins: { top: 6 },
      },
      {
        type: 'text',
        text: 'These two panels are `div custom` blocks rendered by web components the playground registers at render() time — the same hook future cards can use.',
        font_size: 13,
        text_color: '#6D28D9',
        line_height: 20,
        margins: { top: 8 },
        width: { type: 'match_parent' },
      },
    ],
  }
}

// --- pattern 1: template snapshot ----------------------------------------
function statRing() {
  return {
    type: 'custom',
    custom_type: 'stat-ring',
    width: { type: 'match_parent' },
    margins: { top: 20 },
    custom_props: {
      // "@{...}" refs are resolved by the playground template helper against
      // the variables snapshot DivKit hands to the template function.
      value: '@{progress}',
      caption: 'Daily target',
      sub: 'M-Pesa + cash, before expenses',
    },
  }
}

// --- pattern 2: custom chrome + live DivKit children ----------------------
function noteFrame() {
  return {
    type: 'custom',
    custom_type: 'note-frame',
    width: { type: 'match_parent' },
    margins: { top: 16 },
    custom_props: {
      title: 'Live children via <slot>',
      badge: 'REACTIVE',
    },
    items: [
      {
        type: 'text',
        text: '@{rider}',
        font_size: 16,
        font_weight: 'bold',
        text_color: '#111827',
      },
      {
        type: 'text',
        text: '@{rider_city}',
        font_size: 12,
        text_color: '#6B7280',
        margins: { top: 2 },
      },
      {
        type: 'container',
        orientation: 'horizontal',
        width: { type: 'match_parent' },
        content_alignment_vertical: 'center',
        margins: { top: 10 },
        items: [
          {
            type: 'text',
            text: 'Week total',
            font_size: 12,
            text_color: '#6B7280',
            width: { type: 'weighted', value: 1 },
          },
          {
            type: 'text',
            text: 'KES @{\'\' + toString(toInteger(week_total))} / @{\'\' + toString(toInteger(week_target))}',
            font_size: 12,
            font_weight: 'bold',
            text_color: '#7C3AED',
          },
        ],
      },
      progressText(),
    ],
  }
}

function progressText() {
  return {
    type: 'container',
    orientation: 'vertical',
    width: { type: 'match_parent' },
    margins: { top: 10 },
    paddings: { left: 12, right: 12, top: 10, bottom: 10 },
    background: [{ type: 'solid', color: '#F5F3FF' }],
    border: { stroke: { color: '#DDD6FE', width: 1 }, corner_radius: 10 },
    items: [
      {
        type: 'text',
        text: 'Progress: @{\'\' + toString(toInteger(progress))}% — change it below, this text is a plain DivKit div living inside the custom element.',
        font_size: 12,
        text_color: '#5B21B6',
        line_height: 18,
        width: { type: 'match_parent' },
      },
    ],
  }
}

function controls() {
  return {
    type: 'container',
    orientation: 'horizontal',
    width: { type: 'match_parent' },
    margins: { top: 16 },
    items: [
      stepButton('− 10', 'dec', '#FFFFFF', '#4C1D95', '#DDD6FE', [
        {
          log_id: 'demo_dec',
          url: 'div-action://set_variable?name=progress&value=@{progress - 10}',
          enabled: '@{progress} >= 10',
        },
      ]),
      { type: 'container', width: { type: 'fixed', value: 10 }, height: { type: 'wrap_content' } },
      stepButton('+ 10', 'inc', '#7C3AED', '#FFFFFF', '#7C3AED', [
        {
          log_id: 'demo_inc',
          url: 'div-action://set_variable?name=progress&value=@{progress + 10}',
          enabled: '@{progress} <= 90',
        },
      ]),
    ],
  }
}

function stepButton(label, logId, bg, ink, line, actions) {
  return {
    type: 'container',
    orientation: 'horizontal',
    width: { type: 'weighted', value: 1 },
    height: { type: 'fixed', value: 44 },
    background: [{ type: 'solid', color: bg }],
    border: { stroke: { color: line, width: 1 }, corner_radius: 12 },
    content_alignment_horizontal: 'center',
    content_alignment_vertical: 'center',
    actions,
    items: [
      {
        type: 'text',
        text: label,
        font_size: 14,
        font_weight: 'bold',
        text_color: ink,
      },
    ],
  }
}

function footnote() {
  return {
    type: 'text',
    text: 'Registry: client/src/divkit/customComponents.ts → passed to render({ customComponents }). Add an element there and any card can reference it via custom_type.',
    font_size: 11,
    text_color: '#8B5CF6',
    line_height: 17,
    width: { type: 'match_parent' },
    margins: { top: 18 },
  }
}

export { card as customDemoCard }
