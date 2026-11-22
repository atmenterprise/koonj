import Keycloak from 'keycloak-js'

const roles = ['KOONJ_ADMIN', 'KOONJ_OPERATOR', 'KOONJ_VIEWER'] as const
export type KoonjRole = typeof roles[number]

export interface AuthUser {
  id: string
  name: string
  email: string | null
  roles: KoonjRole[]
}

export type AuthState =
  | { status: 'loading' | 'anonymous' | 'error'; user: null }
  | { status: 'authenticated'; user: AuthUser }

const keycloak = new Keycloak({
  url: globalThis.window?.KOONJ_CONFIG?.keycloakUrl || import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8180',
  realm: import.meta.env.VITE_KEYCLOAK_REALM || 'koonj',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'koonj-ui',
})

let state: AuthState = { status: 'loading', user: null }
let initialization: Promise<void> | undefined
const subscribers = new Set<() => void>()

function publish(next: AuthState) {
  state = next
  for (const listener of subscribers) listener()
}

function publishSession() {
  const claims = keycloak.tokenParsed
  if (!keycloak.authenticated || !claims?.sub) {
    publish({ status: 'anonymous', user: null })
    return
  }
  const email = typeof claims.email === 'string' ? claims.email : null
  const name = typeof claims.name === 'string' ? claims.name
    : typeof claims.preferred_username === 'string' ? claims.preferred_username : email ?? 'Koonj user'
  const userRoles = roles.filter(role => keycloak.hasRealmRole(role) || keycloak.hasResourceRole(role, 'koonj-server'))
  publish({ status: 'authenticated', user: { id: claims.sub, name, email, roles: userRoles } })
}

export function subscribeToAuth(listener: () => void) {
  subscribers.add(listener)
  return () => { subscribers.delete(listener) }
}

export const getAuthState = () => state

export function initializeAuth() {
  initialization ??= keycloak.init({
    onLoad: 'check-sso',
    flow: 'standard',
    pkceMethod: 'S256',
    silentCheckSsoRedirectUri: window.location.origin + '/silent-check-sso.html',
    silentCheckSsoFallback: false,
  }).then(publishSession).catch(() => publish({ status: 'error', user: null }))
  return initialization
}

keycloak.onAuthLogout = publishSession
keycloak.onAuthRefreshSuccess = publishSession

export const login = () => keycloak.login()
export const register = () => keycloak.register()
export const logout = () => keycloak.logout({ redirectUri: window.location.origin + '/' })

export async function accessToken(minValidity: number) {
  if (!keycloak.authenticated) return undefined
  try {
    await keycloak.updateToken(minValidity)
    return keycloak.token
  } catch {
    // The adapter already clears invalid refresh tokens. Clear an expired
    // session on refresh transport failures as well so sign-in can recover it.
    keycloak.clearToken()
    return undefined
  }
}
