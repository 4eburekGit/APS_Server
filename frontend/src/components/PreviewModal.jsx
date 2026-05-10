import { useEffect, useState } from 'react';
import { api } from '../api/client.js';
import Icon from './Icon.jsx';
import FileIcon from './FileIcon.jsx';
import FileDetails from './FileDetails.jsx';

/**
 * Universal file viewer modal.
 *
 * Three preview modes resolved client-side from contentType / filename:
 *   • image  — image/* and application/pdf → server-rendered JPEG thumb
 *   • text   — text/* + JSON/YAML/source/log/etc → first 256 KB as text
 *   • none   — placeholder with file icon + "download original" CTA
 *
 * Right pane embeds {@link FileDetails} so tags / comments / audit live
 * next to the preview, not behind a separate ctx-menu click.
 */
const TEXT_EXT = new Set([
  'txt','log','md','markdown','csv','tsv','json','jsonl','xml','yaml','yml','toml',
  'ini','conf','cfg','env','properties','html','htm','css','scss','less','js','mjs',
  'ts','tsx','jsx','py','rb','go','rs','java','kt','kts','scala','cs','cpp','c','h',
  'hpp','sh','bash','zsh','fish','ps1','sql','graphql','gql','patch','diff',
  'gitignore','dockerfile','makefile','tex','lua','pl','php','ex','exs','clj',
]);

function detectMode(file) {
  const ct = (file.contentType || '').toLowerCase();
  if (ct.startsWith('image/') || ct === 'application/pdf') return 'image';
  if (ct.startsWith('text/')) return 'text';
  if ([
    'application/json','application/xml','application/yaml','application/x-yaml',
    'application/javascript','application/x-javascript','application/typescript',
    'application/sql','application/x-sh','application/x-shellscript',
    'application/x-httpd-php','application/toml','application/x-properties',
  ].includes(ct)) return 'text';
  // Fall back on extension — content_type may be octet-stream for old uploads.
  const ext = (file.filename || '').split('.').pop()?.toLowerCase();
  if (ext && TEXT_EXT.has(ext)) return 'text';
  const lower = (file.filename || '').toLowerCase();
  if (['readme','license','changelog','authors','dockerfile','makefile'].includes(lower)) return 'text';
  return 'none';
}

const fmtSize = (b) => {
  if (b == null) return '—';
  const u = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
  let i = 0; let n = Number(b);
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n < 10 ? n.toFixed(1) : Math.round(n)} ${u[i]}`;
};

export default function PreviewModal({ file, onClose, onTagsChanged }) {
  const mode = detectMode(file);
  const [imgUrl, setImgUrl] = useState(null);
  const [textData, setTextData] = useState(null);  // { content, truncated, size }
  const [err, setErr] = useState(null);
  const [wrap, setWrap] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let createdUrl = null;
    setImgUrl(null); setTextData(null); setErr(null);

    if (mode === 'image') {
      api.previewBlobUrl(file.id, 1024)
        .then((url) => {
          if (cancelled) { URL.revokeObjectURL(url); return; }
          createdUrl = url; setImgUrl(url);
        })
        .catch((e) => {
          if (!cancelled) {
            setErr(e.status === 415
              ? 'Сервер не смог построить превью для этого типа.'
              : (e.message || 'Не удалось загрузить превью'));
          }
        });
    } else if (mode === 'text') {
      api.previewText(file.id, 262144)
        .then((d) => { if (!cancelled) setTextData(d); })
        .catch((e) => {
          if (!cancelled) {
            setErr(e.status === 415
              ? 'Текстовый предпросмотр недоступен для этого файла.'
              : (e.message || 'Не удалось загрузить превью'));
          }
        });
    }
    return () => {
      cancelled = true;
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [file, mode]);

  // ESC closes
  useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const downloadOriginal = () =>
    api.triggerDownload(file.id, file.filename).catch(() => {});

  const renderPreview = () => {
    if (err) {
      return (
        <div className="preview-placeholder">
          <div className="placeholder-icon"><FileIcon contentType={file.contentType} filename={file.filename} size={36} /></div>
          <div>{err}</div>
          <button className="btn btn-primary" onClick={downloadOriginal}>
            <Icon name="download" /> Скачать оригинал
          </button>
        </div>
      );
    }
    if (mode === 'image') {
      if (!imgUrl) return <div className="preview-spinner">Загрузка превью…</div>;
      return <img src={imgUrl} alt={file.filename} className="preview-img" />;
    }
    if (mode === 'text') {
      if (!textData) return <div className="preview-spinner">Загрузка содержимого…</div>;
      return (
        <div style={{ width: '100%' }}>
          <div className="preview-text-meta">
            {fmtSize(textData.size)} · {textData.contentType || file.contentType || 'text'}
            {textData.truncated && ' · показаны первые 256 КБ'}
            <button
              className="btn"
              style={{ marginLeft: 12, padding: '2px 8px' }}
              onClick={() => setWrap((w) => !w)}
            >
              {wrap ? 'Не переносить строки' : 'Переносить строки'}
            </button>
          </div>
          <pre className={`preview-text ${wrap ? 'wrap' : ''}`}>{textData.content}</pre>
        </div>
      );
    }
    // mode === 'none'
    return (
      <div className="preview-placeholder">
        <div className="placeholder-icon"><FileIcon contentType={file.contentType} filename={file.filename} size={48} /></div>
        <div>
          <div style={{ fontWeight: 700, color: 'var(--text)', marginBottom: 4 }}>
            Предпросмотр для этого типа недоступен
          </div>
          <div className="muted small">{file.contentType || 'unknown'} · {fmtSize(file.size)}</div>
        </div>
        <button className="btn btn-primary" onClick={downloadOriginal}>
          <Icon name="download" /> Скачать оригинал
        </button>
      </div>
    );
  };

  return (
    <div className="modal-backdrop" onClick={onClose} role="dialog" aria-modal="true">
      <div className="modal preview-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <h3 title={file.filename} style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {file.filename}
          </h3>
          <button className="icon-btn" onClick={onClose} title="Закрыть"><Icon name="close" /></button>
        </div>
        <div className="modal-body preview-body">
          <div className="preview-pane">
            {renderPreview()}
          </div>
          <FileDetails file={file} embedded onClose={() => {}} onTagsChanged={onTagsChanged} />
        </div>
        <div className="modal-foot">
          <button className="btn" onClick={onClose}>Закрыть</button>
          <button className="btn btn-primary" onClick={downloadOriginal}>
            Скачать оригинал
          </button>
        </div>
      </div>
    </div>
  );
}
