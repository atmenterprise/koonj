import { createContext, useContext, useEffect, useSyncExternalStore, type ReactNode } from 'react'
import { getAuthState, initializeAuth, login, loginWithProvider, logout, register, subscribeToAuth,
  type AuthState, type KoonjRole, type SocialProvider } from './keycloak'

type AuthContextValue = AuthState & {
  login: () => Promise<void>
  loginWithProvider: (provider: SocialProvider) => Promise<void>
  register: () => Promise<void>
  logout: () => Promise<void>
  hasRole: (role: KoonjRole) => boolean
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const state = useSyncExternalStore(subscribeToAuth, getAuthState)
  useEffect(() => { void initializeAuth() }, [])

  return <AuthContext.Provider value={{ ...state, login, loginWithProvider, logout, register,
    hasRole: role => state.user?.roles.includes(role) ?? false }}>
    {children}
  </AuthContext.Provider>
}

export function useAuth() {
  const auth = useContext(AuthContext)
  if (!auth) throw new Error('AuthProvider is required')
  return auth
}
