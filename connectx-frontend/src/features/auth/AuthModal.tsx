import React, { useState, useEffect } from 'react';
import {
  Mail,
  Lock,
  User,
  Eye,
  EyeOff,
  Sun,
  Moon,
  ArrowRight,
  ArrowLeft,
  AlertCircle,
  CheckCircle2,
  KeyRound,
  Loader2,
  Shield,
  MessageCircle,
  Smartphone,
} from 'lucide-react';
import { authApi } from '../../api/authApi';
import { ensureLocalCryptoDevice } from '../../crypto/deviceSession';
import { applyTheme, isDarkTheme } from '../../utils/theme';
import { AuthResponse } from '../../types';
import { ConnectXLogo } from '../../components/common/ConnectXLogo';

interface AuthModalProps {
  onSuccess: (authData: AuthResponse) => void;
}

/**
 * Subtle abstract peer-connection mesh representing people and private conversations.
 * Lightweight, GPU-accelerated SVG with gentle pulse animations and zero external dependencies.
 * Adaptive: renders a compact, focused motif on mobile and the full mesh on desktop.
 */
const ConnectXConnectionMesh: React.FC<{ isDark: boolean; isMobile?: boolean }> = ({
  isDark,
  isMobile = false,
}) => {
  if (isMobile) {
    return (
      <div className="relative w-full max-w-[240px] h-12 mx-auto flex items-center justify-center select-none pointer-events-none my-1">
        {/* Soft ambient glow */}
        <div className="absolute inset-0 bg-violet-600/10 dark:bg-violet-600/15 rounded-full filter blur-lg pointer-events-none" />

        <svg
          viewBox="0 0 240 50"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          className="w-full h-full relative z-10 overflow-visible"
          aria-hidden="true"
        >
          <defs>
            <linearGradient id="m-mesh-line" x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%" stopColor={isDark ? '#818cf8' : '#6366f1'} stopOpacity="0.4" />
              <stop offset="50%" stopColor="#a855f7" stopOpacity="0.75" />
              <stop offset="100%" stopColor={isDark ? '#c084fc' : '#8b5cf6'} stopOpacity="0.4" />
            </linearGradient>
            <radialGradient id="m-mesh-glow" cx="50%" cy="50%" r="50%">
              <stop offset="0%" stopColor="#7c3aed" stopOpacity={isDark ? '0.5' : '0.25'} />
              <stop offset="100%" stopColor="#7c3aed" stopOpacity="0" />
            </radialGradient>
          </defs>

          <circle cx="120" cy="25" r="22" fill="url(#m-mesh-glow)" />

          {/* Connection Curves */}
          <path
            d="M 35 25 Q 75 8, 120 25"
            stroke="url(#m-mesh-line)"
            strokeWidth="1.5"
            strokeDasharray="3 3"
            className="animate-pulse-slow"
          />
          <path
            d="M 120 25 Q 165 42, 205 25"
            stroke="url(#m-mesh-line)"
            strokeWidth="1.5"
            strokeDasharray="3 3"
            className="animate-pulse-slow"
          />
          <path
            d="M 35 25 Q 120 42, 205 25"
            stroke={isDark ? 'rgba(129, 140, 248, 0.2)' : 'rgba(99, 102, 241, 0.15)'}
            strokeWidth="1"
          />

          {/* Left Node */}
          <circle
            cx="35"
            cy="25"
            r="9"
            fill={isDark ? '#0f172a' : '#ffffff'}
            stroke={isDark ? '#6366f1' : '#818cf8'}
            strokeWidth="1.5"
          />
          <circle cx="35" cy="25" r="3" fill="#6366f1" />

          {/* Center Hub */}
          <circle
            cx="120"
            cy="25"
            r="13"
            fill={isDark ? '#1e1b4b' : '#f5f3ff'}
            stroke="#7c3aed"
            strokeWidth="1.75"
          />
          <circle cx="120" cy="25" r="4.5" fill="#8b5cf6" />
          <circle
            cx="120"
            cy="25"
            r="17"
            stroke="#8b5cf6"
            strokeWidth="1"
            strokeDasharray="2 2"
            strokeOpacity={isDark ? '0.4' : '0.3'}
            className="animate-pulse-slow"
          />

          {/* Right Node */}
          <circle
            cx="205"
            cy="25"
            r="9"
            fill={isDark ? '#0f172a' : '#ffffff'}
            stroke={isDark ? '#6366f1' : '#818cf8'}
            strokeWidth="1.5"
          />
          <circle cx="205" cy="25" r="3" fill="#6366f1" />
        </svg>
      </div>
    );
  }

  return (
    <div className="relative w-full max-w-sm h-44 xl:h-48 mx-auto flex items-center justify-center select-none pointer-events-none">
      {/* Soft ambient violet glow */}
      <div className="absolute inset-0 bg-violet-600/10 dark:bg-violet-600/15 rounded-full filter blur-2xl pointer-events-none" />

      <svg
        viewBox="0 0 340 180"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
        className="w-full h-full relative z-10 overflow-visible"
        aria-hidden="true"
      >
        <defs>
          <linearGradient id="mesh-line-left" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor={isDark ? '#818cf8' : '#6366f1'} stopOpacity="0.5" />
            <stop offset="100%" stopColor="#8b5cf6" stopOpacity="0.7" />
          </linearGradient>
          <linearGradient id="mesh-line-right" x1="100%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%" stopColor={isDark ? '#c084fc' : '#8b5cf6'} stopOpacity="0.5" />
            <stop offset="100%" stopColor="#6366f1" stopOpacity="0.7" />
          </linearGradient>
          <radialGradient id="mesh-center-glow" cx="50%" cy="50%" r="50%">
            <stop offset="0%" stopColor="#7c3aed" stopOpacity={isDark ? '0.45' : '0.25'} />
            <stop offset="100%" stopColor="#7c3aed" stopOpacity="0" />
          </radialGradient>
        </defs>

        {/* Ambient center aura */}
        <circle cx="170" cy="90" r="50" fill="url(#mesh-center-glow)" />

        {/* Connection links */}
        <path
          d="M 55 90 C 100 45, 125 45, 170 90"
          stroke="url(#mesh-line-left)"
          strokeWidth="1.75"
          strokeDasharray="4 4"
          className="animate-pulse-slow"
        />
        <path
          d="M 170 90 C 215 135, 240 135, 285 90"
          stroke="url(#mesh-line-right)"
          strokeWidth="1.75"
          strokeDasharray="4 4"
          className="animate-pulse-slow"
        />
        <path
          d="M 170 90 L 170 32"
          stroke="url(#mesh-line-left)"
          strokeWidth="1.5"
          strokeOpacity={isDark ? '0.5' : '0.4'}
        />
        <path
          d="M 170 90 L 170 148"
          stroke="url(#mesh-line-right)"
          strokeWidth="1.5"
          strokeOpacity={isDark ? '0.5' : '0.4'}
        />
        <path
          d="M 55 90 Q 170 155, 285 90"
          stroke={isDark ? 'rgba(129, 140, 248, 0.25)' : 'rgba(99, 102, 241, 0.2)'}
          strokeWidth="1.25"
        />

        {/* Left Node (Participant) */}
        <g>
          <circle
            cx="55"
            cy="90"
            r="16"
            fill={isDark ? '#0f172a' : '#ffffff'}
            stroke={isDark ? '#6366f1' : '#818cf8'}
            strokeWidth="1.5"
          />
          <circle cx="55" cy="90" r="5" fill="#6366f1" />
          <circle
            cx="55"
            cy="90"
            r="22"
            stroke={isDark ? 'rgba(99, 102, 241, 0.25)' : 'rgba(99, 102, 241, 0.15)'}
            strokeWidth="1"
          />
        </g>

        {/* Top Node */}
        <g>
          <circle
            cx="170"
            cy="32"
            r="12"
            fill={isDark ? '#0f172a' : '#ffffff'}
            stroke={isDark ? '#8b5cf6' : '#a78bfa'}
            strokeWidth="1.5"
          />
          <circle cx="170" cy="32" r="4" fill="#8b5cf6" />
        </g>

        {/* Center Node (Active Secure Channel) */}
        <g>
          <circle
            cx="170"
            cy="90"
            r="20"
            fill={isDark ? '#1e1b4b' : '#f5f3ff'}
            stroke="#7c3aed"
            strokeWidth="2"
          />
          <circle cx="170" cy="90" r="7" fill="#8b5cf6" />
          <circle
            cx="170"
            cy="90"
            r="28"
            stroke="#8b5cf6"
            strokeWidth="1"
            strokeDasharray="3 3"
            strokeOpacity={isDark ? '0.4' : '0.3'}
            className="animate-pulse-slow"
          />
        </g>

        {/* Bottom Node */}
        <g>
          <circle
            cx="170"
            cy="148"
            r="12"
            fill={isDark ? '#0f172a' : '#ffffff'}
            stroke={isDark ? '#8b5cf6' : '#a78bfa'}
            strokeWidth="1.5"
          />
          <circle cx="170" cy="148" r="4" fill="#8b5cf6" />
        </g>

        {/* Right Node (Participant) */}
        <g>
          <circle
            cx="285"
            cy="90"
            r="16"
            fill={isDark ? '#0f172a' : '#ffffff'}
            stroke={isDark ? '#6366f1' : '#818cf8'}
            strokeWidth="1.5"
          />
          <circle cx="285" cy="90" r="5" fill="#6366f1" />
          <circle
            cx="285"
            cy="90"
            r="22"
            stroke={isDark ? 'rgba(99, 102, 241, 0.25)' : 'rgba(99, 102, 241, 0.15)'}
            strokeWidth="1"
          />
        </g>
      </svg>
    </div>
  );
};

export const AuthModal: React.FC<AuthModalProps> = ({ onSuccess }) => {
  const [activeTab, setActiveTab] = useState<'login' | 'register' | 'forgot'>('login');
  const [isDarkMode, setIsDarkMode] = useState<boolean>(() => isDarkTheme());
  const [showPassword, setShowPassword] = useState<boolean>(false);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  // Login form state
  const [loginEmailOrUsername, setLoginEmailOrUsername] = useState('');
  const [loginPassword, setLoginPassword] = useState('');

  // Register form state
  const [regUsername, setRegUsername] = useState('');
  const [regEmail, setRegEmail] = useState('');
  const [regDisplayName, setRegDisplayName] = useState('');
  const [regPassword, setRegPassword] = useState('');

  // Forgot password flow state
  const [forgotStep, setForgotStep] = useState<'EMAIL' | 'OTP' | 'PASSWORD'>('EMAIL');
  const [forgotEmail, setForgotEmail] = useState('');
  const [forgotOtp, setForgotOtp] = useState('');
  const [newPassword, setNewPassword] = useState('');

  useEffect(() => {
    applyTheme(isDarkMode);
  }, [isDarkMode]);

  const handleLoginSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!loginEmailOrUsername.trim()) {
      setError('Please enter your username or email.');
      return;
    }
    if (!loginPassword) {
      setError('Please enter your password.');
      return;
    }

    setError(null);
    setSuccessMsg(null);
    setLoading(true);

    try {
      const authData = await authApi.login({
        usernameOrEmail: loginEmailOrUsername.trim(),
        password: loginPassword,
      });

      await processPostAuthKeypair(authData);
    } catch (err: unknown) {
      console.error('Login error:', err);
      setError('Invalid username/email or password.');
      setLoading(false);
    }
  };

  const handleRegisterSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!regUsername.trim() || regUsername.length < 3) {
      setError('Username must be at least 3 characters long.');
      return;
    }
    if (!regEmail.trim()) {
      setError('Please enter a valid email address.');
      return;
    }
    if (!regPassword || regPassword.length < 6) {
      setError('Password must be at least 6 characters long.');
      return;
    }

    setError(null);
    setSuccessMsg(null);
    setLoading(true);

    try {
      await authApi.register({
        username: regUsername.trim(),
        email: regEmail.trim(),
        password: regPassword,
        displayName: regDisplayName.trim() || regUsername.trim(),
      });

      setLoading(false);
      setSuccessMsg('Account created successfully! Please sign in with your credentials.');
      setLoginEmailOrUsername(regEmail.trim() || regUsername.trim());
      setLoginPassword('');
      setActiveTab('login');

      setRegUsername('');
      setRegEmail('');
      setRegDisplayName('');
      setRegPassword('');
    } catch (err: unknown) {
      console.error('Register error:', err);
      setLoading(false);
      const msg = err instanceof Error ? err.message : '';

      if (msg.toLowerCase().includes('username is already taken') || msg.includes('USERNAME_EXISTS')) {
        setError('Username is already taken.');
      } else if (msg.toLowerCase().includes('email is already registered') || msg.includes('EMAIL_EXISTS')) {
        setError('Email address is already registered.');
      } else {
        setError(msg || 'Registration failed. Please check your details.');
      }
    }
  };

  const handleRequestForgotOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!forgotEmail.trim()) {
      setError('Please enter your email address.');
      return;
    }

    setError(null);
    setSuccessMsg(null);
    setLoading(true);

    try {
      await authApi.requestForgotPasswordOtp(forgotEmail.trim());
      setLoading(false);
      setForgotStep('OTP');
      setSuccessMsg('Verification code sent! Please check your inbox.');
    } catch (err: unknown) {
      setLoading(false);
      const msg = err instanceof Error ? err.message : 'Failed to send verification code.';
      setError(msg);
    }
  };

  const handleVerifyForgotOtp = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!forgotOtp.trim()) {
      setError('Please enter the 6-digit code.');
      return;
    }

    setError(null);
    setSuccessMsg(null);
    setLoading(true);

    try {
      await authApi.verifyForgotPasswordOtp(forgotEmail.trim(), forgotOtp.trim());
      setLoading(false);
      setForgotStep('PASSWORD');
      setSuccessMsg('Code verified! Choose your new password.');
    } catch (err: unknown) {
      setLoading(false);
      const msg = err instanceof Error ? err.message : 'Invalid verification code.';
      setError(msg);
    }
  };

  const handleResetPasswordSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newPassword || newPassword.length < 6) {
      setError('Password must be at least 6 characters long.');
      return;
    }

    setError(null);
    setSuccessMsg(null);
    setLoading(true);

    try {
      await authApi.resetPassword({
        email: forgotEmail.trim(),
        otpCode: forgotOtp.trim(),
        newPassword,
      });

      setLoading(false);
      setSuccessMsg('Password reset successfully! Please sign in with your new password.');
      setLoginEmailOrUsername(forgotEmail.trim());
      setLoginPassword('');
      setActiveTab('login');
      setForgotStep('EMAIL');
      setForgotEmail('');
      setForgotOtp('');
      setNewPassword('');
    } catch (err: unknown) {
      setLoading(false);
      const msg = err instanceof Error ? err.message : 'Failed to reset password.';
      setError(msg);
    }
  };

  const processPostAuthKeypair = async (authData: AuthResponse) => {
    localStorage.setItem('connectx_token', authData.accessToken);
    localStorage.setItem('connectx_refresh_token', authData.refreshToken);
    localStorage.setItem('connectx_user', JSON.stringify(authData.user));

    try {
      await ensureLocalCryptoDevice(authData.user);
    } catch (err) {
      console.warn('Post-auth device setup warning:', err);
    }

    setLoading(false);
    onSuccess(authData);
  };

  return (
    <div className="min-h-dvh bg-slate-50 dark:bg-[#080b12] text-slate-900 dark:text-slate-100 flex flex-col justify-between transition-colors duration-300 antialiased select-none overflow-y-auto">
      {/* Top Navigation Header */}
      <header className="w-full max-w-6xl mx-auto px-4 sm:px-8 py-3.5 sm:py-6 flex justify-between items-center z-20 flex-shrink-0">
        <div className="flex items-center gap-2.5 sm:gap-3">
          <ConnectXLogo size="md" variant="gradient" static />
          <div className="flex items-center gap-2">
            <span className="font-extrabold text-lg sm:text-2xl tracking-tight text-slate-900 dark:text-white">
              Connect<span className="text-violet-600 dark:text-violet-400">X</span>
            </span>
          </div>
        </div>

        {/* Theme Toggle Button */}
        <button
          onClick={() => setIsDarkMode(!isDarkMode)}
          className="flex items-center gap-1.5 sm:gap-2 px-3 py-1.5 sm:px-3.5 sm:py-2 rounded-xl bg-white dark:bg-slate-900/90 border border-slate-200 dark:border-slate-800 text-slate-700 dark:text-slate-200 shadow-sm hover:border-slate-300 dark:hover:border-slate-700 active:scale-95 transition-all text-xs font-semibold cursor-pointer"
          aria-label="Toggle light or dark mode"
        >
          {isDarkMode ? (
            <>
              <Sun className="h-3.5 w-3.5 sm:h-4 sm:w-4 text-amber-400 flex-shrink-0" />
              <span className="text-xs">Light</span>
            </>
          ) : (
            <>
              <Moon className="h-3.5 w-3.5 sm:h-4 sm:w-4 text-violet-600 flex-shrink-0" />
              <span className="text-xs">Dark</span>
            </>
          )}
        </button>
      </header>

      {/* Main Container */}
      <main className="flex-1 flex items-center justify-center px-4 sm:px-6 lg:px-8 py-2 sm:py-8 z-10 w-full">
        <div className="w-full max-w-5xl xl:max-w-6xl mx-auto grid grid-cols-1 lg:grid-cols-12 gap-8 lg:gap-12 xl:gap-16 items-center">
          {/* LEFT ZONE: ConnectX Brand Experience & Connection Motif (Desktop only) */}
          <section
            aria-label="ConnectX Brand"
            className="hidden lg:flex lg:col-span-6 flex-col justify-center space-y-7 xl:space-y-8 animate-fade-in pr-2 xl:pr-4"
          >
            {/* Brand Statement with "Your people." in violet accent */}
            <div className="space-y-3.5">
              <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-violet-500/10 border border-violet-500/20 text-violet-600 dark:text-violet-400 text-xs font-semibold tracking-wide">
                <span className="w-1.5 h-1.5 rounded-full bg-violet-500 animate-pulse" />
                Private Messaging
              </div>

              <h1 className="text-3xl xl:text-4xl font-extrabold tracking-tight text-slate-900 dark:text-white leading-[1.18]">
                Your conversations.<br />
                <span className="text-violet-600 dark:text-violet-400">Your people.</span><br />
                Your privacy.
              </h1>

              <p className="text-sm text-slate-500 dark:text-slate-400 leading-relaxed max-w-sm">
                Private messaging, built around the people who matter. Connect with confidence on any device.
              </p>
            </div>

            {/* Subtle Connection Mesh Visualization */}
            <ConnectXConnectionMesh isDark={isDarkMode} />

            {/* Human-Centric Trust Points */}
            <div className="space-y-3 max-w-md pt-1">
              <div className="flex items-start gap-3 text-slate-600 dark:text-slate-300">
                <div className="p-1.5 rounded-lg bg-violet-500/10 text-violet-600 dark:text-violet-400 mt-0.5 flex-shrink-0">
                  <Shield className="w-4 h-4" />
                </div>
                <div className="text-xs leading-relaxed">
                  <span className="font-semibold text-slate-900 dark:text-slate-100">Private by design</span>
                  <p className="text-slate-500 dark:text-slate-400">Your conversations stay strictly between you and the people you talk to.</p>
                </div>
              </div>

              <div className="flex items-start gap-3 text-slate-600 dark:text-slate-300">
                <div className="p-1.5 rounded-lg bg-violet-500/10 text-violet-600 dark:text-violet-400 mt-0.5 flex-shrink-0">
                  <MessageCircle className="w-4 h-4" />
                </div>
                <div className="text-xs leading-relaxed">
                  <span className="font-semibold text-slate-900 dark:text-slate-100">Direct & group chats</span>
                  <p className="text-slate-500 dark:text-slate-400">Clear, distraction-free messaging built for real conversations.</p>
                </div>
              </div>

              <div className="flex items-start gap-3 text-slate-600 dark:text-slate-300">
                <div className="p-1.5 rounded-lg bg-violet-500/10 text-violet-600 dark:text-violet-400 mt-0.5 flex-shrink-0">
                  <Smartphone className="w-4 h-4" />
                </div>
                <div className="text-xs leading-relaxed">
                  <span className="font-semibold text-slate-900 dark:text-slate-100">Connect across devices</span>
                  <p className="text-slate-500 dark:text-slate-400">Access your chats seamlessly wherever you sign in.</p>
                </div>
              </div>
            </div>
          </section>

          {/* RIGHT ZONE / MOBILE PRIMARY: Integrated Authentication Surface */}
          <section
            aria-label="Authentication Form"
            className="lg:col-span-6 w-full max-w-md lg:max-w-lg mx-auto"
          >
            {/* DEDICATED MOBILE BRAND INTRO (Visible only on mobile screens < lg) */}
            <div className="lg:hidden text-center space-y-2 mb-3.5 px-2 animate-fade-in">
              <h1 className="text-xl sm:text-2xl font-extrabold tracking-tight text-slate-900 dark:text-white leading-tight">
                Your conversations.<br />
                <span className="text-violet-600 dark:text-violet-400">Your people.</span> Your privacy.
              </h1>
              <p className="text-xs text-slate-500 dark:text-slate-400 max-w-xs mx-auto leading-relaxed">
                Private messaging, built around the people who matter.
              </p>

              {/* Compact Mobile Connection Motif */}
              <ConnectXConnectionMesh isDark={isDarkMode} isMobile={true} />
            </div>

            {/* Auth Surface (Integrated surface on desktop, comfortable card on mobile) */}
            <div className="bg-white/95 dark:bg-[#0c101c]/90 lg:bg-white/80 lg:dark:bg-[#0a0e1a]/80 backdrop-blur-sm border border-slate-200/90 dark:border-slate-800/80 lg:border-slate-200/60 lg:dark:border-slate-800/60 rounded-2xl sm:rounded-3xl shadow-lg shadow-slate-200/30 dark:shadow-none lg:shadow-[0_4px_24px_rgba(0,0,0,0.12)] p-4 sm:p-7 lg:p-8 space-y-5 sm:space-y-6 animate-pop-in transition-all">
              {/* Form Header */}
              <div className="text-left space-y-1 sm:space-y-1.5">
                <h2 className="text-lg sm:text-2xl font-bold tracking-tight text-slate-900 dark:text-white">
                  {activeTab === 'login'
                    ? 'Welcome back'
                    : activeTab === 'register'
                    ? 'Create your account'
                    : 'Reset your password'}
                </h2>
                <p className="text-xs sm:text-sm text-slate-500 dark:text-slate-400 leading-relaxed">
                  {activeTab === 'login'
                    ? 'Sign in to continue to your conversations.'
                    : activeTab === 'register'
                    ? 'Start private conversations in seconds.'
                    : forgotStep === 'EMAIL'
                    ? 'Enter your account email to receive a verification code.'
                    : forgotStep === 'OTP'
                    ? 'Enter the 6-digit verification code sent to your email.'
                    : 'Choose a new password to secure your account.'}
                </p>
              </div>

              {/* Segmented Switcher (Sign In / Create Account) */}
              {activeTab !== 'forgot' && (
                <div className="grid grid-cols-2 p-1 bg-slate-100/90 dark:bg-slate-900/90 border border-slate-200/60 dark:border-slate-800/80 rounded-xl">
                  <button
                    type="button"
                    onClick={() => {
                      setActiveTab('login');
                      setError(null);
                      setSuccessMsg(null);
                    }}
                    className={`py-2 text-xs sm:text-sm font-semibold rounded-lg transition-all cursor-pointer ${
                      activeTab === 'login'
                        ? 'bg-white dark:bg-slate-800 text-violet-600 dark:text-violet-400 shadow-sm'
                        : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
                    }`}
                  >
                    Sign In
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      setActiveTab('register');
                      setError(null);
                      setSuccessMsg(null);
                    }}
                    className={`py-2 text-xs sm:text-sm font-semibold rounded-lg transition-all cursor-pointer ${
                      activeTab === 'register'
                        ? 'bg-white dark:bg-slate-800 text-violet-600 dark:text-violet-400 shadow-sm'
                        : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white'
                    }`}
                  >
                    Create Account
                  </button>
                </div>
              )}

              {/* Inline Feedback Banners */}
              {error && (
                <div
                  role="alert"
                  className="flex items-start gap-2.5 p-3 rounded-xl bg-rose-500/10 border border-rose-500/25 text-rose-600 dark:text-rose-400 text-xs font-semibold animate-fade-in"
                >
                  <AlertCircle className="h-4 w-4 mt-0.5 flex-shrink-0" />
                  <span className="leading-snug">{error}</span>
                </div>
              )}

              {successMsg && (
                <div
                  role="status"
                  className="flex items-start gap-2.5 p-3 rounded-xl bg-emerald-500/10 border border-emerald-500/25 text-emerald-600 dark:text-emerald-400 text-xs font-semibold animate-fade-in"
                >
                  <CheckCircle2 className="h-4 w-4 mt-0.5 flex-shrink-0" />
                  <span className="leading-snug">{successMsg}</span>
                </div>
              )}

              {/* 1. SIGN IN FORM */}
              {activeTab === 'login' && (
                <form onSubmit={handleLoginSubmit} className="space-y-3.5 sm:space-y-4 animate-fade-in">
                  <div className="space-y-1.5">
                    <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">
                      Email or Username
                    </label>
                    <div className="relative">
                      <User className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400 transition-colors pointer-events-none" />
                      <input
                        type="text"
                        required
                        autoComplete="username"
                        placeholder="you@example.com or username"
                        className="w-full pl-10 pr-4 py-2.5 sm:py-3 bg-slate-50/90 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800 rounded-xl text-base sm:text-sm text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500 outline-none focus:border-violet-500 focus:ring-4 focus:ring-violet-500/15 focus:bg-white dark:focus:bg-slate-900 transition-all select-text"
                        value={loginEmailOrUsername}
                        onChange={(e) => setLoginEmailOrUsername(e.target.value)}
                      />
                    </div>
                  </div>

                  <div className="space-y-1.5">
                    <div className="flex justify-between items-center">
                      <label className="text-xs font-semibold text-slate-700 dark:text-slate-300">
                        Password
                      </label>
                      <button
                        type="button"
                        onClick={() => {
                          setActiveTab('forgot');
                          setForgotStep('EMAIL');
                          setError(null);
                          setSuccessMsg(null);
                        }}
                        className="text-xs font-medium text-violet-600 dark:text-violet-400 hover:underline cursor-pointer"
                      >
                        Forgot password?
                      </button>
                    </div>
                    <div className="relative">
                      <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400 transition-colors pointer-events-none" />
                      <input
                        type={showPassword ? 'text' : 'password'}
                        required
                        autoComplete="current-password"
                        placeholder="••••••••"
                        className="w-full pl-10 pr-11 py-2.5 sm:py-3 bg-slate-50/90 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800 rounded-xl text-base sm:text-sm text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500 outline-none focus:border-violet-500 focus:ring-4 focus:ring-violet-500/15 focus:bg-white dark:focus:bg-slate-900 transition-all select-text"
                        value={loginPassword}
                        onChange={(e) => setLoginPassword(e.target.value)}
                      />
                      <button
                        type="button"
                        onClick={() => setShowPassword(!showPassword)}
                        className="absolute right-3.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 p-1 rounded-md transition-colors cursor-pointer"
                        aria-label={showPassword ? 'Hide password' : 'Show password'}
                      >
                        {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                      </button>
                    </div>
                  </div>

                  <button
                    type="submit"
                    disabled={loading}
                    className="w-full bg-violet-600 hover:bg-violet-500 active:scale-[0.99] text-white font-semibold py-3 sm:py-3.5 rounded-xl shadow-md shadow-violet-600/20 hover:shadow-lg hover:shadow-violet-600/30 transition-all mt-2 sm:mt-3 flex items-center justify-center gap-2 disabled:opacity-60 text-sm cursor-pointer min-h-[44px]"
                  >
                    {loading ? (
                      <div className="flex items-center gap-2">
                        <Loader2 className="h-4 w-4 animate-spin text-white" />
                        <span>Signing in...</span>
                      </div>
                    ) : (
                      <>
                        <span>Sign In</span>
                        <ArrowRight className="h-4 w-4" />
                      </>
                    )}
                  </button>

                  <div className="pt-1.5 text-center">
                    <p className="text-xs text-slate-500 dark:text-slate-400">
                      New to ConnectX?{' '}
                      <button
                        type="button"
                        onClick={() => {
                          setActiveTab('register');
                          setError(null);
                          setSuccessMsg(null);
                        }}
                        className="font-semibold text-violet-600 dark:text-violet-400 hover:underline cursor-pointer"
                      >
                        Create an account
                      </button>
                    </p>
                  </div>
                </form>
              )}

              {/* 2. CREATE ACCOUNT FORM */}
              {activeTab === 'register' && (
                <form onSubmit={handleRegisterSubmit} className="space-y-3 sm:space-y-3.5 animate-fade-in">
                  <div className="space-y-1">
                    <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">
                      Full Name
                    </label>
                    <input
                      type="text"
                      placeholder="Your name"
                      autoComplete="name"
                      className="w-full px-3.5 py-2.5 sm:py-3 bg-slate-50/90 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800 rounded-xl text-base sm:text-sm text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500 outline-none focus:border-violet-500 focus:ring-4 focus:ring-violet-500/15 focus:bg-white dark:focus:bg-slate-900 transition-all select-text"
                      value={regDisplayName}
                      onChange={(e) => setRegDisplayName(e.target.value)}
                    />
                  </div>

                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <div className="space-y-1">
                      <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">
                        Username
                      </label>
                      <div className="relative">
                        <User className="absolute left-3.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400 pointer-events-none" />
                        <input
                          type="text"
                          required
                          autoComplete="username"
                          placeholder="username"
                          className="w-full pl-9 pr-3 py-2.5 sm:py-3 bg-slate-50/90 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800 rounded-xl text-base sm:text-sm text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500 outline-none focus:border-violet-500 focus:ring-4 focus:ring-violet-500/15 focus:bg-white dark:focus:bg-slate-900 transition-all select-text"
                          value={regUsername}
                          onChange={(e) => setRegUsername(e.target.value)}
                        />
                      </div>
                    </div>

                    <div className="space-y-1">
                      <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">
                        Email Address
                      </label>
                      <div className="relative">
                        <Mail className="absolute left-3.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400 pointer-events-none" />
                        <input
                          type="email"
                          required
                          autoComplete="email"
                          placeholder="name@email.com"
                          className="w-full pl-9 pr-3 py-2.5 sm:py-3 bg-slate-50/90 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800 rounded-xl text-base sm:text-sm text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500 outline-none focus:border-violet-500 focus:ring-4 focus:ring-violet-500/15 focus:bg-white dark:focus:bg-slate-900 transition-all select-text"
                          value={regEmail}
                          onChange={(e) => setRegEmail(e.target.value)}
                        />
                      </div>
                    </div>
                  </div>

                  <div className="space-y-1">
                    <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">
                      Password
                    </label>
                    <div className="relative">
                      <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400 pointer-events-none" />
                      <input
                        type={showPassword ? 'text' : 'password'}
                        required
                        autoComplete="new-password"
                        placeholder="At least 6 characters"
                        className="w-full pl-9 pr-11 py-2.5 sm:py-3 bg-slate-50/90 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800 rounded-xl text-base sm:text-sm text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500 outline-none focus:border-violet-500 focus:ring-4 focus:ring-violet-500/15 focus:bg-white dark:focus:bg-slate-900 transition-all select-text"
                        value={regPassword}
                        onChange={(e) => setRegPassword(e.target.value)}
                      />
                      <button
                        type="button"
                        onClick={() => setShowPassword(!showPassword)}
                        className="absolute right-3.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 p-1 rounded-md transition-colors cursor-pointer"
                        aria-label={showPassword ? 'Hide password' : 'Show password'}
                      >
                        {showPassword ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                      </button>
                    </div>
                  </div>

                  <button
                    type="submit"
                    disabled={loading}
                    className="w-full bg-violet-600 hover:bg-violet-500 active:scale-[0.99] text-white font-semibold py-3 sm:py-3.5 rounded-xl shadow-md shadow-violet-600/20 hover:shadow-lg hover:shadow-violet-600/30 transition-all mt-2 sm:mt-3 flex items-center justify-center gap-2 disabled:opacity-60 text-sm cursor-pointer min-h-[44px]"
                  >
                    {loading ? (
                      <div className="flex items-center gap-2">
                        <Loader2 className="h-4 w-4 animate-spin text-white" />
                        <span>Creating account...</span>
                      </div>
                    ) : (
                      <>
                        <span>Create Account</span>
                        <ArrowRight className="h-4 w-4" />
                      </>
                    )}
                  </button>

                  <div className="pt-1.5 text-center">
                    <p className="text-xs text-slate-500 dark:text-slate-400">
                      Already have an account?{' '}
                      <button
                        type="button"
                        onClick={() => {
                          setActiveTab('login');
                          setError(null);
                          setSuccessMsg(null);
                        }}
                        className="font-semibold text-violet-600 dark:text-violet-400 hover:underline cursor-pointer"
                      >
                        Sign in
                      </button>
                    </p>
                  </div>
                </form>
              )}

              {/* 3. FORGOT / RESET PASSWORD FLOW */}
              {activeTab === 'forgot' && (
                <div className="space-y-4 animate-fade-in">
                  {/* Step progress pills */}
                  <div className="flex items-center justify-center gap-2 pb-1">
                    <div
                      className={`h-1.5 flex-1 rounded-full transition-all ${
                        forgotStep === 'EMAIL' || forgotStep === 'OTP' || forgotStep === 'PASSWORD'
                          ? 'bg-violet-600'
                          : 'bg-slate-200 dark:bg-slate-800'
                      }`}
                    />
                    <div
                      className={`h-1.5 flex-1 rounded-full transition-all ${
                        forgotStep === 'OTP' || forgotStep === 'PASSWORD'
                          ? 'bg-violet-600'
                          : 'bg-slate-200 dark:bg-slate-800'
                      }`}
                    />
                    <div
                      className={`h-1.5 flex-1 rounded-full transition-all ${
                        forgotStep === 'PASSWORD'
                          ? 'bg-violet-600'
                          : 'bg-slate-200 dark:bg-slate-800'
                      }`}
                    />
                  </div>

                  {/* Step 1: Email Request */}
                  {forgotStep === 'EMAIL' && (
                    <form onSubmit={handleRequestForgotOtp} className="space-y-3.5 sm:space-y-4">
                      <div className="space-y-1.5">
                        <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">
                          Account Email Address
                        </label>
                        <div className="relative">
                          <Mail className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400 pointer-events-none" />
                          <input
                            type="email"
                            required
                            autoComplete="email"
                            placeholder="your.email@example.com"
                            className="w-full pl-10 pr-4 py-2.5 sm:py-3 bg-slate-50/90 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800 rounded-xl text-base sm:text-sm text-slate-900 dark:text-white outline-none focus:border-violet-500 focus:ring-4 focus:ring-violet-500/15 focus:bg-white dark:focus:bg-slate-900 transition-all select-text"
                            value={forgotEmail}
                            onChange={(e) => setForgotEmail(e.target.value)}
                          />
                        </div>
                      </div>

                      <div className="flex items-center gap-2 pt-1">
                        <button
                          type="button"
                          onClick={() => {
                            setActiveTab('login');
                            setError(null);
                            setSuccessMsg(null);
                          }}
                          className="px-3.5 sm:px-4 py-2.5 sm:py-3 rounded-xl border border-slate-200 dark:border-slate-800 text-xs font-semibold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800/80 flex items-center gap-1.5 transition-colors cursor-pointer min-h-[44px]"
                        >
                          <ArrowLeft className="w-4 h-4" /> Sign In
                        </button>
                        <button
                          type="submit"
                          disabled={loading}
                          className="flex-1 py-2.5 sm:py-3 rounded-xl bg-violet-600 hover:bg-violet-500 text-white text-xs font-semibold flex items-center justify-center gap-2 shadow-md shadow-violet-600/20 hover:shadow-lg hover:shadow-violet-600/30 disabled:opacity-50 transition-colors cursor-pointer min-h-[44px]"
                        >
                          {loading ? <Loader2 className="w-4 h-4 animate-spin text-white" /> : null}
                          Send Code
                        </button>
                      </div>
                    </form>
                  )}

                  {/* Step 2: OTP Verification */}
                  {forgotStep === 'OTP' && (
                    <form onSubmit={handleVerifyForgotOtp} className="space-y-3.5 sm:space-y-4">
                      <div className="space-y-1.5">
                        <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">
                          6-Digit Verification Code
                        </label>
                        <div className="relative">
                          <input
                            type="text"
                            required
                            maxLength={6}
                            placeholder="123456"
                            className="w-full px-4 py-2.5 sm:py-3 tracking-[0.4em] text-center text-lg font-mono bg-slate-50/90 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800 rounded-xl text-slate-900 dark:text-white outline-none focus:border-violet-500 focus:ring-4 focus:ring-violet-500/15 focus:bg-white dark:focus:bg-slate-900 transition-all select-text"
                            value={forgotOtp}
                            onChange={(e) => setForgotOtp(e.target.value)}
                          />
                          <KeyRound className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400 pointer-events-none" />
                        </div>
                      </div>

                      <div className="flex items-center gap-2 pt-1">
                        <button
                          type="button"
                          onClick={() => setForgotStep('EMAIL')}
                          className="px-3.5 sm:px-4 py-2.5 sm:py-3 rounded-xl border border-slate-200 dark:border-slate-800 text-xs font-semibold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800/80 transition-colors cursor-pointer min-h-[44px]"
                        >
                          Back
                        </button>
                        <button
                          type="submit"
                          disabled={loading}
                          className="flex-1 py-2.5 sm:py-3 rounded-xl bg-violet-600 hover:bg-violet-500 text-white text-xs font-semibold flex items-center justify-center gap-2 shadow-md shadow-violet-600/20 hover:shadow-lg hover:shadow-violet-600/30 disabled:opacity-50 transition-colors cursor-pointer min-h-[44px]"
                        >
                          {loading ? <Loader2 className="w-4 h-4 animate-spin text-white" /> : null}
                          Verify Code
                        </button>
                      </div>
                    </form>
                  )}

                  {/* Step 3: Set New Password */}
                  {forgotStep === 'PASSWORD' && (
                    <form onSubmit={handleResetPasswordSubmit} className="space-y-3.5 sm:space-y-4">
                      <div className="space-y-1.5">
                        <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">
                          New Password
                        </label>
                        <div className="relative">
                          <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400 pointer-events-none" />
                          <input
                            type={showPassword ? 'text' : 'password'}
                            required
                            placeholder="At least 6 characters"
                            className="w-full pl-10 pr-11 py-2.5 sm:py-3 bg-slate-50/90 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800 rounded-xl text-base sm:text-sm text-slate-900 dark:text-white outline-none focus:border-violet-500 focus:ring-4 focus:ring-violet-500/15 focus:bg-white dark:focus:bg-slate-900 transition-all select-text"
                            value={newPassword}
                            onChange={(e) => setNewPassword(e.target.value)}
                          />
                          <button
                            type="button"
                            onClick={() => setShowPassword(!showPassword)}
                            className="absolute right-3.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 p-1 rounded-md cursor-pointer"
                            aria-label={showPassword ? 'Hide password' : 'Show password'}
                          >
                            {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                          </button>
                        </div>
                      </div>

                      <button
                        type="submit"
                        disabled={loading}
                        className="w-full py-2.5 sm:py-3.5 rounded-xl bg-violet-600 hover:bg-violet-500 text-white text-xs font-semibold flex items-center justify-center gap-2 shadow-md shadow-violet-600/20 hover:shadow-lg hover:shadow-violet-600/30 disabled:opacity-50 transition-colors cursor-pointer min-h-[44px]"
                      >
                        {loading ? <Loader2 className="w-4 h-4 animate-spin text-white" /> : null}
                        Reset Password &amp; Sign In
                      </button>
                    </form>
                  )}
                </div>
              )}
            </div>
          </section>
        </div>
      </main>

      {/* Trust Footer — Clean & concise reassurance */}
      <footer className="w-full max-w-6xl mx-auto px-4 py-3 sm:py-5 text-center text-xs text-slate-400 dark:text-slate-500 z-10 flex-shrink-0">
        <span className="hidden sm:inline">ConnectX &bull; </span>Private conversations. End-to-end encrypted.
      </footer>
    </div>
  );
};
