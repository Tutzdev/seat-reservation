import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { lazy, StrictMode, Suspense } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { AppShell, RequireSession } from '@/components/app-shell'
import { SessionProvider } from '@/features/auth/session'
import { SignInPage, SignUpPage } from '@/features/auth/auth-pages'
import { BookingPage } from '@/features/bookings/booking-page'
import { MyBookingsPage } from '@/features/bookings/my-bookings-page'
import { EventPage } from '@/features/events/event-page'
import { EventsPage } from '@/features/events/events-page'
import { ApiError } from '@/lib/api'
import './index.css'

// Buyers never load the organizer's pages.
const OrganizerPage = lazy(() => import('@/features/organizer/organizer-page').then((m) => ({ default: m.OrganizerPage })))
const SaleDashboardPage = lazy(() =>
  import('@/features/organizer/sale-dashboard-page').then((m) => ({ default: m.SaleDashboardPage })))
const loading = <p className="text-ink-soft">Loading…</p>

const queries = new QueryClient({
  defaultOptions: {
    queries: {
      // A 4xx will not get better by asking again; a dropped connection might.
      retry: (failures, error) => !(error instanceof ApiError && error.status < 500) && failures < 2,
      refetchOnWindowFocus: true,
    },
  },
})

const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      { path: '/', element: <EventsPage /> },
      { path: '/events/:eventId', element: <EventPage /> },
      { path: '/sign-in', element: <SignInPage /> },
      { path: '/sign-up', element: <SignUpPage /> },
      {
        element: <RequireSession />,
        children: [
          { path: '/bookings', element: <MyBookingsPage /> },
          { path: '/bookings/:bookingId', element: <BookingPage /> },
        ],
      },
      {
        element: <RequireSession organizer />,
        children: [
          { path: '/organizer', element: <Suspense fallback={loading}><OrganizerPage /></Suspense> },
          {
            path: '/organizer/events/:eventId',
            element: <Suspense fallback={loading}><SaleDashboardPage /></Suspense>,
          },
        ],
      },
    ],
  },
])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queries}>
      <SessionProvider>
        <RouterProvider router={router} />
      </SessionProvider>
    </QueryClientProvider>
  </StrictMode>,
)
