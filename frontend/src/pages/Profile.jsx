import { useEffect, useState } from 'react';
import { api } from '../api/client.js';
import { useAuth } from '../context/AuthContext.jsx';
import { useToast } from '../context/ToastContext.jsx';

/** FR#17 — TOTP 2FA enrolment + disable. */
export default function Profile() {
  const { user } = useAuth();
  const toast = useToast();

  // 'loading' until /api/user/me returns; then either 'enabled' (already 2FA-on)
  // or 'idle' (offer enrolment). Without this fetch the page would always
  // show enrol UI even when 2FA is active.
  const [step, setStep] = useState('loading');
  const [password, setPassword] = useState('');
  const [secret, setSecret] = useState('');
  const [otpauth, setOtpauth] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api.myInfo()
      .then((info) => { if (!cancelled) setStep(info.totpEnabled ? 'enabled' : 'idle'); })
      .catch(() => { if (!cancelled) setStep('idle'); });
    return () => { cancelled = true; };
  }, []);

  const startEnroll = async () => {
    if (!password) { toast.warn('Введите пароль'); return; }
    setBusy(true);
    try {
      const r = await api.enroll2fa(user.username, password);
      setSecret(r.secret);
      setOtpauth(r.otpauth);
      setStep('verifying');
      toast.info('Отсканируйте QR в Authenticator и введите код');
    } catch (e) {
      toast.error(e.message || 'Не удалось начать настройку 2FA');
    } finally { setBusy(false); }
  };

  const verifyEnroll = async () => {
    if (code.length !== 6) return;
    setBusy(true);
    try {
      await api.verifyEnroll2fa(user.username, code);
      setStep('enabled');
      toast.success('2FA включена. При следующем входе потребуется код.');
      setCode(''); setPassword('');
    } catch (e) {
      toast.error(e.message || 'Неверный код');
    } finally { setBusy(false); }
  };

  const disable = async () => {
    if (!password) { toast.warn('Введите пароль'); return; }
    setBusy(true);
    try {
      await api.disable2fa(user.username, password);
      setStep('idle'); setSecret(''); setOtpauth(''); setPassword('');
      toast.success('2FA отключена');
    } catch (e) {
      toast.error(e.message || 'Не удалось отключить 2FA');
    } finally { setBusy(false); }
  };

  const qrSrc = otpauth
    ? `https://api.qrserver.com/v1/create-qr-code/?size=220x220&data=${encodeURIComponent(otpauth)}`
    : null;

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-left">
          <h1 className="page-title">Профиль и безопасность</h1>
        </div>
      </header>

      <section className="panel">
        <h3>Учётная запись</h3>
        <p><strong>Имя:</strong> {user?.username}</p>
        <p><strong>Роль:</strong> {user?.role}</p>
      </section>

      <section className="panel">
        <h3>Двухфакторная аутентификация (TOTP)</h3>
        <p>
          Дополнительная защита: при входе нужно будет ввести 6-значный код из приложения
          (Google Authenticator, Authy, FreeOTP, 1Password и т.д.).
        </p>

        {step === 'loading' && (
          <div className="muted">Загрузка состояния 2FA…</div>
        )}

        {step === 'idle' && (
          <div>
            <p>Чтобы включить 2FA, подтвердите пароль и отсканируйте QR-код приложением-аутентификатором.</p>
            <div className="panel-row">
              <label className="field" style={{ flex: '1 1 240px' }}>
                <span>Пароль</span>
                <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
              </label>
              <button className="btn btn-primary" onClick={startEnroll} disabled={busy || !password}>
                {busy ? 'Подготовка…' : 'Включить 2FA'}
              </button>
              <button className="btn" onClick={() => setStep('disabling')}>
                Отключить 2FA
              </button>
            </div>
          </div>
        )}

        {step === 'verifying' && (
          <div>
            <ol style={{ paddingLeft: 20, lineHeight: 1.7, margin: '8px 0 16px' }}>
              <li>Откройте Authenticator-приложение и нажмите «Добавить аккаунт».</li>
              <li>Отсканируйте QR ниже <strong>либо</strong> введите секрет вручную:
                {' '}<code className="totp-secret">{secret}</code>
              </li>
              <li>Введите 6-значный код из приложения для подтверждения.</li>
            </ol>
            <div className="totp-row">
              {qrSrc && (
                <img className="totp-qr" src={qrSrc} alt="QR-код для Authenticator" width={220} height={220} />
              )}
              <div className="totp-fields">
                <label className="field">
                  <span>Код из приложения</span>
                  <input
                    type="text" inputMode="numeric" pattern="[0-9]{6}" maxLength={6}
                    placeholder="000000" autoFocus
                    value={code}
                    onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                  />
                </label>
                <div className="panel-row" style={{ gap: 8 }}>
                  <button className="btn btn-primary" onClick={verifyEnroll} disabled={busy || code.length !== 6}>
                    {busy ? 'Проверка…' : 'Подтвердить'}
                  </button>
                  <button className="btn" onClick={() => { setStep('idle'); setCode(''); setSecret(''); setOtpauth(''); }}>
                    Отмена
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

        {step === 'enabled' && (
          <div>
            <div className="callout-success" style={{ marginBottom: 12 }}>
              2FA активирована. При следующем входе вас попросят ввести код.
            </div>
            <button className="btn" onClick={() => setStep('disabling')}>Отключить</button>
          </div>
        )}

        {step === 'disabling' && (
          <div>
            <p>Введите пароль для подтверждения отключения 2FA:</p>
            <div className="panel-row">
              <label className="field" style={{ flex: '1 1 240px' }}>
                <span>Пароль</span>
                <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
              </label>
              <button className="btn btn-primary" onClick={disable} disabled={busy || !password}>
                {busy ? 'Отключение…' : 'Отключить 2FA'}
              </button>
              <button className="btn" onClick={() => { setStep('idle'); setPassword(''); }}>
                Отмена
              </button>
            </div>
          </div>
        )}
      </section>
    </div>
  );
}
