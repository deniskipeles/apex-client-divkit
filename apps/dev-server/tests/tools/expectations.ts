// ---------------------------------------------------------------------------
// Expected outcomes for every conformance case, keyed `<slug>/<case>`.
//
// They live beside the cases rather than inside them so the table stays readable
// and so `build-tool-cases.mjs` can fail when a case has no expectation. Every
// entry asserts `success` and the exact `status`; the matchers below pin the
// payload values a user actually reads off the card.
//
// A status here is a contract, not a transcript: `INVALID`, `UNSUPPORTED` and
// `DENIED` are deliberate — those are the paths where the app must not invent a
// result.
// ---------------------------------------------------------------------------

import type { Expect } from '../types.ts'

export const EXPECTATIONS: Record<string, Expect> = {
  // --- scanner ---------------------------------------------------------
  'scan/simulated_payload_decodes': { success: true, status: 'SUCCESS', data: { raw_value: { equals: 'APEX-4006381333931' } } },
  'scan/simulate_returns_payload': { success: true, status: 'SUCCESS', data: { raw_value: { equals: 'APEX-SIM-1' } } },
  'scan/parse_extracts_symbology': { success: true, status: 'SUCCESS', data: { raw_value: { equals: 'APEX-7788' } } },
  'scan/no_input_refuses': { success: false, status: 'NO_INPUT', errorContains: 'Provide a camera frame' },
  'scan/ocr_without_host_refuses': { success: false, status: 'NO_INPUT', errorContains: 'No image source host' },
  'scan/format_mismatch_is_reported': { success: false, status: 'FORMAT_MISMATCH', errorContains: 'does not match' },
  'scan/continuous_scan_needs_host': { success: false, status: 'NO_INPUT' },

  // --- haptics / beeper ------------------------------------------------
  'haptics/click_plays': { success: true, status: 'SUCCESS' },
  'haptics/heavy_plays': { success: true, status: 'SUCCESS' },
  'haptics/vibrate_one_shot': { success: true, status: 'SUCCESS' },
  'haptics/stop_cancels': { success: true, status: 'CANCELLED' },
  'haptics/status_reports_ready': { success: true, status: 'READY' },
  'haptics/perform_pattern': { success: true, status: 'SUCCESS' },
  'haptics/tick_plays': { success: true, status: 'SUCCESS' },
  'haptics/success_plays': { success: true, status: 'SUCCESS' },
  'haptics/warning_plays': { success: true, status: 'SUCCESS' },
  'haptics/error_plays': { success: true, status: 'SUCCESS' },
  'haptics/repeat_is_stable': { success: true, status: 'SUCCESS' },
  'beeper/tone_plays': { success: true, status: 'SUCCESS' },
  'beeper/beep_alias': { success: true, status: 'SUCCESS' },
  'beeper/chime_sequence': { success: true, status: 'SUCCESS' },
  'beeper/play_alias': { success: true, status: 'SUCCESS' },
  'beeper/stop_stops': { success: true, status: 'MUTED' },
  'beeper/status_reports': { success: true, status: 'READY' },

  // --- camera ----------------------------------------------------------
  'camera/capture_without_host_fails_closed': { success: false, status: 'UNSUPPORTED', errorContains: 'No camera host' },
  'camera/torch_without_host_fails_closed': { success: false, status: 'UNSUPPORTED' },
  'camera/status_reports': { success: true, status: 'READY' },

  // --- files -----------------------------------------------------------
  'files/write_read_round_trip': {
    success: true,
    status: 'SUCCESS',
    data: { path: { equals: 'notes/hello.txt' }, content: { equals: 'apex conformance' } },
  },
  'files/append_extends': { success: true, status: 'SUCCESS', data: { content: { equals: 'one-two' } } },
  'files/copy_then_move': { success: true, status: 'SUCCESS', data: { path: { equals: 'notes/moved.txt' } } },
  'files/list_and_info': { success: true, status: 'SUCCESS', data: { path: { equals: 'notes' } } },
  'files/delete_removes': { success: true, status: 'SUCCESS' },
  'files/uri_resolves': { success: true, status: 'SUCCESS', data: { uri: { startsWith: 'file://' } } },
  'files/read_missing_fails': { success: false, status: 'FAILED', errorContains: 'No such file' },

  // --- image manipulation ---------------------------------------------
  'image-manipulation/process_without_image_fails': { success: false, status: 'NO_INPUT' },
  'image-manipulation/crop_without_image_fails': { success: false, status: 'NO_INPUT' },
  'image-manipulation/resize_without_image_fails': { success: false, status: 'NO_INPUT' },
  'image-manipulation/threshold_without_image_fails': { success: false, status: 'NO_INPUT' },
  'image-manipulation/adjust_color_without_image_fails': { success: false, status: 'NO_INPUT' },
  'image-manipulation/smooth_without_image_fails': { success: false, status: 'NO_INPUT' },
  'image-manipulation/remove_background_without_image_fails': { success: false, status: 'NO_INPUT' },

  // --- embedding -------------------------------------------------------
  'embedding/text_embeds': { success: true, status: 'SUCCESS', data: { dim: { equals: '512' } }, outputs: { 'user.vector_dim': { nonEmpty: true } } },
  'embedding/text_similarity_scores': { success: true, status: 'SUCCESS', data: { score: { range: { min: 0.99, max: 1.0 } } } },
  'embedding/image_without_host_fails': { success: false, status: 'FAILED', errorContains: 'decodable' },
  'embedding/image_text_without_host_fails': { success: false, status: 'FAILED' },
  'embedding/similarity_alias': { success: false, status: 'FAILED', errorContains: 'packed vectors' },

  // --- clipboard -------------------------------------------------------
  'clipboard/copy_then_paste': { success: true, status: 'SUCCESS', data: { text: { equals: 'apex clipboard' } } },
  'clipboard/clear_empties': { success: true, status: 'EMPTY', data: { text: { equals: '' } } },

  // --- biometrics ------------------------------------------------------
  'biometrics/denied_without_host': { success: false, status: 'UNSUPPORTED', errorContains: 'No biometric' },
  'biometrics/refusing_sink_denies': { success: false, status: 'FAILED', errorContains: 'cancelled or denied' },
  'biometrics/accepting_sink_succeeds': { success: true, status: 'AUTHORIZED', data: { authenticated: { equals: true } } },

  // --- libsignal -------------------------------------------------------
  'libsignal/full_flow': { success: true, status: 'VERIFIED', data: { verified: { equals: true } } },
  'libsignal/identity_keys_setup': { success: true, status: 'IDENTITY_READY', data: { identity_key: { nonEmpty: true } } },
  'libsignal/status_reports_session': { success: true, status: 'ESTABLISHED', data: { session_id: { startsWith: 'sig_sess_' } } },
  'libsignal/reset_clears_session': { success: true, status: 'NO_SESSION', data: { session_id: { equals: '' } } },
  'libsignal/ratchet_advances': { success: true, status: 'ENCRYPTED', data: { ciphertext: { nonEmpty: true } } },
  'libsignal/decrypt_without_session_fails': { success: false, status: 'FAILED', errorContains: 'authentication' },
  'libsignal/verify_wrong_number_mismatches': { success: false, status: 'MISMATCH', data: { verified: { equals: false } } },

  // --- ml --------------------------------------------------------------
  'mobilenetv4/classify_without_image_fails_closed': { success: false, status: 'NO_INPUT' },
  'mobilenetv4/detect_alias_fails_closed': { success: false, status: 'NO_INPUT' },
  'mobilenetv4/predict_alias_fails_closed': { success: false, status: 'NO_INPUT' },
  'u2net/segment_reports_unsupported': { success: false, status: 'NO_INPUT' },
  'u2net/infer_reports_unsupported': { success: false, status: 'NO_INPUT' },
  'u2net/mask_reports_unsupported': { success: false, status: 'NO_INPUT' },

  // --- push ------------------------------------------------------------
  'push/send_alert_posts': { success: true, status: 'LOGGED_TO_LEDGER', data: { notification_id: { startsWith: 'push_' } } },
  'push/notify_posts': { success: true, status: 'LOGGED_TO_LEDGER' },
  'push/send_posts': { success: true, status: 'LOGGED_TO_LEDGER' },

  // --- offline cache ---------------------------------------------------
  'offline-cache/cache_then_stats': { success: true, status: 'CACHED_OFFLINE_READY', data: { cached_pages: { range: { min: 1 } } } },
  'offline-cache/clear_empties_cache': { success: true, status: 'CACHED_OFFLINE_READY', data: { cached_pages: { equals: '0' } } },

  // --- realtime --------------------------------------------------------
  'realtime-ws/connect_without_transport_fails': { success: false, status: 'UNSUPPORTED', errorContains: 'no websocket transport' },
  'realtime-ws/connect_with_transport_succeeds': { success: true, status: 'CONNECTED' },
  'realtime-ws/subscribe_without_endpoint_fails': { success: false, status: 'FAILED', errorContains: 'not connected' },
  'realtime-ws/send_without_endpoint_fails': { success: false, status: 'FAILED', errorContains: 'not connected' },
  'realtime-ws/disconnect_is_safe': { success: true, status: 'DISCONNECTED' },

  // --- net -------------------------------------------------------------
  'net/hub_lifecycle': { success: true, status: 'STOPPED', data: { port: { equals: '0' } } },
  'net/status_without_hub': { success: true, status: 'IDLE' },
  'net/share_without_content_fails': { success: false, status: 'FAILED', errorContains: 'missing content' },
  'net/share_without_hub_is_staged': { success: true, status: 'STAGED', data: { hub_running: { equals: false } } },
  'net/get_unreachable_fails': { success: false, status: 'FAILED', errorContains: 'Connection refused' },
  'net/post_unreachable_fails': { success: false, status: 'FAILED' },
  'net/download_unreachable_fails': { success: false, status: 'FAILED' },
  'net/upload_unreachable_fails': { success: false, status: 'FAILED' },
  'net/request_unreachable_fails': { success: false, status: 'FAILED' },
  'net/stop_twice_is_safe': { success: true, status: 'NOT_RUNNING' },

  // --- printer ---------------------------------------------------------
  'print/scan_reports_adapter_state': { success: false, status: 'DENIED' },
  'print/status_reports_ready': { success: true, status: 'READY' },
  'print/connect_without_device_fails': { success: false, status: 'DENIED' },
  'print/print_without_receipt_fails': { success: false, status: 'FAILED', errorContains: 'missing receipt' },
  'print/print_without_printer_fails': { success: false, status: 'UNSUPPORTED', errorContains: 'printer' },
  'print/pair_without_device_fails': { success: false, status: 'DENIED' },
  'print/disconnect_is_safe': { success: false, status: 'FAILED', errorContains: 'missing address' },

  // --- codec -----------------------------------------------------------
  'codec/base64_round_trip': { success: true, status: 'SUCCESS', data: { text: { equals: 'YXBleCBwb3M=' } } },
  'codec/base45_round_trip': { success: true, status: 'SUCCESS', data: { text: { nonEmpty: true } } },
  'codec/json_validate_ok': { success: true, status: 'SUCCESS' },
  'codec/json_validate_rejects': { success: false, status: 'INVALID', data: { valid: { equals: false } } },
  'codec/json_extract_paths': { success: true, status: 'SUCCESS', data: { count: { equals: '1' } } },
  'codec/json_stringify': { success: true, status: 'SUCCESS', data: { json: { contains: '"order":1042' } } },
  'codec/chunk_assemble_round_trip': {
    success: true,
    status: 'COMPLETE',
    data: { checksum_ok: { equals: true }, json: { contains: '"order":1042' } },
  },
  'codec/out_of_order_chunks_assemble': { success: true, status: 'COMPLETE', data: { json: { equals: 'abcdef' }, checksum_ok: { equals: true } } },
  'codec/missing_chunk_reports_partial': { success: true, status: 'PARTIAL', data: { missing: { nonEmpty: true } } },
  'codec/duplicate_chunk_does_not_complete': { success: true, status: 'PARTIAL', data: { missing: { nonEmpty: true } } },
  'codec/corrupted_chunk_fails_checksum': { success: false, status: 'FAILED', data: { checksum_ok: { equals: false } }, errorContains: 'Checksum' },

  // --- code generation -------------------------------------------------
  'code-generate/qr_renders': { success: true, status: 'SUCCESS', data: { format: { equals: 'QR_CODE' }, uri: { startsWith: 'file://' } } },
  'code-generate/ean13_renders': { success: true, status: 'SUCCESS', data: { format: { equals: 'EAN_13' }, uri: { startsWith: 'file://' } } },
  'code-generate/code128_renders': { success: true, status: 'SUCCESS', data: { format: { equals: 'CODE_128' } } },
  'code-generate/qr_alias_renders': { success: true, status: 'SUCCESS', data: { content: { equals: 'APEX-QR-77' } } },
  'code-generate/render_alias': { success: true, status: 'SUCCESS' },
  'code-generate/missing_content_fails': { success: false, status: 'FAILED', errorContains: 'content' },

  // --- screen ----------------------------------------------------------
  'screen/keep_awake_on': { success: true, status: 'ENABLED', data: { enabled: { equals: true } } },
  'screen/keep_awake_off': { success: true, status: 'DISABLED', data: { enabled: { equals: false } } },
  'screen/screenshot_without_host_fails': { success: false, status: 'UNSUPPORTED', errorContains: 'host' },
  'screen/recording_without_host_fails': { success: false, status: 'UNSUPPORTED', errorContains: 'host' },
  'screen/status_reports_state': { success: true, status: 'READY' },
}

/** Step-level expectations where the interesting value is not the last step. */
export const STEP_EXPECTATIONS: Record<string, Record<number, Expect>> = {
  // The plaintext must come back byte-for-byte, quotes and punctuation included.
  'libsignal/full_flow': {
    1: { success: true, status: 'ENCRYPTED', data: { ciphertext: { nonEmpty: true } } },
    2: { success: true, status: 'DECRYPTED', data: { plaintext: { equals: 'Apex order #1042 - 2 items' } } },
    3: { success: true, status: 'VERIFIED' },
  },
  'libsignal/reset_clears_session': {
    1: { success: true, status: 'SESSION_RESET', data: { session_id: { equals: '' } } },
  },
  'net/hub_lifecycle': {
    0: { success: true, status: 'SERVING', data: { port: { equals: '8089' } } },
    2: { success: true, status: 'SHARED', data: { id: { nonEmpty: true } } },
    3: { success: true, status: 'OK', data: { count: { range: { min: 1 } } } },
    4: { success: true, status: 'REMOVED' },
  },
  'codec/chunk_assemble_round_trip': {
    0: { success: true, status: 'SUCCESS', data: { count: { range: { min: 2 } }, checksum: { nonEmpty: true } } },
  },
  'files/append_extends': {
    1: { success: true, status: 'SUCCESS' },
  },
}
