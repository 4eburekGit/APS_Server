import { useEffect, useState } from 'react';
import { api } from '../api/client.js';
import { useToast } from '../context/ToastContext.jsx';
import Icon from './Icon.jsx';

const fmtDate = (s) => {
  if (!s) return '—';
  try { return new Date(s).toLocaleString('ru-RU'); } catch { return s; }
};

/**
 * Side-panel for one file: tags (FR#20), comments (FR#14), audit (FR#22).
 *
 * Two layout modes:
 *   • default — fixed-position right-side overlay (used by the Dashboard
 *     ctx-menu "Подробнее").
 *   • embedded={true} — flex-fill inline (used inside PreviewModal so the
 *     panel sits next to the preview image/text instead of overlapping it).
 */
export default function FileDetails({ file, onClose, embedded = false, onTagsChanged }) {
  const toast = useToast();
  const [tab, setTab] = useState('tags'); // 'tags' | 'comments' | 'audit'
  const [tags, setTags] = useState([]);
  const [allTags, setAllTags] = useState([]);
  const [comments, setComments] = useState([]);
  const [audit, setAudit] = useState([]);
  const [newComment, setNewComment] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!file) return;
    let cancelled = false;
    (async () => {
      try {
        if (tab === 'tags') {
          const [own, all] = await Promise.all([api.tagsForFile(file.id), api.listTags()]);
          if (!cancelled) { setTags(own || []); setAllTags(all || []); }
        } else if (tab === 'comments') {
          const list = await api.listComments(file.id);
          if (!cancelled) setComments(list || []);
        } else if (tab === 'audit') {
          const list = await api.fileAudit(file.id);
          if (!cancelled) setAudit(list || []);
        }
      } catch (e) { if (!cancelled) toast.error(e.message || 'Не удалось загрузить'); }
    })();
    return () => { cancelled = true; };
  }, [file, tab, toast]);

  if (!file) return null;

  const attached = new Set(tags.map((t) => t.id));

  const toggleTag = async (t) => {
    setBusy(true);
    try {
      if (attached.has(t.id)) {
        await api.detachTag(t.id, file.id);
        setTags(tags.filter((x) => x.id !== t.id));
      } else {
        await api.attachTag(t.id, file.id);
        setTags([...tags, t]);
      }
      // Notify parent (Dashboard) so its tagsByFile cache reloads and the
      // file card's mini-chip row refreshes without a manual page reload.
      if (typeof onTagsChanged === 'function') onTagsChanged(file.id);
    } catch (e) { toast.error(e.message || 'Не удалось обновить тег'); }
    finally { setBusy(false); }
  };

  const addComment = async () => {
    if (!newComment.trim()) return;
    setBusy(true);
    try {
      const c = await api.addComment(file.id, newComment.trim());
      setComments([c, ...comments]);
      setNewComment('');
    } catch (e) { toast.error(e.message || 'Не удалось добавить'); }
    finally { setBusy(false); }
  };
  const delComment = async (c) => {
    if (!confirm('Удалить комментарий?')) return;
    try {
      await api.deleteComment(file.id, c.id);
      setComments(comments.filter((x) => x.id !== c.id));
    } catch (e) { toast.error(e.message || 'Не удалось удалить'); }
  };

  return (
    <aside className={`fd-panel ${embedded ? 'fd-embedded' : ''}`} role={embedded ? undefined : 'dialog'} aria-modal={embedded ? undefined : 'true'}>
      {!embedded && (
        <header className="fd-head">
          <strong title={file.filename}>{file.filename}</strong>
          <button className="icon-btn" onClick={onClose} title="Закрыть"><Icon name="close" /></button>
        </header>
      )}

      <nav className="fd-tabs">
        {[
          ['tags', 'Теги'],
          ['comments', 'Комментарии'],
          ['audit', 'История'],
        ].map(([key, label]) => (
          <button key={key} onClick={() => setTab(key)}
                  className={`fd-tab ${tab === key ? 'active' : ''}`}>
            {label}
          </button>
        ))}
      </nav>

      <div className="fd-body">
        {tab === 'tags' && (
          <div>
            <div className="muted small" style={{ marginBottom: 10 }}>
              Кликните по тегу, чтобы прикрепить или открепить.
            </div>
            {allTags.length === 0 ? (
              <div className="muted">Нет тегов. Создайте их на странице «Теги».</div>
            ) : (
              <div className="tag-grid">
                {allTags.map((t) => {
                  const on = attached.has(t.id);
                  return (
                    <button
                      key={t.id}
                      disabled={busy}
                      onClick={() => toggleTag(t)}
                      className={`tag-chip ${on ? 'active' : ''}`}
                      style={on ? { background: t.color || 'var(--accent-soft)' } : {}}
                    >
                      <span className="tag-swatch" style={{ background: t.color || 'var(--accent-soft)' }} />
                      {on ? '✓ ' : '+ '}{t.name}
                    </button>
                  );
                })}
              </div>
            )}
          </div>
        )}

        {tab === 'comments' && (
          <div>
            <div className="comment-input-row">
              <input
                value={newComment}
                onChange={(e) => setNewComment(e.target.value)}
                placeholder="Написать комментарий…"
                onKeyDown={(e) => e.key === 'Enter' && addComment()}
              />
              <button className="btn btn-primary" onClick={addComment} disabled={busy || !newComment.trim()}>
                Отправить
              </button>
            </div>
            {comments.length === 0 ? (
              <div className="muted">Комментариев пока нет.</div>
            ) : (
              <ul className="comment-list">
                {comments.map((c) => (
                  <li key={c.id} className="comment">
                    <div className="comment-meta">
                      <span>{fmtDate(c.createdAt)}</span>
                      <button className="icon-btn" title="Удалить" onClick={() => delComment(c)} style={{ width: 24, height: 24 }}>×</button>
                    </div>
                    <div className="comment-body">{c.body}</div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {tab === 'audit' && (
          <div>
            {audit.length === 0 ? (
              <div className="muted">Записей пока нет.</div>
            ) : (
              <ul className="audit-list">
                {audit.map((a) => (
                  <li key={a.id} className="audit">
                    <div className="audit-row">
                      <span className="audit-action">{a.action}</span>
                      <span>{fmtDate(a.ts)}</span>
                    </div>
                    {a.ip && <div className="audit-ip">IP: {a.ip}</div>}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </aside>
  );
}
