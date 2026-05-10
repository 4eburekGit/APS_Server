import { useCallback, useEffect, useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { api } from '../api/client.js';
import { useAuth } from '../context/AuthContext.jsx';
import { useToast } from '../context/ToastContext.jsx';
import Icon from '../components/Icon.jsx';

const fmtSize = (b) => {
  if (b == null) return '—';
  const u = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
  let i = 0; let n = Number(b);
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n < 10 ? n.toFixed(1) : Math.round(n)} ${u[i]}`;
};

export default function Admin() {
  const { user } = useAuth();
  const toast = useToast();
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [confirm, setConfirm] = useState(null); // user pending delete

  const isAdmin = user?.role?.includes('ADMIN');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const list = await api.listUsers();
      setUsers(Array.isArray(list) ? list : []);
    } catch (e) {
      toast.error(e.message || 'Не удалось загрузить пользователей');
    } finally { setLoading(false); }
  }, [toast]);

  useEffect(() => { if (isAdmin) load(); }, [isAdmin, load]);

  const filtered = useMemo(
    () => users.filter((u) => u.username?.toLowerCase().includes(search.toLowerCase())),
    [users, search]
  );

  const totals = useMemo(() => {
    const used  = users.reduce((s, u) => s + (u.usedBytes || 0), 0);
    const files = users.reduce((s, u) => s + (u.fileCount || 0), 0);
    return { used, files, count: users.length };
  }, [users]);

  if (!isAdmin) return <Navigate to="/" replace />;

  const doDelete = async (u) => {
    try {
      await api.deleteUser(u.id);
      toast.success(`Пользователь «${u.username}» удалён`);
      setConfirm(null);
      load();
    } catch (e) {
      toast.error(e.message || 'Не удалось удалить пользователя');
    }
  };

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-left">
          <div className="crumbs"><span className="crumb"><span className="current">Администрирование</span></span></div>
          <h1 className="page-title">Пользователи</h1>
        </div>
        <div className="page-head-right">
          <div className="search">
            <Icon name="search" size={16} />
            <input placeholder="Поиск по имени…" value={search} onChange={(e) => setSearch(e.target.value)} />
          </div>
          <button className="icon-btn" title="Обновить" onClick={load}><Icon name="refresh" /></button>
        </div>
      </header>

      {/* Stat cards */}
      <div className="admin-stats">
        <StatCard icon="home"  label="Пользователей" value={totals.count} />
        <StatCard icon="file"  label="Файлов всего"  value={totals.files} />
        <StatCard icon="cloud" label="Занято места"  value={fmtSize(totals.used)} />
      </div>

      {loading ? (
        <div className="empty"><div className="empty-art"><Icon name="refresh" size={36} /></div><p>Загрузка…</p></div>
      ) : filtered.length === 0 ? (
        <div className="empty">
          <div className="empty-art"><Icon name="home" size={56} /></div>
          <h3>Пользователей нет</h3>
          <p>{users.length === 0 ? 'Список пуст.' : 'Под фильтр ничего не подошло.'}</p>
        </div>
      ) : (
        <table className="ftable">
          <thead>
            <tr>
              <th>Пользователь</th>
              <th>Роль</th>
              <th>Файлов</th>
              <th style={{ width: 280 }}>Использовано</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((u) => {
              const pct = u.quotaBytes ? Math.min(100, (u.usedBytes / u.quotaBytes) * 100) : 0;
              const danger = pct >= 90;
              const warn   = pct >= 70 && !danger;
              return (
                <tr key={u.id}>
                  <td>
                    <div className="row-name">
                      <span className="avatar small">{u.username?.slice(0,2).toUpperCase()}</span>
                      <div>
                        <div style={{ fontWeight: 600 }}>{u.username}</div>
                        <div className="muted small" style={{ fontFamily: 'monospace' }}>{u.id}</div>
                      </div>
                    </div>
                  </td>
                  <td>
                    <span className={`role-pill ${u.role === 'ADMIN' ? 'role-admin' : 'role-user'}`}>
                      {u.role || 'USER'}
                    </span>
                  </td>
                  <td>{u.fileCount ?? 0}</td>
                  <td>
                    <div className="quota">
                      <div className="quota-bar">
                        <div
                          className={`quota-fill ${danger ? 'danger' : warn ? 'warn' : ''}`}
                          style={{ width: `${pct}%` }}
                        />
                      </div>
                      <div className="quota-meta">
                        {fmtSize(u.usedBytes)} / {fmtSize(u.quotaBytes)} <span className="muted">({pct.toFixed(0)}%)</span>
                      </div>
                    </div>
                  </td>
                  <td>
                    <button
                      className="icon-btn danger-btn"
                      title="Удалить пользователя"
                      onClick={() => setConfirm(u)}
                      disabled={u.id === user?.id}
                    >
                      <Icon name="trash" />
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {/* Confirm modal */}
      {confirm && (
        <div className="modal-backdrop" onClick={() => setConfirm(null)}>
          <div className="modal" style={{ width: 460 }} onClick={(e) => e.stopPropagation()}>
            <div className="modal-head">
              <h3>Удалить пользователя?</h3>
              <button className="icon-btn" onClick={() => setConfirm(null)}><Icon name="close" /></button>
            </div>
            <div className="modal-body">
              <p>
                Вы уверены, что хотите удалить пользователя <strong>«{confirm.username}»</strong>?
              </p>
              <p className="muted small" style={{ marginTop: 8 }}>
                ⚠ Деструктивная операция. Будут удалены все файлы, папки и метаданные пользователя.
                Каталог <code>./uploads/{confirm.id}/</code> будет удалён с диска. Это нельзя отменить.
              </p>
            </div>
            <div className="modal-foot">
              <button className="btn" onClick={() => setConfirm(null)}>Отмена</button>
              <button className="btn btn-danger" onClick={() => doDelete(confirm)}>
                <Icon name="trash" /> Удалить навсегда
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function StatCard({ icon, label, value }) {
  return (
    <div className="stat-card">
      <div className="stat-icon"><Icon name={icon} size={20} /></div>
      <div>
        <div className="stat-value">{value}</div>
        <div className="stat-label">{label}</div>
      </div>
    </div>
  );
}
