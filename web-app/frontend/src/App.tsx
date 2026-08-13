import { Navigate, Route, Routes } from 'react-router-dom';
import { AppLayout } from './AppLayout.tsx';
import { OrgProvider } from './OrgContext.tsx';
import { Issues } from './routes/Issues.tsx';
import { Overview } from './routes/Overview.tsx';
import { Repositories } from './routes/Repositories.tsx';
import { Sessions } from './routes/Sessions.tsx';
import { SignInWall } from './SignInWall.tsx';
import { useAuth } from './useAuth.tsx';

export default function App() {
  const { state } = useAuth();

  if (state.status === 'loading') {
    return null;
  }
  if (state.status === 'unauthenticated') {
    return <SignInWall />;
  }

  return (
    <OrgProvider>
      <Routes>
        <Route element={<AppLayout />}>
          <Route index element={<Overview />} />
          <Route path="repositories" element={<Repositories />} />
          <Route path="issues" element={<Issues />} />
          <Route path="sessions" element={<Sessions />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </OrgProvider>
  );
}
