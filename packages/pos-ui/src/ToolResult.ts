// Mirrors apps/android ToolResult.kt and apps/ios ToolProtocols.swift.
export type ToolResult =
  | { kind: 'success'; data: Record<string, unknown> }
  | { kind: 'unsupported'; tool: string; reason: string }
  | { kind: 'permissionDenied'; tool: string; permission: string }
  | { kind: 'failed'; tool: string; message: string }
