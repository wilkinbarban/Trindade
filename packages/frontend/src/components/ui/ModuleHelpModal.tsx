import { useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { HelpCircle, X } from 'lucide-react';
import { Button } from './button';

export interface HelpSection {
  title: string;
  description?: string;
  steps?: string[];
}

export interface ModuleHelpModalProps {
  title: string;
  subtitle?: string;
  description: string;
  sections?: HelpSection[];
  className?: string;
  buttonLabel?: string;
}

export function ModuleHelpModal({
  title,
  subtitle,
  description,
  sections = [],
  className = '',
  buttonLabel,
}: ModuleHelpModalProps) {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const modalRef = useRef<HTMLDivElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!isOpen) return;

    // Trap focus and handle escape
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setIsOpen(false);
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    closeButtonRef.current?.focus();

    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen]);

  return (
    <>
      <button
        type="button"
        onClick={() => setIsOpen(true)}
        className={`inline-flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-semibold text-gray-600 bg-gray-50 hover:bg-gray-100 hover:text-gray-900 border border-gray-200 rounded-lg transition-colors shadow-2xs ${className}`}
        aria-label={t('ui.help')}
      >
        <HelpCircle className="h-4 w-4 text-amber-600 shrink-0" />
        <span>{buttonLabel || t('ui.help')}</span>
      </button>

      {isOpen && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="help-modal-title"
          className="fixed inset-0 z-50 bg-black/60 backdrop-blur-xs flex items-center justify-center p-3 sm:p-4 animate-in fade-in duration-150"
          onClick={(e) => {
            if (e.target === e.currentTarget) setIsOpen(false);
          }}
        >
          <div
            ref={modalRef}
            className="bg-white rounded-2xl shadow-2xl max-w-lg w-full max-h-[85vh] flex flex-col border border-gray-200 overflow-hidden"
          >
            {/* Encabezado */}
            <div className="flex items-start justify-between p-4 sm:p-5 border-b border-gray-100 bg-amber-50/50 shrink-0">
              <div className="flex items-center gap-3">
                <span className="p-2 bg-amber-100 text-amber-800 rounded-xl">
                  <HelpCircle className="h-5 w-5" />
                </span>
                <div>
                  <h3 id="help-modal-title" className="text-base sm:text-lg font-bold text-gray-900">
                    {title}
                  </h3>
                  {subtitle && <p className="text-xs text-gray-500 mt-0.5">{subtitle}</p>}
                </div>
              </div>
              <button
                ref={closeButtonRef}
                type="button"
                onClick={() => setIsOpen(false)}
                className="h-9 w-9 -mr-1 -mt-1 p-0 flex items-center justify-center rounded-xl text-gray-400 hover:text-gray-700 hover:bg-gray-100 transition-colors"
                aria-label={t('ui.close')}
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {/* Contenido Scrollable */}
            <div className="p-5 overflow-y-auto space-y-4 text-sm text-gray-700 flex-1">
              <div className="p-3.5 bg-blue-50/60 border border-blue-100 rounded-xl text-blue-950 leading-relaxed text-xs sm:text-sm">
                {description}
              </div>

              {sections.length > 0 && (
                <div className="space-y-4 pt-1">
                  {sections.map((section, idx) => (
                    <div key={idx} className="space-y-2 border-b border-gray-100 pb-3.5 last:border-0 last:pb-0">
                      <h4 className="text-xs sm:text-sm font-bold text-gray-900 flex items-center gap-2">
                        <span className="flex items-center justify-center w-5 h-5 rounded-full bg-amber-100 text-amber-900 text-xs font-bold">
                          {idx + 1}
                        </span>
                        {section.title}
                      </h4>
                      {section.description && (
                        <p className="text-xs text-gray-600 leading-relaxed pl-7">{section.description}</p>
                      )}
                      {section.steps && section.steps.length > 0 && (
                        <ul className="text-xs text-gray-600 space-y-1 pl-7 list-disc list-outside">
                          {section.steps.map((step, sIdx) => (
                            <li key={sIdx} className="leading-relaxed">
                              {step}
                            </li>
                          ))}
                        </ul>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Pie con Botón de Cierre Cómodo para Móviles */}
            <div className="p-4 border-t border-gray-100 bg-gray-50/60 flex justify-end shrink-0">
              <Button
                type="button"
                onClick={() => setIsOpen(false)}
                className="w-full sm:w-auto px-5 py-2.5 bg-gray-800 hover:bg-gray-900 text-white rounded-xl text-xs sm:text-sm font-semibold shadow-xs"
              >
                {t('ui.understand')}
              </Button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
