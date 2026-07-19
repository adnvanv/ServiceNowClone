import { Navigate, NavLink, Route, Routes, useNavigate } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import Navigator from './components/Navigator'
import LoginPage from './pages/LoginPage'
import ListPage from './pages/ListPage'
import FormPage from './pages/FormPage'
import SchemaAdminPage from './pages/SchemaAdminPage'

export default function App() {
  const { user, ready, isAdmin, logout } = useAuth()
  const navigate = useNavigate()

  if (!ready) return null
  if (!user) return <LoginPage />

  return (
    <div className="shell">
      <header className="topbar">
        <span className="brand">Glidelike</span>
        {isAdmin && (
          <NavLink to="/admin/schema" style={{ color: '#c6cdd6', fontSize: 12 }}>
            Schema admin
          </NavLink>
        )}
        <span className="spacer" />
        <span className="user">{user.user_name}</span>
        <button
          onClick={() => {
            logout()
            navigate('/')
          }}
        >
          Log out
        </button>
      </header>
      <Navigator />
      <main className="main">
        <Routes>
          <Route path="/" element={<Navigate to="/table/incident" replace />} />
          <Route path="/table/:table" element={<ListPage />} />
          <Route path="/table/:table/new" element={<FormPage />} />
          <Route path="/table/:table/:sysId" element={<FormPage />} />
          <Route path="/admin/schema" element={<SchemaAdminPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  )
}
