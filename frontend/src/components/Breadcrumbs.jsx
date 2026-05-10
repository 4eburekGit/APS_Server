import { Link } from 'react-router-dom';

export default function Breadcrumbs({ items }) {
  return (
    <nav className="crumbs">
      {items.map((it, i) => {
        const last = i === items.length - 1;
        return (
          <span key={i} className="crumb">
            {it.to && !last ? <Link to={it.to}>{it.label}</Link> : <span className={last ? 'current' : ''}>{it.label}</span>}
            {!last && <span className="sep">/</span>}
          </span>
        );
      })}
    </nav>
  );
}
