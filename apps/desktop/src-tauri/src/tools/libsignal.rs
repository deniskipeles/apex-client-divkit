//! Deterministic, dependency-free mirror of the observable `engine.tools.libsignal.LibSignalEngine`
//! contract (Android binds the official Signal Foundation library; iOS mirrors it in Swift).
//!
//! Reproduces the same *observable* surface: stable per-(workspace, peer) session identities, a
//! deterministic safety number, and a reversible encrypt/decrypt round trip. The desktop app
//! target wires the same protocol operations to its platform crypto.

#![allow(dead_code)]

use std::collections::{HashMap, HashSet};
use std::sync::Mutex;
use std::sync::OnceLock;

use super::support::{b64_decode, b64_encode, fnv1a};

pub struct SignalSession {
    pub session_id: String,
    pub peer_id: String,
    pub device_id: i64,
    pub fingerprint: String,
    pub is_verified: bool,
    pub send_counter: i64,
    pub recv_counter: i64,
}

pub struct EncryptedEnvelope {
    pub type_: i64,
    pub address: String,
    pub device_id: i64,
    pub ciphertext_b64: String,
}

struct State {
    identities: HashMap<String, (i64, String)>,
    sessions: HashSet<String>,
    verified: HashSet<String>,
    counters: HashMap<String, (i64, i64)>,
}

pub struct LibSignalEngine {
    state: Mutex<State>,
}

fn session_key(workspace: &str, peer: &str, device: i64) -> String {
    format!("{workspace}|{peer}|{device}")
}

/// 6-block displayable safety number derived from the identity pair.
pub fn fingerprint(workspace: &str, peer: &str) -> String {
    let digest = fnv1a(&format!("{workspace}|{peer}"));
    let mut padded = digest;
    while padded.len() < 12 {
        padded.push('0');
    }
    let chars: Vec<char> = padded.chars().collect();
    let mut blocks = Vec::new();
    let mut i = 0;
    while i < 12 {
        let block: String = chars[i..i + 2].iter().collect();
        blocks.push(block.to_uppercase());
        i += 2;
    }
    blocks.join("-")
}

impl LibSignalEngine {
    pub fn new() -> Self {
        LibSignalEngine {
            state: Mutex::new(State {
                identities: HashMap::new(),
                sessions: HashSet::new(),
                verified: HashSet::new(),
                counters: HashMap::new(),
            }),
        }
    }

    pub fn shared() -> &'static LibSignalEngine {
        static INSTANCE: OnceLock<LibSignalEngine> = OnceLock::new();
        INSTANCE.get_or_init(LibSignalEngine::new)
    }

    pub fn setup_identity(&self, workspace: &str) -> (i64, String) {
        let mut state = self.state.lock().unwrap();
        if let Some(existing) = state.identities.get(workspace) {
            return existing.clone();
        }
        let reg_id = 1000 + (rand_u32() % 15_381) as i64;
        let key = fnv1a(&format!("identity::{workspace}"));
        let identity = (reg_id, key);
        state.identities.insert(workspace.to_string(), identity.clone());
        identity
    }

    pub fn has_session(&self, workspace: &str, peer: &str, device: i64) -> bool {
        let state = self.state.lock().unwrap();
        state.sessions.contains(&session_key(workspace, peer, device))
    }

    pub fn create_or_get_session(&self, workspace: &str, peer: &str, device: i64) -> SignalSession {
        self.setup_identity(workspace);
        let key = session_key(workspace, peer, device);
        let counter = {
            let mut state = self.state.lock().unwrap();
            state.sessions.insert(key.clone());
            *state.counters.get(&key).unwrap_or(&(0, 0))
        };
        let digest = fnv1a(&format!("{workspace}:{peer}:{device}"));
        let session_id = format!("sig_sess_{}", &digest[..12.min(digest.len())]);
        SignalSession {
            session_id,
            peer_id: peer.to_string(),
            device_id: device,
            fingerprint: self.get_safety_number(workspace, peer),
            is_verified: self.is_session_verified(workspace, peer, device),
            send_counter: counter.0,
            recv_counter: counter.1,
        }
    }

    /// Reversible symmetric transform standing in for the Double Ratchet ciphertext.
    pub fn encrypt(&self, workspace: &str, peer: &str, device: i64, plaintext: &str) -> EncryptedEnvelope {
        self.setup_identity(workspace);
        let _ = self.create_or_get_session(workspace, peer, device);
        let key = session_key(workspace, peer, device);
        let counter = {
            let mut state = self.state.lock().unwrap();
            let recv = state.counters.get(&key).map(|c| c.1).unwrap_or(0);
            let send = state.counters.get(&key).map(|c| c.0).unwrap_or(0) + 1;
            state.counters.insert(key.clone(), (send, recv));
            send
        };
        let nonce = fnv1a(&format!("{key}#{counter}"));
        let body = b64_encode(plaintext.as_bytes());
        let payload = format!("{nonce}:{body}");
        EncryptedEnvelope {
            type_: 3,
            address: peer.to_string(),
            device_id: device,
            ciphertext_b64: b64_encode(payload.as_bytes()),
        }
    }

    pub fn decrypt(
        &self,
        workspace: &str,
        peer: &str,
        device: i64,
        ciphertext_b64: &str,
        _message_type: i64,
    ) -> Result<String, String> {
        self.setup_identity(workspace);
        let _ = self.create_or_get_session(workspace, peer, device);
        let key = session_key(workspace, peer, device);
        {
            let mut state = self.state.lock().unwrap();
            let send = state.counters.get(&key).map(|c| c.0).unwrap_or(0);
            let recv = state.counters.get(&key).map(|c| c.1).unwrap_or(0) + 1;
            state.counters.insert(key.clone(), (send, recv));
        }

        let outer = b64_decode(ciphertext_b64).ok_or("Malformed ciphertext")?;
        let inner = String::from_utf8(outer).map_err(|_| "Malformed ciphertext")?;
        let parts: Vec<&str> = inner.splitn(2, ':').collect();
        if parts.len() != 2 {
            return Err("Malformed ciphertext payload".into());
        }
        let decoded = b64_decode(parts[1]).ok_or("Malformed ciphertext payload")?;
        String::from_utf8(decoded).map_err(|_| "Malformed ciphertext payload".into())
    }

    pub fn get_safety_number(&self, workspace: &str, peer: &str) -> String {
        self.setup_identity(workspace);
        fingerprint(workspace, peer)
    }

    pub fn set_session_verified(&self, workspace: &str, peer: &str, verified: bool, device: i64) {
        let key = session_key(workspace, peer, device);
        let mut state = self.state.lock().unwrap();
        if verified {
            state.verified.insert(key);
        } else {
            state.verified.remove(&key);
            state.sessions.remove(&key);
        }
    }

    pub fn is_session_verified(&self, workspace: &str, peer: &str, device: i64) -> bool {
        let state = self.state.lock().unwrap();
        state.verified.contains(&session_key(workspace, peer, device))
    }

    pub fn reset_session(&self, workspace: &str, peer: &str, device: i64) {
        let key = session_key(workspace, peer, device);
        let mut state = self.state.lock().unwrap();
        state.sessions.remove(&key);
        state.verified.remove(&key);
        state.counters.remove(&key);
    }
}

fn rand_u32() -> u32 {
    use std::time::{SystemTime, UNIX_EPOCH};
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0);
    // Mix in an address to avoid repeating the same registration id across calls.
    let addr = &nanos as *const u32 as usize as u32;
    nanos.wrapping_mul(2_654_435_761).wrapping_add(addr)
}
