import { useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useToast } from '../context/ToastContext.jsx';
import Icon from '../components/Icon.jsx';

// Backend now serves user + admin credentials from the same /auth/login,
// so we don't ask the user up front which kind of account they have.
// Instead we peek at the JWT's `role` claim once the token comes back
// and route them to /admin or / accordingly.
function roleFromToken(token) {
  try {
    const part = token.split('.')[1];
    const json = atob(part.replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(decodeURIComponent(escape(json)))?.role || '';
  } catch {
    return '';
  }
}

export default function Login() {
  const { login, isAuth, user } = useAuth();
  const toast = useToast();
  const nav = useNavigate();

  const [u, setU] = useState('');
  const [p, setP] = useState('');
  const [code, setCode] = useState('');
  const [needTotp, setNeedTotp] = useState(false); // shown after 401 "TOTP required"
  const [busy, setBusy] = useState(false);

  if (isAuth) {
    const isAdmin = user?.role?.includes('ADMIN');
    return <Navigate to={isAdmin ? '/admin' : '/'} replace />;
  }

  const submit = async (e) => {
    e.preventDefault();
    if (!u || !p) return;
    setBusy(true);
    try {
      // Pass totpCode if user already entered it (resubmit after prompt);
      // otherwise null on first attempt.
      const token = await login(u, p, needTotp ? code : null);
      const isAdmin = roleFromToken(token).includes('ADMIN');
      toast.success(`Добро пожаловать, ${u}!`);
      nav(isAdmin ? '/admin' : '/');
    } catch (err) {
      const msg = err.message || '';
      if (err.status === 401 && /TOTP required/i.test(msg) && !needTotp) {
        setNeedTotp(true);
        toast.warn('Введите 6-значный код из приложения-аутентификатора');
      } else if (err.status === 401 && /TOTP invalid/i.test(msg)) {
        toast.error('Неверный код. Попробуйте ещё раз');
        setCode('');
      } else {
        toast.error(msg || 'Не удалось войти');
      }
    } finally { setBusy(false); }
  };

  return (
    <div className="auth-shell">
      <div className="auth-bg" />
      <div className="auth-card">
        <div className="auth-brand">
          <div className="brand-logo big"><Icon name="cloud" size={28} /></div>
          <div>
            <div className="auth-title">APS Cloud</div>
            <div className="auth-sub">Войти в аккаунт</div>
          </div>
        </div>

        <form className="auth-form" onSubmit={submit}>
          <label className="field">
            <span>Имя пользователя</span>
            <input value={u} onChange={(e) => setU(e.target.value)} autoFocus required />
          </label>

          <label className="field">
            <span>Пароль</span>
            <input type="password" value={p} onChange={(e) => setP(e.target.value)} required />
          </label>

          {needTotp && (
            <label className="field">
              <span>Код из аутентификатора</span>
              <input
                type="text"
                inputMode="numeric"
                pattern="[0-9]{6}"
                maxLength={6}
                placeholder="000000"
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                autoFocus
                required
              />
              <small style={{ color: 'var(--muted, #888)', marginTop: 4 }}>
                Откройте Google Authenticator (или аналог) и введите 6-значный код для APS_Server.
              </small>
            </label>
          )}

          <button className="btn btn-primary lg" disabled={busy || (needTotp && code.length !== 6)}>
            {busy ? 'Входим…' : (needTotp ? 'Подтвердить' : 'Войти')}
          </button>
        </form>

        <div className="auth-foot">
          Нет аккаунта? <Link to="/register">Зарегистрироваться</Link>
        </div>
      </div>
    </div>
  );
}
