import { Suspense, lazy, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './contexts/AuthContext';
import { AppShell } from './components/layout/AppShell';
import { LoadingSpinner } from './components/ui/loading-spinner';
import { GuestReadonlyNotice } from './components/ui/guest-readonly-notice';
import { AssaiCalculatorPage } from './pages/AssaiCalculatorPage';
import { LoginPage } from './pages/LoginPage';
import { StockCalculatorPage } from './pages/StockCalculatorPage';
import { StockHistoryPage } from './pages/StockHistoryPage';
import { SetupPage } from './pages/SetupPage';
import { api } from './api/client';

const DashboardPage = lazy(() => import('./pages/DashboardPage').then(m => ({ default: m.DashboardPage })));
const ReportsPage = lazy(() => import('./pages/ReportsPage').then(m => ({ default: m.ReportsPage })));
const ReportHistoryPage = lazy(() => import('./pages/ReportHistoryPage').then(m => ({ default: m.ReportHistoryPage })));
const ReportViewPage = lazy(() => import('./pages/ReportViewPage').then(m => ({ default: m.ReportViewPage })));
const LoadingSchedulePage = lazy(() => import('./pages/LoadingSchedulePage').then(m => ({ default: m.LoadingSchedulePage })));
const LoadingHistoryPage = lazy(() => import('./pages/LoadingHistoryPage').then(m => ({ default: m.LoadingHistoryPage })));
const ReportEditPage = lazy(() => import('./pages/ReportEditPage').then(m => ({ default: m.ReportEditPage })));
const LoadingEditPage = lazy(() => import('./pages/LoadingEditPage').then(m => ({ default: m.LoadingEditPage })));
const AdminDashboard = lazy(() => import('./pages/admin/AdminDashboard').then(m => ({ default: m.AdminDashboard })));
const AuditPage = lazy(() => import('./pages/admin/AuditPage').then(m => ({ default: m.AuditPage })));
const ShareAppPage = lazy(() => import('./pages/ShareAppPage').then(m => ({ default: m.ShareAppPage })));
const MassasLaunchPage = lazy(() => import('./pages/MassasLaunchPage').then(m => ({ default: m.MassasLaunchPage })));

function LazyFallback() {
  return (
    <div className="flex items-center justify-center h-screen">
      <LoadingSpinner />
    </div>
  );
}

function LazyRoute({ children }: { children: React.ReactNode }) {
  return <Suspense fallback={<LazyFallback />}>{children}</Suspense>;
}

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return <LazyFallback />;
  }

  if (!user) {
    return <Navigate to="/login" replace />;
  }

  return (
    <AppShell>
      <LazyRoute>{children}</LazyRoute>
    </AppShell>
  );
}

function PublicRoute({ children }: { children: React.ReactNode }) {
  const { user, isLoading } = useAuth();

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-screen">
        <LoadingSpinner />
      </div>
    );
  }

  if (user) {
    return <Navigate to="/dashboard" replace />;
  }

  return <>{children}</>;
}

function SetupGate({ children }: { children: React.ReactNode }) {
  const { t } = useTranslation();
  const [required, setRequired] = useState<boolean | null>(null);
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    void api.get<{ setupRequired: boolean }>('/auth/setup/status')
      .then(r => { if (active) setRequired(r.setupRequired); })
      .catch(() => { if (active) setError(true); });
    return () => { active = false; };
  }, [attempt]);
  if (error) return <main className="min-h-screen flex flex-col items-center justify-center gap-4" role="alert">
    <p>{t('setup.gateError')}</p>
    <button type="button" onClick={() => { setError(false); setAttempt(value => value + 1); }} className="underline">{t('setup.retry')}</button>
  </main>;
  if (required === null) return <LazyFallback />;
  if (required) return <SetupPage onComplete={() => setRequired(false)} />;
  return <>{children}</>;
}

export function App() {
  return (
    <AuthProvider>
      <SetupGate><BrowserRouter basename={import.meta.env.BASE_URL}>
        <Routes>
          <Route
            path="/login"
            element={
              <PublicRoute>
                <LoginPage />
              </PublicRoute>
            }
          />
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <DashboardPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/reports"
            element={
              <ProtectedRoute>
                <ReportsPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/loading/reports-history"
            element={
              <ProtectedRoute>
                <ReportHistoryPage />
              </ProtectedRoute>
            }
          />
          <Route path="/reports/history" element={<Navigate to="/loading/reports-history" replace />} />
          <Route
            path="/reports/:id"
            element={
              <ProtectedRoute>
                <ReportViewPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/reports/:id/edit"
            element={
              <ProtectedRoute>
                <ReportEditPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/stock-history"
            element={
              <ProtectedRoute>
                <StockHistoryPage />
              </ProtectedRoute>
            }
          />
          {/* La ruta vieja redirige: un enlace ya compartido no debe morir. */}
          <Route path="/stock/history" element={<Navigate to="/stock-history" replace />} />
          <Route
            path="/stock-calculator"
            element={
              <ProtectedRoute>
                <StockCalculatorPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/assai-calculator"
            element={
              <ProtectedRoute>
                <AssaiCalculatorPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/loading"
            element={
              <ProtectedRoute>
                <LoadingSchedulePage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/loading/history"
            element={
              <ProtectedRoute>
                <LoadingHistoryPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/loading/edit"
            element={
              <ProtectedRoute>
                <LoadingEditPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/admin"
            element={
              <ProtectedRoute>
                <AdminDashboard />
              </ProtectedRoute>
            }
          />
          <Route
            path="/admin/audit"
            element={
              <ProtectedRoute>
                <AuditPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/compartir"
            element={
              <ProtectedRoute>
                <ShareAppPage />
              </ProtectedRoute>
            }
          />
          <Route
            path="/massas-launch"
            element={
              <ProtectedRoute>
                <MassasLaunchPage />
              </ProtectedRoute>
            }
          />
          <Route path="/compartilhar" element={<Navigate to="/compartir" replace />} />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      <GuestReadonlyNotice />
      </BrowserRouter></SetupGate>
    </AuthProvider>
  );
}
