import type { RouteObject } from 'react-router'
import { EmptyPage } from '@/pages/EmptyPage'

/**
 * The views of the UI concept are added here by the implementation steps that build them.
 * Until then every path shows the same empty page.
 */
export const routes: RouteObject[] = [{ path: '*', element: <EmptyPage /> }]
