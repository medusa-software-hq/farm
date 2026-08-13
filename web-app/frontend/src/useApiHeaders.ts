import { useMemo } from 'react';
import { useAuth } from './useAuth.tsx';

/** The bearer auth header for API calls. Only meaningful inside the authenticated subtree. */
export function useApiHeaders(): Record<string, string> {
  const { state } = useAuth();
  const token = state.status === 'authenticated' ? state.token : '';
  return useMemo(() => ({ Authorization: `Bearer ${token}` }), [token]);
}
