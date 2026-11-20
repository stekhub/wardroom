import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { setSessionEndedHandler } from '@/api/client'
import { AUTH_STATE_KEY } from '@/auth/authState'
import { Toaster } from '@/components/ui/sonner'
import { TooltipProvider } from '@/components/ui/tooltip'
import { routes } from '@/routes'

export function App() {
  // Created once per application instance, so tests never share cached data.
  const [queryClient] = useState(() => new QueryClient())
  const [router] = useState(() => createBrowserRouter(routes))

  useEffect(() => {
    // A 401 on any request means the session has ended; asking again shows the login page.
    setSessionEndedHandler(() => {
      void queryClient.invalidateQueries({ queryKey: AUTH_STATE_KEY })
    })
    return () => {
      setSessionEndedHandler(() => undefined)
    }
  }, [queryClient])

  return (
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <RouterProvider router={router} />
        <Toaster position="bottom-right" />
      </TooltipProvider>
    </QueryClientProvider>
  )
}
