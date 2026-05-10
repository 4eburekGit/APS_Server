import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './context/AuthContext.jsx';
import Login from './pages/Login.jsx';
import Register from './pages/Register.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Admin from './pages/Admin.jsx';
import Stats from './pages/Stats.jsx';
import Tags from './pages/Tags.jsx';
import Profile from './pages/Profile.jsx';
import Search from './pages/Search.jsx';
import Layout from './components/Layout.jsx';

function Protected({ children }) {
  const { isAuth } = useAuth();
  if (!isAuth) return <Navigate to="/login" replace />;
  return children;
}

// Admins do not have personal storage (their owner_id won't satisfy the
// users-table FK), so the user-file routes are off-limits — redirect them
// to the admin panel instead.
function UserOnly({ children }) {
  const { user } = useAuth();
  const isAdmin = user?.role?.includes('ADMIN');
  if (isAdmin) return <Navigate to="/admin" replace />;
  return children;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />
      <Route
        path="/*"
        element={
          <Protected>
            <Layout>
              <Routes>
                <Route path="/" element={<UserOnly><Dashboard view="root" /></UserOnly>} />
                <Route path="/folder/:folderId" element={<UserOnly><Dashboard view="folder" /></UserOnly>} />
                <Route path="/trash" element={<UserOnly><Dashboard view="trash" /></UserOnly>} />
                <Route path="/stats" element={<UserOnly><Stats /></UserOnly>} />
                <Route path="/tags" element={<UserOnly><Tags /></UserOnly>} />
                <Route path="/profile" element={<UserOnly><Profile /></UserOnly>} />
                <Route path="/search" element={<UserOnly><Search /></UserOnly>} />
                <Route path="/admin" element={<Admin />} />
                <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
            </Layout>
          </Protected>
        }
      />
    </Routes>
  );
}
