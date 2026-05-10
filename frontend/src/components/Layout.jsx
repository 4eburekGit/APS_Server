import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext.jsx';
import Icon from './Icon.jsx';

export default function Layout({ children }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const onLogout = () => {
    logout();
    navigate('/login');
  };

  const initials = (user?.username || '?').slice(0, 2).toUpperCase();
  const isAdmin = user?.role?.includes('ADMIN');

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-logo">
            <Icon name="cloud" />
          </div>
          <div className="brand-text">
            <div className="brand-title">APS Cloud</div>
            <div className="brand-sub">файловое хранилище</div>
          </div>
        </div>

        <nav className="side-nav">
          {!isAdmin && (
            <>
              <NavLink to="/" end className={({isActive}) => `side-link ${isActive ? 'active' : ''}`}>
                <Icon name="home" /> <span>Мои файлы</span>
              </NavLink>
              <NavLink to="/search" className={({isActive}) => `side-link ${isActive ? 'active' : ''}`}>
                <Icon name="search" /> <span>Поиск</span>
              </NavLink>
              <NavLink to="/trash" className={({isActive}) => `side-link ${isActive ? 'active' : ''}`}>
                <Icon name="trash" /> <span>Корзина</span>
              </NavLink>
              <NavLink to="/tags" className={({isActive}) => `side-link ${isActive ? 'active' : ''}`}>
                <Icon name="tag" /> <span>Теги</span>
              </NavLink>
              <NavLink to="/stats" className={({isActive}) => `side-link ${isActive ? 'active' : ''}`}>
                <Icon name="chart" /> <span>Статистика</span>
              </NavLink>
              <NavLink to="/profile" className={({isActive}) => `side-link ${isActive ? 'active' : ''}`}>
                <Icon name="shield" /> <span>Профиль · 2FA</span>
              </NavLink>
            </>
          )}
          {isAdmin && (
            <NavLink to="/admin" className={({isActive}) => `side-link ${isActive ? 'active' : ''}`}>
              <Icon name="cloud" /> <span>Администрирование</span>
            </NavLink>
          )}
        </nav>

        {!isAdmin && (
          <div className="storage-card">
            <div className="storage-title">Квота хранилища</div>
            <div className="storage-bar">
              <div className="storage-bar-fill" style={{ width: '12%' }} />
            </div>
            <div className="storage-meta">~1.2 / 10 ГБ</div>
          </div>
        )}

        <div className="user-card" onClick={onLogout} title="Выйти">
          <div className="avatar">{initials}</div>
          <div className="user-meta">
            <div className="user-name">{user?.username || 'guest'}</div>
            <div className="user-role">{isAdmin ? 'администратор' : 'пользователь'}</div>
          </div>
          <Icon name="logout" />
        </div>
      </aside>

      <main className="main">{children}</main>
    </div>
  );
}
