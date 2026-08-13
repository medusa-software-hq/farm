import { createContext, use, useCallback, useEffect, useState, type ReactNode } from 'react';
import { farm } from './api.ts';
import { useApiHeaders } from './useApiHeaders.ts';

export interface Org {
  orgLogin: string;
  installationId: bigint;
}

interface OrgContextValue {
  /** `null` while the linked orgs are still loading. */
  orgs: Org[] | null;
  /** The org everything is scoped to; `null` when none are linked. */
  selected: Org | null;
  select: (orgLogin: string) => void;
}

const OrgContext = createContext<OrgContextValue | null>(null);

const STORAGE_KEY = 'farm.selectedOrg';

export function OrgProvider({ children }: { children: ReactNode }) {
  const headers = useApiHeaders();
  const [orgs, setOrgs] = useState<Org[] | null>(null);
  const [selectedLogin, setSelectedLogin] = useState<string | null>(() =>
    localStorage.getItem(STORAGE_KEY)
  );

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const res = await farm.listLinkedOrgs({}, { headers });
        if (!cancelled) {
          setOrgs(
            res.orgs.map((o) => ({ orgLogin: o.orgLogin, installationId: o.installationId }))
          );
        }
      } catch {
        if (!cancelled) {
          setOrgs([]);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [headers]);

  const select = useCallback((orgLogin: string) => {
    setSelectedLogin(orgLogin);
    localStorage.setItem(STORAGE_KEY, orgLogin);
  }, []);

  // Resolve the selection against the loaded orgs; fall back to the first linked org.
  const selected =
    orgs && orgs.length > 0 ? (orgs.find((o) => o.orgLogin === selectedLogin) ?? orgs[0]) : null;

  return <OrgContext value={{ orgs, selected, select }}>{children}</OrgContext>;
}

export function useOrg(): OrgContextValue {
  const ctx = use(OrgContext);
  if (!ctx) {
    throw new Error('useOrg must be used within OrgProvider');
  }
  return ctx;
}
