import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api, connectSession } from '@/lib/api'
import type { Role, TokenResponse } from '@/lib/types'

/**
 * The access token is a JWT this service signed: its subject is the customer id and it carries the roles, so the
 * interface reads both from it without asking the server. Kept in localStorage so a refresh keeps you signed in.
 */
interface Session {
  token: string
  customerId: string
  roles: Role[]
  expiresAt: number
}

interface SessionContext {
  session: Session | null
  isOrganizer: boolean
  signIn: (email: string, password: string) => Promise<void>
  signUp: (name: string, email: string, password: string) => Promise<void>
  signOut: () => void
}

const STORAGE_KEY = 'seatline.session'
const Context = createContext<SessionContext | null>(null)

export function readToken(token: string): Session {
  const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
  return { token, customerId: payload.sub, roles: payload.roles ?? [], expiresAt: payload.exp * 1000 }
}

function restore(): Session | null {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (!saved) return null
    const session = readToken(saved)
    return session.expiresAt > Date.now() ? session : null
  } catch {
    return null
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(restore)
  const queries = useQueryClient()

  const signOut = useCallback(() => {
    try {
      localStorage.removeItem(STORAGE_KEY)
    } catch {
      // Storage can be blocked; the session still ends in memory.
    }
    setSession(null)
    queries.clear()
  }, [queries])

  const signIn = useCallback(async (email: string, password: string) => {
    const response = await api<TokenResponse>('/api/v1/auth/token', { body: { email, password }, token: null })
    try {
      localStorage.setItem(STORAGE_KEY, response.accessToken)
    } catch {
      // Not remembered across reloads, but signed in for now.
    }
    setSession(readToken(response.accessToken))
  }, [])

  const signUp = useCallback(
    async (name: string, email: string, password: string) => {
      await api('/api/v1/auth/register', { body: { name, email, password }, token: null })
      await signIn(email, password)
    },
    [signIn],
  )

  const value = useMemo<SessionContext>(
    () => ({ session, isOrganizer: session?.roles.includes('ORGANIZER') ?? false, signIn, signUp, signOut }),
    [session, signIn, signUp, signOut],
  )

  // Set during render, so the token is in place before any child runs its first query.
  connectSession(() => session?.token ?? null, signOut)

  return <Context.Provider value={value}>{children}</Context.Provider>
}

export function useSession(): SessionContext {
  const context = useContext(Context)
  if (!context) throw new Error('useSession needs a SessionProvider')
  return context
}
