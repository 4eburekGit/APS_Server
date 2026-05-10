import { useState } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import { useToast } from '../context/ToastContext.jsx';
import Icon from '../components/Icon.jsx';

export default function Register() {
  const { register, isAuth } = useAuth();
  const toast = useToast();
  const nav = useNavigate();

  const [u, setU] = useState('');
  const [p, setP] = useState('');
  const [p2, setP2] = useState('');
  const [busy, setBusy] = useState(false);

  if (isAuth) return <Navigate to="/" replace />;

  const submit = async (e) => {
    e.preventDefault();
    if (!u || !p) return;
    if (p !== p2) { toast.error('Пароли не совпадают'); return; }
    setBusy(true);
    try {
      // Self-service registration is for regular users only — admin
      // accounts are provisioned out-of-band (see AuthService.registerAdmin
      // / direct DB insert), not through the public sign-up form.
      await register(u, p, false);
      toast.success('Аккаунт создан');
      nav('/');
    } catch (err) {
      toast.error(err.message || 'Не удалось зарегистрироваться');
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
            <div className="auth-sub">Создание аккаунта</div>
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

          <label className="field">
            <span>Повторите пароль</span>
            <input type="password" value={p2} onChange={(e) => setP2(e.target.value)} required />
          </label>

          <button className="btn btn-primary lg" disabled={busy}>
            {busy ? 'Создаём…' : 'Зарегистрироваться'}
          </button>
        </form>

        <div className="auth-foot">
          Уже есть аккаунт? <Link to="/login">Войти</Link>
        </div>
      </div>
    </div>
  );
}
