import { useMemo, type CSSProperties, type ReactNode } from 'react'
import {
  adaptBorderColorForTheme,
  adaptButtonBgForTheme,
  adaptButtonTextForTheme,
  adaptSurfaceColorForTheme,
  adaptTextColorForTheme,
  buildCardModel,
  runActions,
  type ActionHost,
  type BlockProps,
  type BoxStyle,
  type ChartSpec,
  type DivValue,
  type DivVars,
  type RenderAction,
  type RenderNode,
} from '@apex/pos-ui/android-runtime'
import { IconGlyph } from './IconGlyph'

// ---------------------------------------------------------------------------
// AndroidCard — renders the pure model from `@apex/pos-ui/android-runtime`.
//
// This is a Compose-semantics port, not a DivKit client: node types, defaults,
// expression resolution and action folding all come from the runtime module that
// mirrors `DivKitMultiplatform.kt`. Nothing here invents a fallback the device
// does not have — an unknown type is already dropped by the model.
// ---------------------------------------------------------------------------

export interface AndroidCardProps {
  card: unknown
  variables: DivVars
  /** Live variables, so a URL dispatch sees writes made while it was awaiting. */
  getVariables?: () => DivVars
  isDark?: boolean
  onAction: (url: string, payload: unknown) => Promise<void> | void
  onSetVariable: (name: string, value: DivValue) => void
  onEvent?: (text: string) => void
}

const WEIGHT: Record<string, number> = { light: 300, regular: 400, medium: 600, bold: 700 }

export function AndroidCard({ card, variables, getVariables, isDark = true, onAction, onSetVariable, onEvent }: AndroidCardProps) {
  const model = useMemo(() => buildCardModel(card, variables), [card, variables])

  const host: ActionHost = {
    getVariables: () => getVariables?.() ?? variables,
    setVariable: onSetVariable,
    dispatchUrl: async (url, payload) => {
      onEvent?.(`dispatch ${url}`)
      await onAction(url, payload)
    },
  }

  const fire = (actions: RenderAction[]) => {
    void runActions(actions, host)
  }

  if (!model) {
    return (
      <div className="flex h-full items-center justify-center p-6 text-center text-[11px] text-ink-faint">
        This card renders nothing on Android — every node is hidden or of a type the device drops.
      </div>
    )
  }

  return (
    <div className="h-full w-full overflow-y-auto px-3 py-3">
      <NodeView node={model} variables={variables} isDark={isDark} fire={fire} onSetVariable={onSetVariable} />
    </div>
  )
}

interface NodeProps {
  node: RenderNode
  variables: DivVars
  isDark: boolean
  surfaceHex?: string
  fire: (actions: RenderAction[]) => void
  onSetVariable: (name: string, value: DivValue) => void
}

function boxStyle(box: BoxStyle, isDark: boolean): CSSProperties {
  const style: CSSProperties = {}
  if (box.margin) style.margin = `${box.margin.top}px ${box.margin.right}px ${box.margin.bottom}px ${box.margin.left}px`
  if (box.padding) style.padding = `${box.padding.top}px ${box.padding.right}px ${box.padding.bottom}px ${box.padding.left}px`
  if (box.background) style.background = adaptSurfaceColorForTheme(box.background, isDark)
  if (box.border) {
    style.border = `${box.border.width}px solid ${adaptBorderColorForTheme(box.border.color, isDark)}`
    style.borderRadius = `${box.border.radius}px`
  } else if (box.radius) {
    style.borderRadius = `${box.radius}px`
  }
  if (box.weight) style.flexGrow = box.weight
  return style
}

function NodeView({ node, variables, isDark, surfaceHex, fire, onSetVariable }: NodeProps): ReactNode {
  if (node.kind === 'nothing') return null
  const nodeSurface = node.box.background ?? surfaceHex
  const style = boxStyle(node.box, isDark)

  switch (node.kind) {
    case 'container':
    case 'gallery':
    case 'grid': {
      const grid = node.kind === 'grid' ? { display: 'grid', gridTemplateColumns: `repeat(${node.columnCount}, minmax(0, 1fr))` } : {}
      const flow =
        node.kind === 'container'
          ? { display: 'flex', flexDirection: node.layout === 'horizontal' ? 'row' : 'column' }
          : node.kind === 'gallery'
            ? { display: 'flex', flexDirection: 'row', overflowX: 'auto' }
            : {}
      return (
        <div style={{ ...style, ...flow, ...grid, gap: `${node.spacing}px` } as CSSProperties}>
          {node.children.map((child) => (
            <NodeView
              key={child.key}
              node={child}
              variables={variables}
              isDark={isDark}
              surfaceHex={nodeSurface}
              fire={fire}
              onSetVariable={onSetVariable}
            />
          ))}
        </div>
      )
    }
    case 'text':
      return (
        <div
          onClick={node.actions.length > 0 ? () => fire(node.actions) : undefined}
          style={{
            ...style,
            color: adaptTextColorForTheme(node.color, isDark, nodeSurface),
            fontSize: `${node.fontSize}px`,
            fontWeight: WEIGHT[node.fontWeight],
            textAlign: node.align,
            fontFamily: node.monospace ? 'ui-monospace, SFMono-Regular, Menlo, monospace' : undefined,
            whiteSpace: 'pre-wrap',
            lineHeight: 1.35,
            overflow: 'hidden',
            cursor: node.actions.length > 0 ? 'pointer' : undefined,
          }}
        >
          {node.text}
        </div>
      )
    case 'button': {
      const label = adaptButtonTextForTheme(node.textColor, node.background, isDark)
      return (
        <button
          type="button"
          onClick={() => fire(node.actions)}
          style={{
            ...style,
            background: adaptButtonBgForTheme(node.background, isDark),
            color: label,
            borderRadius: node.radius,
            fontSize: `${node.fontSize}px`,
            fontWeight: 700,
            display: 'flex',
            alignItems: 'center',
            justifyContent: node.align === 'left' ? 'flex-start' : 'center',
            gap: 8,
            padding: style.padding ?? '12px 14px',
            cursor: 'pointer',
            width: '100%',
            textAlign: 'left',
          }}
        >
          {node.icon ? <IconGlyph reference={node.icon} size={18} color={label} /> : null}
          <span style={{ display: 'flex', flexDirection: 'column' }}>
            <span>{node.text}</span>
            {node.subtitle ? <span style={{ fontWeight: 400, fontSize: 11, opacity: 0.85 }}>{node.subtitle}</span> : null}
          </span>
        </button>
      )
    }
    case 'input':
      return (
        <label style={{ ...style, display: 'block' }}>
          <input
            value={node.value}
            placeholder={node.hint}
            onChange={(event) => onSetVariable(node.variable, event.target.value)}
            style={{
              width: '100%',
              borderRadius: 10,
              border: `1px solid ${adaptBorderColorForTheme('#334155', isDark)}`,
              background: adaptSurfaceColorForTheme('#152033', isDark),
              color: adaptTextColorForTheme('#F8FAFC', isDark),
              padding: '10px 12px',
              fontSize: 14,
            }}
          />
        </label>
      )
    case 'separator':
      return <div style={{ ...style, height: 1, background: adaptBorderColorForTheme('#1E293B', isDark) }} />
    case 'image':
      return (
        <div
          style={{
            ...style,
            display: 'flex',
            justifyContent: node.align === 'left' ? 'flex-start' : node.align === 'right' ? 'flex-end' : 'center',
          }}
        >
          {node.bitmap ? (
            <img
              src={node.source}
              alt={node.contentDescription ?? ''}
              style={{ width: node.size, height: node.size, objectFit: 'contain' }}
            />
          ) : (
            <IconGlyph
              reference={node.source}
              size={node.size}
              color={node.tint ? adaptTextColorForTheme(node.tint, isDark, nodeSurface) : undefined}
            />
          )}
        </div>
      )
    case 'json_list':
      return (
        <div style={{ ...style, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {node.rows.length === 0 ? (
            <div style={{ color: adaptTextColorForTheme('#64748B', isDark, nodeSurface), fontSize: 12 }}>{node.emptyText}</div>
          ) : (
            node.rows.map((row) => (
              <NodeView
                key={row.key}
                node={row}
                variables={variables}
                isDark={isDark}
                surfaceHex={nodeSurface}
                fire={fire}
                onSetVariable={onSetVariable}
              />
            ))
          )}
        </div>
      )
    case 'chart':
      return <ChartView spec={node.spec} isDark={isDark} style={style} surfaceHex={nodeSurface} />
    case 'block':
      return (
        <BlockView
          block={node.block}
          style={style}
          isDark={isDark}
          surfaceHex={nodeSurface}
          variables={variables}
          fire={fire}
          onSetVariable={onSetVariable}
        />
      )
    default:
      return null
  }
}

function BlockView({
  block,
  style,
  isDark,
  surfaceHex,
  variables,
  fire,
  onSetVariable,
}: {
  block: BlockProps
  style: CSSProperties
  isDark: boolean
  surfaceHex?: string
  variables: DivVars
  fire: (actions: RenderAction[]) => void
  onSetVariable: (name: string, value: DivValue) => void
}) {
  const caption: CSSProperties = { color: adaptTextColorForTheme('#94A3B8', isDark, surfaceHex), fontSize: 11 }
  const inner = (children: RenderNode[]) =>
    children.map((child) => (
      <NodeView
        key={child.key}
        node={child}
        variables={variables}
        isDark={isDark}
        surfaceHex={surfaceHex}
        fire={fire}
        onSetVariable={onSetVariable}
      />
    ))

  switch (block.type) {
    case 'sticky_footer':
      return (
        <div style={{ ...style, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {inner(block.body)}
          <div
            style={{
              borderTop: `1px solid ${adaptBorderColorForTheme('#1E293B', isDark)}`,
              paddingTop: 8,
              display: 'flex',
              flexDirection: 'column',
              gap: 6,
            }}
          >
            {inner(block.footer)}
          </div>
        </div>
      )
    case 'segmented':
    case 'bottom_tabs':
      return (
        <div style={{ ...style, display: 'flex', gap: 6 }}>
          {block.items.map((item) => {
            const active = item.value === block.activeValue
            return (
              <button
                key={item.value}
                type="button"
                onClick={() => fire(item.actions)}
                style={{
                  flex: 1,
                  padding: '8px 10px',
                  borderRadius: block.type === 'bottom_tabs' ? 12 : 999,
                  background: active
                    ? adaptButtonBgForTheme('#0EA5E9', isDark)
                    : adaptSurfaceColorForTheme('#152033', isDark),
                  color: active ? '#FFFFFF' : adaptTextColorForTheme('#94A3B8', isDark, surfaceHex),
                  fontSize: 12,
                  fontWeight: 600,
                }}
              >
                {item.label}
              </button>
            )
          })}
        </div>
      )
    case 'progress_ring': {
      const fraction = Math.max(0, Math.min(1, block.fraction))
      const circumference = 2 * Math.PI * 26
      return (
        <div style={{ ...style, display: 'flex', alignItems: 'center', gap: 12 }}>
          <svg width="64" height="64" viewBox="0 0 64 64">
            <circle cx="32" cy="32" r="26" fill="none" stroke={adaptBorderColorForTheme('#1E293B', isDark)} strokeWidth="6" />
            <circle
              cx="32"
              cy="32"
              r="26"
              fill="none"
              stroke={adaptTextColorForTheme('#38BDF8', isDark, surfaceHex)}
              strokeWidth="6"
              strokeDasharray={`${circumference * fraction} ${circumference}`}
              strokeLinecap="round"
              transform="rotate(-90 32 32)"
            />
          </svg>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span
              style={{
                ...caption,
                fontFamily: 'ui-monospace, monospace',
                fontSize: 18,
                color: adaptTextColorForTheme('#F8FAFC', isDark, surfaceHex),
              }}
            >
              {block.label}
            </span>
            <span style={caption}>{block.caption}</span>
          </div>
        </div>
      )
    }
    case 'text_field':
      return (
        <label style={{ ...style, display: 'flex', flexDirection: 'column', gap: 4 }}>
          <span style={caption}>{block.label}</span>
          <input
            value={String(variables[block.variable] ?? '')}
            placeholder={block.placeholder}
            onChange={(event) => onSetVariable(block.variable, event.target.value)}
            style={{
              borderRadius: 10,
              border: `1px solid ${adaptBorderColorForTheme('#334155', isDark)}`,
              background: adaptSurfaceColorForTheme('#152033', isDark),
              color: adaptTextColorForTheme('#F8FAFC', isDark, surfaceHex),
              padding: '10px 12px',
              fontSize: 14,
            }}
          />
        </label>
      )
    case 'scan_chips':
      return (
        <div style={{ ...style, display: 'flex', flexWrap: 'wrap', gap: 6, alignItems: 'center' }}>
          {block.chunks.length === 0 ? <span style={caption}>No chunks scanned</span> : null}
          {block.chunks.map((chunk, index) => (
            <span
              key={`${chunk}-${index}`}
              style={{
                padding: '4px 8px',
                borderRadius: 999,
                background: adaptSurfaceColorForTheme('#152033', isDark),
                color: adaptTextColorForTheme('#34D399', isDark, surfaceHex),
                fontFamily: 'ui-monospace, monospace',
                fontSize: 10,
                border: `1px solid ${adaptBorderColorForTheme('#1E293B', isDark)}`,
              }}
            >
              {chunk || '·'}
            </span>
          ))}
          <span style={caption}>
            {block.chunks.length} / {block.expected || '?'}
          </span>
        </div>
      )
    case 'qr_controls':
      return (
        <div style={{ ...style, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
          <button type="button" onClick={() => fire(block.prev)} style={controlButton(isDark)}>
            Prev
          </button>
          <span style={{ ...caption, fontFamily: 'ui-monospace, monospace', fontSize: 12 }}>
            {block.index} / {block.count}
          </span>
          <button type="button" onClick={() => fire(block.next)} style={controlButton(isDark)}>
            Next
          </button>
        </div>
      )
    case 'fab':
      return (
        <div style={{ ...style, display: 'flex', justifyContent: 'flex-end' }}>
          <button
            type="button"
            onClick={() => fire(block.actions)}
            style={{
              borderRadius: 999,
              padding: '12px 18px',
              background: adaptButtonBgForTheme('#0EA5E9', isDark),
              color: '#FFFFFF',
              fontWeight: 700,
              fontSize: 12,
            }}
          >
            {block.label || '＋'}
          </button>
        </div>
      )
    case 'toast':
      return (
        <div
          style={{
            ...style,
            background: adaptSurfaceColorForTheme('#1E293B', isDark),
            color: adaptTextColorForTheme('#E2E8F0', isDark, surfaceHex),
            borderRadius: 10,
            padding: '10px 12px',
            fontSize: 12,
          }}
        >
          {block.text}
        </div>
      )
    case 'inline_banner':
      return (
        <div
          style={{
            ...style,
            borderRadius: 10,
            padding: '10px 12px',
            fontSize: 12,
            background: adaptSurfaceColorForTheme(block.tone === 'error' ? '#3F1D24' : '#152033', isDark),
            color: adaptTextColorForTheme(block.tone === 'error' ? '#F87171' : '#38BDF8', isDark, surfaceHex),
            border: `1px solid ${adaptBorderColorForTheme('#1E293B', isDark)}`,
          }}
        >
          {block.text}
        </div>
      )
    case 'camera_viewfinder':
      return (
        <div
          style={{
            ...style,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            minHeight: 140,
            borderRadius: 14,
            border: `1px dashed ${adaptBorderColorForTheme('#334155', isDark)}`,
            background: adaptSurfaceColorForTheme('#0F172A', isDark),
            color: adaptTextColorForTheme('#94A3B8', isDark, surfaceHex),
            fontSize: 11,
            textAlign: 'center',
            padding: 12,
          }}
        >
          {block.hint || 'Camera preview needs the device camera'}
        </div>
      )
    case 'form_modal':
    case 'bottom_sheet':
      if (!block.visible) return null
      return (
        <div
          style={{
            ...style,
            display: 'flex',
            flexDirection: 'column',
            gap: 10,
            borderRadius: 16,
            padding: 14,
            background: adaptSurfaceColorForTheme('#152033', isDark),
            border: `1px solid ${adaptBorderColorForTheme('#334155', isDark)}`,
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontSize: 13, fontWeight: 700, color: adaptTextColorForTheme('#F8FAFC', isDark, surfaceHex) }}>
              {block.title}
            </span>
            <button type="button" onClick={() => fire(block.dismissActions)} style={controlButton(isDark)}>
              ✕
            </button>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>{inner(block.body)}</div>
          <div style={{ display: 'flex', gap: 8 }}>
            {block.secondaryLabel ? (
              <button type="button" onClick={() => fire(block.secondaryActions)} style={{ ...controlButton(isDark), flex: 1 }}>
                {block.secondaryLabel}
              </button>
            ) : null}
            {block.primaryLabel ? (
              <button
                type="button"
                onClick={() => fire(block.primaryActions)}
                style={{
                  flex: 1,
                  borderRadius: 10,
                  padding: '10px 12px',
                  background: adaptButtonBgForTheme('#0EA5E9', isDark),
                  color: '#FFFFFF',
                  fontSize: 12,
                  fontWeight: 700,
                }}
              >
                {block.primaryLabel}
              </button>
            ) : null}
          </div>
        </div>
      )
    default:
      return null
  }
}

function controlButton(isDark: boolean): CSSProperties {
  return {
    borderRadius: 10,
    padding: '8px 12px',
    fontSize: 11,
    fontWeight: 600,
    background: adaptSurfaceColorForTheme('#152033', isDark),
    color: adaptTextColorForTheme('#94A3B8', isDark),
    border: `1px solid ${adaptBorderColorForTheme('#334155', isDark)}`,
  }
}

function ChartView({
  spec,
  isDark,
  style,
  surfaceHex,
}: {
  spec: ChartSpec
  isDark: boolean
  style: CSSProperties
  surfaceHex?: string
}) {
  const width = 280
  const height = Math.max(120, spec.heightDp - 60)
  const max = Math.max(1, ...spec.points.map((point) => point.value))
  const axis = adaptBorderColorForTheme('#1E293B', isDark)
  const label = adaptTextColorForTheme('#94A3B8', isDark, surfaceHex)

  const body = (() => {
    if (spec.chartType === 'donut' || spec.chartType === 'pie') {
      const total = spec.points.reduce((sum, point) => sum + point.value, 0) || 1
      let angle = -Math.PI / 2
      const radius = Math.min(width, height) / 2 - 4
      const cx = width / 2
      const cy = height / 2
      const inner = spec.chartType === 'donut' ? radius * 0.55 : 0
      return (
        <svg width={width} height={height}>
          {spec.points.map((point) => {
            const slice = (point.value / total) * Math.PI * 2
            const x1 = cx + radius * Math.cos(angle)
            const y1 = cy + radius * Math.sin(angle)
            const start = angle
            angle += slice
            const x2 = cx + radius * Math.cos(angle)
            const y2 = cy + radius * Math.sin(angle)
            const large = slice > Math.PI ? 1 : 0
            const ix1 = cx + inner * Math.cos(start)
            const iy1 = cy + inner * Math.sin(start)
            const ix2 = cx + inner * Math.cos(angle)
            const iy2 = cy + inner * Math.sin(angle)
            const d =
              inner > 0
                ? `M ${x1} ${y1} A ${radius} ${radius} 0 ${large} 1 ${x2} ${y2} L ${ix2} ${iy2} A ${inner} ${inner} 0 ${large} 0 ${ix1} ${iy1} Z`
                : `M ${cx} ${cy} L ${x1} ${y1} A ${radius} ${radius} 0 ${large} 1 ${x2} ${y2} Z`
            return <path key={point.id} d={d} fill={point.colorHex} stroke={adaptSurfaceColorForTheme('#0B1120', isDark)} strokeWidth={1} />
          })}
        </svg>
      )
    }
    const step = width / Math.max(1, spec.points.length)
    const points = spec.points.map((point, index) => ({
      x: step * index + step / 2,
      y: height - 18 - (point.value / max) * (height - 30),
      point,
    }))
    const path = points.map((entry, index) => `${index === 0 ? 'M' : 'L'} ${entry.x} ${entry.y}`).join(' ')
    return (
      <svg width={width} height={height}>
        <line x1={0} y1={height - 16} x2={width} y2={height - 16} stroke={axis} strokeWidth={1} />
        {spec.chartType === 'bar'
          ? points.map((entry) => (
              <rect
                key={entry.point.id}
                x={entry.x - step * 0.3}
                y={entry.y}
                width={step * 0.6}
                height={Math.max(1, height - 16 - entry.y)}
                rx={3}
                fill={entry.point.colorHex}
              />
            ))
          : null}
        {spec.chartType === 'area' ? (
          <path
            d={`${path} L ${points.at(-1)?.x ?? 0} ${height - 16} L ${points[0]?.x ?? 0} ${height - 16} Z`}
            fill={spec.accentHex}
            opacity={0.18}
          />
        ) : null}
        {spec.chartType === 'line' || spec.chartType === 'area' ? (
          <path d={path} fill="none" stroke={spec.accentHex} strokeWidth={2} />
        ) : null}
      </svg>
    )
  })()

  return (
    <div
      style={{
        ...style,
        background: adaptSurfaceColorForTheme('#152033', isDark),
        borderRadius: 14,
        padding: 12,
        border: `1px solid ${axis}`,
      }}
    >
      <div style={{ fontSize: 12, fontWeight: 700, color: adaptTextColorForTheme('#F8FAFC', isDark, surfaceHex) }}>{spec.title}</div>
      <div style={{ fontSize: 10, color: label, marginBottom: 6 }}>{spec.subtitle}</div>
      {body}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
        {spec.points.map((point) => (
          <span key={point.id} style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 10, color: label }}>
            <span style={{ width: 8, height: 8, borderRadius: 2, background: point.colorHex }} />
            {point.label} {spec.valuePrefix}
            {point.value}
            {spec.valueSuffix}
          </span>
        ))}
      </div>
    </div>
  )
}
