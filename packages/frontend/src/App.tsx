import { Suspense, lazy, useEffect, useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './contexts/AuthContext';
import { AppShell } from './components/layout/AppShell';
import { LoadingSpinner } from './components/ui/loading-spinner';
import { LoginPage } from './pages/LoginPage';
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
  const [required, setRequired] = useState<boolean | null>(null);
  useEffect(() => { void api.get<{ setupRequired: boolean }>('/auth/setup/status').then(r => setRequired(r.setupRequired)); }, []);
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
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </BrowserRouter></SetupGate>
    </AuthProvider>
  );
}
