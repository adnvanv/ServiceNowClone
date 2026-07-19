import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api, getToken, setToken, setUnauthorizedHandler } from '../api/client'
import type { LoginResult, UserInfo } from '../api/types'

interface AuthState {
  user: UserInfo | null
  roles: string[]
  ready: boolean
  isAdmin: boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserInfo | null>(null)
  const [roles, setRoles] = useState<string[]>([])
  const [ready, setReady] = useState(false)

  const logout = useCallback(() => {
    setToken(null)
    setUser(null)
    setRoles([])
  }, [])

  useEffect(() => {
    setUnauthorizedHandler(logout)
    if (!getToken()) {
      setReady(true)
      return
    }
    // Session restore: validate the stored token and refresh identity.
    api
      .get<{ user: UserInfo; roles: string[] }>('/api/now/auth/me')
      .then(({ result }) => {
        setUser(result.user)
        setRoles(result.roles)
      })
      .catch(() => logout())
      .finally(() => setReady(true))
  }, [logout])

  const login = useCallback(async (username: string, password: string) => {
    const { result } = await api.post<LoginResult>('/api/now/auth/login', { username, password })
    setToken(result.token)
    setUser(result.user)
    setRoles(result.roles)
  }, [])

  const value = useMemo(
    () => ({ user, roles, ready, isAdmin: roles.includes('admin'), login, logout }),
    [user, roles, ready, login, logout],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth outside AuthProvider')
  return ctx
}
