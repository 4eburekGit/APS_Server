import { useEffect, useState } from 'react';
import { api } from '../api/client.js';
import { useToast } from '../context/ToastContext.jsx';

/** Preset palette — same hues used across the app (donut, bars, accent). */
const PALETTE = [
  '#6366f1', '#8b5cf6', '#3b82f6', '#0ea5e9', '#14b8a6',
  '#10b981', '#f59e0b', '#ef4444', '#ec4899', '#a855f7',
];

/** FR#20 + NFT#9 — tag CRUD page. */
export default function Tags() {
  const toast = useToast();
  const [tags, setTags] = useState([]);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState('');
  const [color, setColor] = useState(PALETTE[0]);
  const [busy, setBusy] = useState(false);
  const [openTag, setOpenTag] = useState(null);
  const [files, setFiles] = useState([]);
  const [filesLoading, setFilesLoading] = useState(false);

  const reload = () => {
    setLoading(true);
    api.listTags()
      .then(setTags)
      .catch((e) => toast.error(e.message || 'Не удалось загрузить теги'))
      .finally(() => setLoading(false));
  };
  useEffect(reload, []);

  const create = async () => {
    if (!name.trim()) return;
    setBusy(true);
    try {
      await api.createTag(name.trim(), color || null);
      setName(''); setColor(PALETTE[0]);
      toast.success('Тег создан');
      reload();
    } catch (e) {
      toast.error(e.message || 'Не удалось создать тег');
    } finally { setBusy(false); }
  };

  const del = async (t) => {
    if (!confirm(`Удалить тег «${t.name}»? Связи с файлами будут потеряны.`)) return;
    try {
      await api.deleteTag(t.id);
      toast.success('Тег удалён');
      reload();
    } catch (e) { toast.error(e.message || 'Не удалось удалить'); }
  };

  const inspectFiles = async (t) => {
    setOpenTag(t);
    setFilesLoading(true);
    try {
      const list = await api.filesForTag(t.id);
      setFiles(list || []);
    } catch (e) {
      toast.error(e.message || 'Не удалось загрузить список файлов');
    } finally { setFilesLoading(false); }
  };

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-left">
          <h1 className="page-title">Теги</h1>
        </div>
      </header>

      <section className="panel">
        <h3>Создать тег</h3>
        <div className="panel-row">
          <label className="field" style={{ flex: '1 1 220px' }}>
            <span>Название (≤64)</span>
            <input value={name} onChange={(e) => setName(e.target.value.slice(0, 64))}
                   onKeyDown={(e) => e.key === 'Enter' && create()}
                   placeholder="например: работа" />
          </label>
          <label className="field">
            <span>Цвет</span>
            <div className="swatch-grid">
              {PALETTE.map((c) => (
                <button
                  key={c}
                  type="button"
                  className={`swatch-btn ${color === c ? 'selected' : ''}`}
                  style={{ background: c }}
                  onClick={() => setColor(c)}
                  title={c}
                  aria-label={`Цвет ${c}`}
                />
              ))}
            </div>
          </label>
          <button className="btn btn-primary" disabled={busy || !name.trim()} onClick={create}>
            {busy ? 'Создание…' : 'Создать'}
          </button>
        </div>
      </section>

      <section className="panel">
        <h3>Мои теги <span className="muted small">({tags.length})</span></h3>
        {loading
          ? <div className="muted">Загрузка…</div>
          : tags.length === 0
            ? <div className="muted">Тегов пока нет.</div>
            : (
              <div className="tag-grid">
                {tags.map((t) => (
                  <div
                    key={t.id}
                    className="tag-chip active"
                    style={{ background: t.color || 'var(--accent-soft)' }}
                    onClick={() => inspectFiles(t)}
                    title="Показать файлы с этим тегом"
                  >
                    <span>{t.name}</span>
                    <button
                      className="chip-x"
                      onClick={(e) => { e.stopPropagation(); del(t); }}
                      title="Удалить тег"
                    >×</button>
                  </div>
                ))}
              </div>
            )
        }
      </section>

      {openTag && (
        <section className="panel">
          <div className="metric-row">
            <h3 style={{ margin: 0 }}>Файлы с тегом «{openTag.name}»</h3>
            <button className="btn" onClick={() => { setOpenTag(null); setFiles([]); }}>Закрыть</button>
          </div>
          {filesLoading
            ? <div className="muted">Загрузка…</div>
            : files.length === 0
              ? <div className="muted">Нет файлов с этим тегом.</div>
              : (
                <ul className="simple-list">
                  {files.map((f) => (
                    <li key={f.id}>
                      <strong>{f.filename}</strong>
                      <span className="muted small"> · {f.contentType || 'неизвестно'}</span>
                    </li>
                  ))}
                </ul>
              )
          }
        </section>
      )}
    </div>
  );
}
