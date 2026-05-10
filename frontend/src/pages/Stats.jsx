import { useEffect, useState } from 'react';
import { api } from '../api/client.js';
import { useToast } from '../context/ToastContext.jsx';
import Icon from '../components/Icon.jsx';

/** FR#18 + NFT#7 (load ≤2 s) + NFT#21 (visualised by mime type). */
/** Pretty label for a content_type. Mirrors EXT_LABEL on Dashboard but
 *  works on raw MIME (Stats has no per-file filename context).
 *  Keeps the chart legend readable instead of flooding it with raw types. */
function prettyMime(mime) {
  if (!mime || mime === 'application/octet-stream' || mime === 'unknown') return 'Без типа';
  if (mime.startsWith('image/'))   return 'Изображения (' + mime.slice(6).toUpperCase() + ')';
  if (mime.startsWith('video/'))   return 'Видео (' + mime.slice(6).toUpperCase() + ')';
  if (mime.startsWith('audio/'))   return 'Аудио (' + mime.slice(6).toUpperCase() + ')';
  if (mime.startsWith('font/'))    return 'Шрифт (' + mime.slice(5).toUpperCase() + ')';
  if (mime === 'text/plain')       return 'Текст';
  if (mime === 'text/markdown')    return 'Markdown';
  if (mime === 'text/csv')         return 'CSV';
  if (mime === 'text/html')        return 'HTML';
  if (mime === 'application/json') return 'JSON';
  if (mime === 'application/zip')  return 'ZIP';
  if (mime === 'application/x-7z-compressed') return '7-Zip';
  if (mime === 'application/x-tar') return 'TAR';
  if (mime === 'application/gzip') return 'GZip';
  if (mime === 'application/pdf')  return 'PDF';
  if (mime.includes('wordprocessingml')) return 'Word';
  if (mime.includes('spreadsheetml'))    return 'Excel';
  if (mime.includes('presentationml'))   return 'PowerPoint';
  if (mime === 'application/msword')      return 'Word';
  if (mime === 'application/vnd.ms-excel') return 'Excel';
  if (mime === 'application/vnd.ms-powerpoint') return 'PowerPoint';
  // Last-resort: strip vendor prefixes for shorter labels.
  return mime.replace(/^application\//, '').replace(/^vnd\.[a-z-]+\./, '');
}

const fmtSize = (b) => {
  if (b == null) return '—';
  const u = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
  let i = 0; let n = Number(b);
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n < 10 ? n.toFixed(1) : Math.round(n)} ${u[i]}`;
};

/* Soft palette matching the violet/indigo accent of the app. */
const PALETTE = [
  '#6366f1', '#8b5cf6', '#3b82f6', '#0ea5e9',
  '#10b981', '#f59e0b', '#ef4444', '#ec4899',
  '#14b8a6', '#a855f7',
];
const colorFor = (i) => PALETTE[i % PALETTE.length];

// Group long-tail mimes (<2% of bytes each) into "Прочее" so the chart stays readable.
function rollUp(buckets, totalBytes) {
  const big = [];
  let other = { bytes: 0, files: 0, mimes: [] };
  for (const b of buckets) {
    if (totalBytes && b.bytes / totalBytes >= 0.02) {
      big.push(b);
    } else {
      other.bytes += b.bytes;
      other.files += b.files;
      other.mimes.push(b.mime);
    }
  }
  if (other.bytes > 0) {
    big.push({ mime: `Прочее (${other.mimes.length})`, bytes: other.bytes, files: other.files });
  }
  return big;
}

// SVG donut chart — no chart library, so the bundle stays tiny.
function Donut({ data, totalBytes }) {
  const size = 220;
  const r = 86;
  const cx = size / 2;
  const cy = size / 2;
  let acc = 0;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-label="Диаграмма по типам файлов">
      <circle className="donut-bg-ring" cx={cx} cy={cy} r={r} strokeWidth="32" fill="none" />
      {data.map((b, i) => {
        const frac = totalBytes > 0 ? b.bytes / totalBytes : 0;
        if (frac <= 0) return null;
        const a0 = acc * 2 * Math.PI - Math.PI / 2;
        const a1 = (acc + frac) * 2 * Math.PI - Math.PI / 2;
        acc += frac;
        const x0 = cx + r * Math.cos(a0); const y0 = cy + r * Math.sin(a0);
        const x1 = cx + r * Math.cos(a1); const y1 = cy + r * Math.sin(a1);
        const large = frac > 0.5 ? 1 : 0;
        return (
          <path
            key={i}
            d={`M ${x0} ${y0} A ${r} ${r} 0 ${large} 1 ${x1} ${y1}`}
            stroke={colorFor(i)} strokeWidth="32" fill="none" strokeLinecap="butt"
          >
            <title>{`${prettyMime(b.mime)}: ${fmtSize(b.bytes)} (${(frac * 100).toFixed(1)}%)`}</title>
          </path>
        );
      })}
      <text className="donut-text" x={cx} y={cy - 4} textAnchor="middle" fontSize="14" fontWeight="700">
        {fmtSize(totalBytes)}
      </text>
      <text className="donut-text-faint" x={cx} y={cy + 16} textAnchor="middle" fontSize="11">
        занято
      </text>
    </svg>
  );
}

function Bars({ data, totalBytes }) {
  const max = data.reduce((m, b) => Math.max(m, b.bytes), 1);
  return (
    <div className="bar-list">
      {data.map((b, i) => (
        <div className="bar-row" key={i} title={`${prettyMime(b.mime)}: ${fmtSize(b.bytes)} (${b.files} файлов)`}>
          <div className="bar-row-head">
            <span className="bar-row-name">{prettyMime(b.mime)}</span>
            <span className="bar-row-stats">{fmtSize(b.bytes)} · {b.files}</span>
          </div>
          <div className="bar-track">
            <div className="bar-fill" style={{
              width: `${(b.bytes / max) * 100}%`,
              background: colorFor(i),
            }} />
          </div>
        </div>
      ))}
    </div>
  );
}

export default function Stats() {
  const toast = useToast();
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api.myStats()
      .then((s) => { if (!cancelled) setStats(s); })
      .catch((e) => { if (!cancelled) toast.error(e.message || 'Не удалось загрузить статистику'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [toast]);

  if (loading) {
    return (
      <div className="page">
        <div className="empty"><div className="empty-art"><Icon name="info" size={32} /></div>Загрузка статистики…</div>
      </div>
    );
  }
  if (!stats) {
    return <div className="page"><div className="empty">Нет данных</div></div>;
  }

  const usedPct = stats.quotaBytes > 0
    ? Math.min(100, (stats.usedBytes / stats.quotaBytes) * 100)
    : 0;
  const rolled = rollUp(stats.byMime || [], stats.usedBytes);
  const fillCls = usedPct > 90 ? 'danger' : usedPct > 70 ? 'warn' : '';

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-left">
          <h1 className="page-title">Статистика диска</h1>
        </div>
      </header>

      <section className="panel">
        <div className="metric-row">
          <strong>Использовано: {fmtSize(stats.usedBytes)} из {fmtSize(stats.quotaBytes)}</strong>
          <span className="muted-strong">{stats.fileCount} файл(ов)</span>
        </div>
        <div className="bar-track">
          <div className={`bar-fill ${fillCls}`} style={{ width: `${usedPct}%` }} />
        </div>
        <div className="muted small" style={{ marginTop: 6 }}>{usedPct.toFixed(1)}% от квоты</div>
      </section>

      <div className="charts-row">
        <section className="panel charts-col-fixed" style={{ marginBottom: 0 }}>
          <h3>По типам</h3>
          {rolled.length === 0
            ? <div className="muted">Нет файлов</div>
            : <Donut data={rolled} totalBytes={stats.usedBytes} />}
        </section>
        <section className="panel charts-col-flex" style={{ marginBottom: 0 }}>
          <h3>Гистограмма</h3>
          {rolled.length === 0
            ? <div className="muted">Нет файлов</div>
            : <Bars data={rolled} totalBytes={stats.usedBytes} />}
        </section>
      </div>

      <section className="panel" style={{ marginTop: 16 }}>
        <h3>Детали</h3>
        <table className="stats-table">
          <thead>
            <tr>
              <th>Тип</th>
              <th>Файлов</th>
              <th>Объём</th>
              <th>% диска</th>
            </tr>
          </thead>
          <tbody>
            {(stats.byMime || []).map((b, i) => (
              <tr key={i}>
                <td>
                  <span className="swatch" style={{ background: colorFor(i) }} />
                  {prettyMime(b.mime)}
                </td>
                <td>{b.files}</td>
                <td>{fmtSize(b.bytes)}</td>
                <td>
                  {stats.usedBytes > 0
                    ? `${((b.bytes / stats.usedBytes) * 100).toFixed(1)}%`
                    : '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </div>
  );
}
