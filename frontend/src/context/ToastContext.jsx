import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';

const ToastCtx = createContext(null);

let nextId = 1;

// Toasts within this window with the same (kind, msg) are deduplicated:
// the existing toast's lifetime is extended and a small counter shown.
const DEDUP_WINDOW_MS = 2500;

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  // Active dismissal timers, keyed by toast id, so we can cancel & restart on dedup hits.
  const timers = useRef(new Map());

  const dismiss = useCallback((id) => {
    const t = timers.current.get(id);
    if (t) { clearTimeout(t); timers.current.delete(id); }
    setToasts((list) => list.filter((x) => x.id !== id));
  }, []);

  const scheduleDismiss = useCallback((id, ttl) => {
    const prev = timers.current.get(id);
    if (prev) clearTimeout(prev);
    timers.current.set(id, setTimeout(() => dismiss(id), ttl));
  }, [dismiss]);

  const push = useCallback((msg, kind = 'info', ttl = 4000) => {
    setToasts((list) => {
      // Dedup against the most recent toast (same kind+msg, still within window).
      const last = list[list.length - 1];
      if (last && last.kind === kind && last.msg === msg && (Date.now() - last.bornAt) < DEDUP_WINDOW_MS) {
        // Bump count + restart its TTL.
        scheduleDismiss(last.id, ttl);
        return list.map((x) => x.id === last.id ? { ...x, count: (x.count || 1) + 1, bornAt: Date.now() } : x);
      }
      const id = nextId++;
      scheduleDismiss(id, ttl);
      return [...list, { id, msg, kind, bornAt: Date.now(), count: 1 }];
    });
  }, [scheduleDismiss]);

  // Stable reference — does NOT change between renders, so consumers using `toast`
  // in their own deps don't get re-created on every render (which previously
  // produced a feedback loop on network failures).
  const toast = useMemo(() => ({
    info:    (m) => push(m, 'info'),
    success: (m) => push(m, 'success'),
    error:   (m) => push(m, 'error', 6000),
    warn:    (m) => push(m, 'warn'),
  }), [push]);

  return (
    <ToastCtx.Provider value={toast}>
      {children}
      <div className="toast-stack">
        {toasts.map((t) => (
          <div key={t.id} className={`toast toast-${t.kind}`} onClick={() => dismiss(t.id)}>
            <span className="toast-icon">
              {t.kind === 'success' ? '✓' : t.kind === 'error' ? '✕' : t.kind === 'warn' ? '!' : 'i'}
            </span>
            <span className="toast-msg">{t.msg}</span>
            {t.count > 1 && <span className="toast-count">×{t.count}</span>}
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

export const useToast = () => useContext(ToastCtx);
