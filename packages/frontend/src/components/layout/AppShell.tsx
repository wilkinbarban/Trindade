import { useState, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { LucideMenu, LucideX, LayoutDashboard, ClipboardList, Truck, MessageCircle, Clock, ShieldCheck, History } from 'lucide-react';
import { useAuth } from '../../contexts/AuthContext';
import { Button } from '../ui/button';


interface NavItemProps {
  href?: string;
  icon: ReactNode;
  label: string;
  disabled?: boolean;
  excludedActivePrefixes?: string[];
}

function NavItem({ href, icon, label, disabled, excludedActivePrefixes = [] }: NavItemProps) {
  const location = useLocation();
  const isExcludedActivePath = excludedActivePrefixes.some(
    (prefix) => location.pathname === prefix || location.pathname.startsWith(prefix + '/')
  );
  const isActive = href
    ? !isExcludedActivePath &&
      (location.pathname === href || (href !== '/' && location.pathname.startsWith(href + '/'))) &&
      !(href === '/admin' && (location.pathname === '/admin/audit' || location.pathname.startsWith('/admin/audit/')))
    : false;

  if (disabled || !href) {
    return (
      <span className="flex items-center px-3 py-2 text-gray-400 rounded-lg cursor-not-allowed select-none">
        <span className="mr-3 h-4 w-4 opacity-50">{icon}</span>
        {label}
      </span>
    );
  }

  return (
    <Link
      to={href}
      className={`flex items-center px-3 py-2 rounded-lg transition-colors ${
        isActive
          ? 'bg-blue-50 text-blue-700 font-medium'
          : 'text-gray-700 hover:bg-gray-100'
      }`}
    >
      <span className="mr-3 h-4 w-4">{icon}</span>
      {label}
    </Link>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation();
  const { user, logout } = useAuth();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const closeSidebar = () => setSidebarOpen(false);

  return (
    <div className="flex h-screen bg-gray-50">
      {/* Mobile overlay */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 z-20 bg-black/50 lg:hidden"
          onClick={closeSidebar}
          aria-hidden="true"
        />
      )}

      {/* Sidebar */}
      <aside
        className={`fixed inset-y-0 left-0 z-30 w-64 transform bg-white shadow-lg transition-transform duration-200 ease-in-out lg:static lg:translate-x-0 flex flex-col ${
          sidebarOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <div className="flex items-center justify-between h-16 px-4 border-b border-gray-200 shrink-0">
          <h1 className="text-lg font-bold text-gray-800">{t('app.name')}</h1>
          <Button
            variant="ghost"
            size="icon"
            onClick={closeSidebar}
            className="lg:hidden"
            aria-label={t('ui.closeSidebar')}
          >
            <LucideX className="h-5 w-5" />
          </Button>
        </div>

        <nav className="p-4 space-y-1 overflow-y-auto flex-1" onClick={closeSidebar}>
          <div className="px-3 py-2 text-xs font-semibold text-gray-500 uppercase tracking-wider">
            {t('nav.menu')}
          </div>

          <NavItem href="/dashboard" icon={<LayoutDashboard className="h-4 w-4" />} label={t('nav.dashboard')} />

          <div className="px-3 pt-4 pb-2 text-xs font-semibold text-gray-400 uppercase tracking-wider">
            {t('nav.operations')}
          </div>

          <NavItem href="/reports" icon={<ClipboardList className="h-4 w-4" />} label={t('nav.reports')} />
          <NavItem
            href="/loading"
            icon={<Clock className="h-4 w-4" />}
            label={t('nav.loadingSchedule')}
            excludedActivePrefixes={['/loading/reports-history', '/loading/history']}
          />
          <NavItem href="/loading/reports-history" icon={<History className="h-4 w-4" />} label={t('nav.reportHistory')} />
          <NavItem href="/loading/history" icon={<Truck className="h-4 w-4" />} label={t('nav.loadingHistory')} />

          {/* Admin section — visible to Administrador and limited Trabalhador panel */}
          {(user?.role === 'Administrador' || user?.role === 'Trabalhador') && (
            <>
              <div className="px-3 pt-4 pb-2 text-xs font-semibold text-gray-400 uppercase tracking-wider">
                {t('nav.administration')}
              </div>
              <NavItem href="/admin" icon={<ShieldCheck className="h-4 w-4" />} label={t('nav.adminPanel')} />
              {user?.role === 'Administrador' && (
                <NavItem href="/admin/audit" icon={<History className="h-4 w-4" />} label={t('nav.audit')} />
              )}
            </>
          )}

          <div className="px-3 pt-4 pb-2 text-xs font-semibold text-gray-400 uppercase tracking-wider">
            {t('nav.future')}
          </div>

          <NavItem icon={<MessageCircle className="h-4 w-4" />} label={t('nav.whatsappExport')} disabled />
        </nav>

        {/* User footer */}
        <div className="p-4 border-t border-gray-200 bg-gray-50 shrink-0">
          <div className="flex items-center justify-between">
            <div className="min-w-0">
              <div className="text-sm font-medium text-gray-700 truncate">
                {user?.username}
              </div>
              <div className="text-xs text-gray-500">{user?.role}</div>
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={logout}
            >
              {t('auth.signOut')}
            </Button>
          </div>
        </div>
      </aside>

      {/* Main content area */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Header */}
        <header className="flex items-center h-16 px-4 bg-white border-b border-gray-200 shadow-sm shrink-0">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => setSidebarOpen(true)}
            className="mr-2 lg:hidden"
            aria-label={t('ui.openSidebar')}
          >
            <LucideMenu className="h-6 w-6" />
          </Button>
          <h2 className="text-xl font-semibold text-gray-800">
            {t('app.header')}
          </h2>
          <div className="ml-auto flex items-center gap-1.5" aria-label={t('ui.switchLanguage')}>
            <Button
              variant={(i18n.resolvedLanguage || i18n.language || 'pt-BR').startsWith('pt') ? 'default' : 'outline'}
              size="sm"
              onClick={() => i18n.changeLanguage('pt-BR')}
              className="h-8 px-3 text-xs font-semibold"
            >
              PT
            </Button>
            <Button
              variant={(i18n.resolvedLanguage || i18n.language || 'pt-BR').startsWith('es') ? 'default' : 'outline'}
              size="sm"
              onClick={() => i18n.changeLanguage('es')}
              className="h-8 px-3 text-xs font-semibold"
            >
              ES
            </Button>
          </div>
        </header>

        {/* Page content */}
        <main className="flex-1 overflow-auto p-4 md:p-6">{children}</main>
      </div>
    </div>
  );
}
