import React, { useState } from 'react';
import { ChevronDown, ChevronRight, Check, Loader2 } from 'lucide-react';

// Minimal, shared building blocks for every settings-style screen (the global Settings screen and
// Group Settings) -- not a framework, just the three small pieces both screens were duplicating.
// Kept intentionally tiny: a section eyebrow label, a clickable/informational row, and the generic
// labeled dropdown-select. Nothing here knows about "profile" or "group" -- that's the caller's job.

export const SettingsSectionLabel: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <p className="px-1 text-[11px] font-semibold uppercase tracking-wide text-slate-400 dark:text-slate-500">{children}</p>
);

interface SettingsRowProps {
  icon: React.ReactNode;
  label: string;
  sublabel?: string;
  /** Short badge on the right, e.g. "Soon" -- mutually exclusive with the chevron. */
  badge?: string;
  disabled?: boolean;
  danger?: boolean;
  onClick?: () => void;
}

/** A single navigable/informational settings row -- icon, label(+sublabel), and either a chevron
 * (when clickable and no badge given) or a badge on the right. */
export const SettingsRow: React.FC<SettingsRowProps> = ({ icon, label, sublabel, badge, disabled, danger, onClick }) => {
  const clickable = !!onClick && !disabled;
  const Tag = clickable ? 'button' : 'div';
  return (
    <Tag
      {...(clickable ? { type: 'button', onClick } : {})}
      className={`w-full flex items-center gap-3 px-3.5 py-3 rounded-2xl text-left transition-colors ${
        danger
          ? 'text-red-600 dark:text-red-400'
          : disabled
          ? 'text-slate-400 dark:text-slate-500 cursor-not-allowed'
          : 'text-slate-700 dark:text-slate-200'
      } ${clickable ? 'hover:bg-slate-50 dark:hover:bg-slate-800/40' : ''}`}
    >
      <span className={`flex-shrink-0 ${danger ? 'text-red-500' : disabled ? 'text-slate-400' : 'text-indigo-400'}`}>{icon}</span>
      <span className="flex-1 min-w-0">
        <span className="block text-sm font-medium truncate">{label}</span>
        {sublabel && <span className="block text-[11px] text-slate-400 mt-0.5">{sublabel}</span>}
      </span>
      {badge && <span className="text-[10px] uppercase tracking-wide text-slate-400 flex-shrink-0">{badge}</span>}
      {clickable && !badge && <ChevronRight className="w-4 h-4 text-slate-400 flex-shrink-0" />}
    </Tag>
  );
};

interface SettingsDropdownProps<T extends string> {
  icon: React.ReactNode;
  label: string;
  options: { value: T; label: string; description: string }[];
  value: T;
  editable: boolean;
  saving: boolean;
  onSelect: (next: T) => void;
}

/** A labeled dropdown-select settings control -- current value, options with descriptions, a
 * disabled/read-only fallback when the viewer isn't allowed to change it. */
export function SettingsDropdown<T extends string>({
  icon,
  label,
  options,
  value,
  editable,
  saving,
  onSelect,
}: SettingsDropdownProps<T>) {
  const [open, setOpen] = useState(false);
  const selected = options.find((o) => o.value === value) ?? options[0];

  return (
    <div className="p-3.5 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700/60 rounded-2xl space-y-2.5">
      <div className="flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-200">
        {icon}
        {label}
      </div>
      {editable ? (
        <div className="relative">
          <button
            type="button"
            onClick={() => setOpen((v) => !v)}
            disabled={saving}
            className="w-full flex items-center justify-between px-3 py-2.5 bg-white dark:bg-slate-900 border border-slate-300 dark:border-slate-700 rounded-xl text-sm text-left disabled:opacity-60"
          >
            <span className="flex items-center gap-2">
              {saving && <Loader2 className="w-3.5 h-3.5 animate-spin text-indigo-500" />}
              {selected.label}
            </span>
            <ChevronDown className={`w-4 h-4 text-slate-400 transition-transform ${open ? 'rotate-180' : ''}`} />
          </button>
          {open && (
            <>
              <div className="fixed inset-0 z-10" onClick={() => setOpen(false)} />
              <div className="absolute left-0 right-0 top-full mt-1 bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl shadow-xl z-20 overflow-hidden">
                {options.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    onClick={() => {
                      setOpen(false);
                      onSelect(option.value);
                    }}
                    className="w-full text-left px-3 py-2.5 hover:bg-slate-50 dark:hover:bg-slate-800 flex items-start gap-2"
                  >
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-slate-700 dark:text-slate-200">{option.label}</p>
                      <p className="text-[11px] text-slate-400">{option.description}</p>
                    </div>
                    {option.value === value && <Check className="w-4 h-4 text-indigo-500 flex-shrink-0 mt-0.5" />}
                  </button>
                ))}
              </div>
            </>
          )}
        </div>
      ) : (
        <p className="text-sm text-slate-500 dark:text-slate-400">{selected.label}</p>
      )}
      <p className="text-[11px] text-slate-400">{selected.description}</p>
    </div>
  );
}
