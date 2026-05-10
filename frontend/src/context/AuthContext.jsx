import { createContext, useContext, useState, useCallback } from 'react';
import { api, getToken, setToken } from '../api/client.js';

const AuthCtx = createContext(null);

function decodeJwtPayload(token) {
  try {
    const part = token.split('.')[1];
    const json = atob(part.replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(decodeURIComponent(escape(json)));
  } catch {
    return null;
  }
}

// Derive user *synchronously* from the token. The previous version did
// this in a useEffect, which left `user === null` for one render after
// login — long enough for an admin to mount Dashboard and trigger
// getRootContent → getOrCreateRootFolder → FK violation against users.
function userFromToken(token) {
  if (!token) return null;
  const payload = decodeJwtPayload(token);
  if (!payload) return { username: 'user', role: 'ROLE_USER' };
  return {
    username: payload.sub || 'user',
    role: payload.role || (Array.isArray(payload.authorities) ? payload.authorities[0] : 'ROLE_USER'),
    exp: payload.exp,
  };
}

export function AuthProvider({ children }) {
  const [token, setTok] = useState(() => getToken());
  const user = userFromToken(token);

  // FR#17: 2FA-aware. totpCode is null for first attempt; after 401 "TOTP
  // required" the Login page re-calls with the code. `asAdmin` legacy flag
  // kept for callsites still passing it; backend serves both via /auth/login.
  const login = useCallback(async (u, p, asAdminOrTotp = false) => {
    // Back-compat: if 3rd arg is a string we treat it as totpCode; if
    // boolean we treat it as asAdmin flag (legacy, ignored — unified login).
    const totpCode = typeof asAdminOrTotp === 'string' ? asAdminOrTotp : null;
    const r = await api.login(u, p, totpCode);
    setToken(r.token);
    setTok(r.token);
    return r.token;
  }, []);

  const register = useCallback(async (u, p, asAdmin = false) => {
    const r = asAdmin ? await api.registerAdmin(u, p) : await api.register(u, p);
    setToken(r.token);
    setTok(r.token);
    return r.token;
  }, []);

  const logout = useCallback(() => {
    setToken(null);
    setTok(null);
  }, []);

  return (
    <AuthCtx.Provider value={{ token, user, login, register, logout, isAuth: !!token }}>
      {children}
    </AuthCtx.Provider>
  );
}

export const useAuth = () => useContext(AuthCtx);
