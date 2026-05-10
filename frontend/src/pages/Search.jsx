import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client.js';
import { useToast } from '../context/ToastContext.jsx';
import Icon from '../components/Icon.jsx';
import FileIcon from '../components/FileIcon.jsx';
import PreviewModal from '../components/PreviewModal.jsx';

const fmtSize = (b) => {
  if (b == null) return '—';
  const u = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
  let i = 0; let n = Number(b);
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n < 10 ? n.toFixed(1) : Math.round(n)} ${u[i]}`;
};
const fmtDate = (s) => {
  if (!s) return '—';
  try { return new Date(s).toLocaleString('ru-RU'); } catch { return s; }
};

const TYPE_OPTS = [
  { value: '',           label: 'Все типы' },
  { value: 'image/',     label: 'Изображения' },
  { value: 'video/',     label: 'Видео' },
  { value: 'audio/',     label: 'Аудио' },
  { value: 'text/',      label: 'Текст' },
  { value: 'application/pdf', label: 'PDF' },
  { value: 'application/zip', label: 'ZIP' },
  { value: 'application/json', label: 'JSON' },
  { value: 'application/vnd.openxmlformats-officedocument', label: 'Office' },
];

/** FR#15 — search by name / type / date. Backend uses Postgres tsvector + trigram. */
export default function Search() {
  const toast = useToast();
  const navigate = useNavigate();

  const [q, setQ]       = useState('');
  const [type, setType] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo]     = useState('');
  const [results, setResults] = useState([]);
  const [busy, setBusy] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const [previewFile, setPreviewFile] = useState(null);

  const submit = async (e) => {
    if (e) e.preventDefault();
    setBusy(true); setHasSearched(true);
    try {
      const r = await api.searchFiles({ q, type, from, to, limit: 200 });
      setResults(r || []);
    } catch (err) {
      toast.error(err.message || 'Ошибка поиска');
    } finally { setBusy(false); }
  };

  const empty = useMemo(() => results.length === 0, [results]);

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-left">
          <h1 className="page-title">Поиск</h1>
        </div>
      </header>

      <section className="panel">
        <form onSubmit={submit} className="panel-row" style={{ alignItems: 'flex-end' }}>
          <label className="field" style={{ flex: '2 1 240px' }}>
            <span>Текст</span>
            <input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="имя файла, фрагмент, расширение…"
              autoFocus
            />
          </label>
          <label className="field" style={{ flex: '1 1 160px' }}>
            <span>Тип</span>
            <select value={type} onChange={(e) => setType(e.target.value)}>
              {TYPE_OPTS.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>С даты</span>
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="field">
            <span>По дату</span>
            <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </label>
          <button className="btn btn-primary" disabled={busy}>
            {busy ? 'Ищем…' : 'Найти'}
          </button>
          <button type="button" className="btn" onClick={() => {
            setQ(''); setType(''); setFrom(''); setTo(''); setResults([]); setHasSearched(false);
          }}>
            Сбросить
          </button>
        </form>
      </section>

      <section className="panel">
        {!hasSearched && <div className="muted">Введите запрос и нажмите «Найти».</div>}
        {hasSearched && empty && !busy && <div className="muted">Ничего не найдено.</div>}
        {!empty && (
          <table className="ftable">
            <thead>
              <tr>
                <th>Имя</th>
                <th>Тип</th>
                <th>Размер</th>
                <th>Загружен</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {results.map((f) => (
                <tr key={f.id}>
                  <td>
                    <div className="row-name">
                      <FileIcon contentType={f.contentType} filename={f.filename} size={18} />
                      <span>{f.filename}</span>
                    </div>
                  </td>
                  <td>{f.contentType || '—'}</td>
                  <td>{fmtSize(f.size)}</td>
                  <td>{fmtDate(f.uploadedAt)}</td>
                  <td style={{ display: 'flex', gap: 4 }}>
                    {/* Preview only image/* and PDF — backend rejects others. */}
                    {(/^image\//.test(f.contentType) || f.contentType === 'application/pdf') && (
                      <button className="icon-btn" title="Предпросмотр" onClick={() => setPreviewFile(f)}>
                        <Icon name="info" />
                      </button>
                    )}
                    <button className="icon-btn" title="Скачать"
                            onClick={() => api.triggerDownload(f.id, f.filename).catch((e) => toast.error(e.message))}>
                      <Icon name="download" />
                    </button>
                    {f.folderId && (
                      <button className="icon-btn" title="Открыть папку"
                              onClick={() => navigate(`/folder/${f.folderId}`)}>
                        <Icon name="folder" />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {previewFile && <PreviewModal file={previewFile} onClose={() => setPreviewFile(null)} />}
    </div>
  );
}
