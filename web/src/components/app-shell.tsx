import { LogOut } from 'lucide-react'
import { Link, NavLink, Navigate, Outlet, useLocation } from 'react-router'
import { useSession } from '@/features/auth/session'

function navClass({ isActive }: { isActive: boolean }) {
  return `rounded-md px-3 py-2 text-sm font-semibold transition-colors ${
    isActive ? 'bg-ink text-paper' : 'text-ink-soft hover:text-ink'
  }`
}

export function AppShell() {
  const { session, isOrganizer, signOut } = useSession()
  return (
    <div className="flex min-h-dvh flex-col">
      <a href="#content" className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50
        focus:rounded-md focus:bg-ink focus:px-4 focus:py-2 focus:text-paper">
        Skip to content
      </a>
      <header className="border-b border-line bg-paper-raised">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-1 px-4 py-3 sm:px-6">
          <Link to="/" className="font-display text-3xl font-extrabold tracking-tight text-ink">
            Seatline
          </Link>
          {/* On a phone the links get a row of their own under the name, instead of squeezing beside it. */}
          <nav aria-label="Main" className="order-last -mx-3 flex w-full flex-wrap items-center gap-1 sm:order-none
            sm:mx-0 sm:w-auto sm:flex-1">
            <NavLink to="/" end className={navClass}>
              Events
            </NavLink>
            {session && (
              <NavLink to="/bookings" className={navClass}>
                My tickets
              </NavLink>
            )}
            {isOrganizer && (
              <NavLink to="/organizer" className={navClass}>
                Organizer
              </NavLink>
            )}
          </nav>
          <div className="ml-auto sm:ml-0">
            {session ? (
              <button
                type="button"
                onClick={signOut}
                className="inline-flex items-center gap-2 rounded-md px-3 py-2 text-sm font-semibold text-ink-soft
                  hover:text-ink"
              >
                <LogOut aria-hidden className="size-4" />
                Sign out
              </button>
            ) : (
              <Link to="/sign-in" className="rounded-md px-3 py-2 text-sm font-semibold text-velvet hover:text-velvet-deep">
                Sign in
              </Link>
            )}
          </div>
        </div>
      </header>
      <main id="content" className="mx-auto w-full max-w-6xl flex-1 px-4 py-8 sm:px-6 sm:py-12">
        <Outlet />
      </main>
      <footer className="border-t border-line">
        <p className="mx-auto max-w-6xl px-4 py-6 text-sm text-ink-soft sm:px-6">
          A seat is held by one booking at a time, checked by the database itself.{' '}
          <a href="https://github.com/Tutzdev/seat-reservation" className="underline underline-offset-2 hover:text-ink">
            How it works
          </a>
        </p>
      </footer>
    </div>
  )
}

/** Pages that need an account send you to sign in and bring you back afterwards. */
export function RequireSession({ organizer = false }: { organizer?: boolean }) {
  const { session, isOrganizer } = useSession()
  const location = useLocation()
  if (!session) {
    return <Navigate to={`/sign-in?next=${encodeURIComponent(location.pathname)}`} replace />
  }
  if (organizer && !isOrganizer) {
    return <Navigate to="/" replace />
  }
  return <Outlet />
}
