import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
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
