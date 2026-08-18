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
  AlertTriangle,
  CheckCircle2,
  KeyRound,
  ArrowLeft,
  Loader2,
  ShieldCheck,
  Sparkles,
  MessageSquare,
  Key,
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
 * Minimalist abstract network motif for ConnectX auth left hero pane.
 * Represents interconnected people, fluid signal flow, and cryptographic privacy.
 */
const ConnectXNetworkMotif: React.FC = () => {
  return (
    <div className="relative w-full max-w-sm h-64 mx-auto flex items-center justify-center select-none pointer-events-none">
      <svg
        viewBox="0 0 320 240"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
        className="w-full h-full opacity-90"
      >
        {/* Soft background aura */}
        <circle cx="160" cy="120" r="90" fill="url(#violet-aura)" opacity="0.4" />

        {/* Signal Connection Lines */}
        <path
          d="M 60 70 Q 110 40, 160 80 T 260 90"
          stroke="url(#line-grad-1)"
          strokeWidth="1.5"
          strokeDasharray="4 4"
          className="animate-pulse"
        />
        <path
          d="M 50 160 Q 120 190, 160 140 T 270 150"
          stroke="url(#line-grad-2)"
          strokeWidth="1.5"
          strokeDasharray="3 3"
        />
        <path
          d="M 160 60 L 160 180"
          stroke="url(#line-grad-1)"
          strokeWidth="1"
          strokeOpacity="0.4"
        />
        <path
          d="M 70 120 L 250 120"
          stroke="url(#line-grad-2)"
          strokeWidth="1"
          strokeOpacity="0.3"
        />

        {/* Node 1: Left Top */}
        <circle cx="70" cy="80" r="16" fill="#6366f1" fillOpacity="0.12" />
        <circle cx="70" cy="80" r="8" fill="#6366f1" fillOpacity="0.3" />
        <circle cx="70" cy="80" r="4" fill="#818cf8" />

        {/* Node 2: Center Hub */}
        <circle cx="160" cy="120" r="28" fill="#8b5cf6" fillOpacity="0.1" />
        <circle cx="160" cy="120" r="18" fill="#7c3aed" fillOpacity="0.25" />
        <circle cx="160" cy="120" r="8" fill="#a78bfa" />

        {/* Node 3: Right Top */}
        <circle cx="250" cy="90" r="16" fill="#6366f1" fillOpacity="0.12" />
        <circle cx="250" cy="90" r="8" fill="#6366f1" fillOpacity="0.3" />
        <circle cx="250" cy="90" r="4" fill="#818cf8" />

        {/* Node 4: Left Bottom */}
        <circle cx="80" cy="170" r="14" fill="#ec4899" fillOpacity="0.12" />
        <circle cx="80" cy="170" r="7" fill="#ec4899" fillOpacity="0.3" />
        <circle cx="80" cy="170" r="3.5" fill="#f472b6" />

        {/* Node 5: Right Bottom */}
        <circle cx="240" cy="165" r="14" fill="#ec4899" fillOpacity="0.12" />
        <circle cx="240" cy="165" r="7" fill="#ec4899" fillOpacity="0.3" />
        <circle cx="240" cy="165" r="3.5" fill="#f472b6" />

        <defs>
          <radialGradient id="violet-aura" cx="50%" cy="50%" r="50%">
            <stop offset="0%" stopColor="#7c3aed" stopOpacity="0.4" />
            <stop offset="100%" stopColor="#7c3aed" stopOpacity="0" />
          </radialGradient>
          <linearGradient id="line-grad-1" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#6366f1" stopOpacity="0.8" />
            <stop offset="100%" stopColor="#a855f7" stopOpacity="0.8" />
          </linearGradient>
          <linearGradient id="line-grad-2" x1="100%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%" stopColor="#ec4899" stopOpacity="0.8" />
            <stop offset="100%" stopColor="#8b5cf6" stopOpacity="0.8" />
          </linearGradient>
        </defs>
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
      setSuccessMsg('Verification code sent! Please check your email inbox.');
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
      setSuccessMsg('Verification successful! Choose your new password.');
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
      console.warn('Post-auth device key registration warning:', err);
    }

    setLoading(false);
    onSuccess(authData);
  };

  return (
    <div className="min-h-dvh bg-slate-50 dark:bg-slate-950 flex flex-col justify-between text-slate-900 dark:text-white transition-colors duration-300 select-none">
      {/* Navigation Top Bar */}
      <header className="w-full max-w-7xl mx-auto px-4 sm:px-8 py-4 flex justify-between items-center z-20">
        <div className="flex items-center gap-2.5">
          <ConnectXLogo size="md" variant="gradient" static />
          <span className="font-extrabold text-xl sm:text-2xl tracking-tight text-slate-900 dark:text-white">
            Connect<span className="text-violet-600 dark:text-violet-400">X</span>
          </span>
        </div>

        {/* Theme Toggle */}
        <button
          onClick={() => setIsDarkMode(!isDarkMode)}
          className="flex items-center gap-2 px-3.5 py-2 rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-slate-700 dark:text-slate-200 shadow-sm hover:border-slate-300 dark:hover:border-slate-700 transition-all text-xs font-semibold"
          aria-label="Toggle light or dark theme"
        >
          {isDarkMode ? (
            <>
              <Sun className="h-4 w-4 text-amber-400" />
              <span>Light</span>
            </>
          ) : (
            <>
              <Moon className="h-4 w-4 text-violet-600" />
              <span>Dark</span>
            </>
          )}
        </button>
      </header>

      {/* Main Authentication Container */}
      <main className="flex-1 flex items-center justify-center px-4 py-6 sm:py-10 z-10">
        <div className="w-full max-w-5xl mx-auto grid grid-cols-1 lg:grid-cols-12 gap-8 items-center">
          {/* LEFT ZONE: ConnectX Identity & Communication Motif (Desktop only) */}
          <div className="hidden lg:flex lg:col-span-6 flex-col justify-center px-6 space-y-6">
            <div className="space-y-3">
              <div className="inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-violet-500/10 text-violet-600 dark:text-violet-400 text-xs font-semibold">
                <Sparkles className="w-3.5 h-3.5" />
                <span>Next-Gen Private Messaging</span>
              </div>
              <h1 className="text-3xl xl:text-4xl font-extrabold tracking-tight text-slate-900 dark:text-white leading-tight">
                Conversations that stay truly private.
              </h1>
              <p className="text-sm text-slate-500 dark:text-slate-400 leading-relaxed max-w-md">
                Connect directly with friends and teams through secure end-to-end encryption. Your messages and media remain completely yours.
              </p>
            </div>

            {/* Network Motif Visual */}
            <ConnectXNetworkMotif />

            {/* Feature Highlights */}
            <div className="grid grid-cols-2 gap-3 pt-2 max-w-md">
              <div className="p-3 rounded-2xl bg-white/70 dark:bg-slate-900/60 border border-slate-200/80 dark:border-slate-800/80 flex items-start gap-2.5">
                <div className="p-1.5 rounded-xl bg-violet-500/10 text-violet-600 dark:text-violet-400 mt-0.5">
                  <ShieldCheck className="w-4 h-4" />
                </div>
                <div className="min-w-0">
                  <h4 className="text-xs font-bold text-slate-800 dark:text-slate-200">Device Encryption</h4>
                  <p className="text-[11px] text-slate-400">Keys stay on your device</p>
                </div>
              </div>

              <div className="p-3 rounded-2xl bg-white/70 dark:bg-slate-900/60 border border-slate-200/80 dark:border-slate-800/80 flex items-start gap-2.5">
                <div className="p-1.5 rounded-xl bg-indigo-500/10 text-indigo-600 dark:text-indigo-400 mt-0.5">
                  <MessageSquare className="w-4 h-4" />
                </div>
                <div className="min-w-0">
                  <h4 className="text-xs font-bold text-slate-800 dark:text-slate-200">Direct &amp; Groups</h4>
                  <p className="text-[11px] text-slate-400">Seamless communication</p>
                </div>
              </div>
            </div>
          </div>

          {/* RIGHT ZONE: Focused Authentication Card */}
          <div className="lg:col-span-6 w-full max-w-md mx-auto">
            <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800/90 rounded-3xl shadow-xl p-6 sm:p-8 space-y-6 animate-pop-in">
              {/* Form Title & Subtitle */}
              <div className="text-left space-y-1.5">
                <h2 className="text-xl sm:text-2xl font-bold tracking-tight text-slate-900 dark:text-white">
                  {activeTab === 'login'
                    ? 'Welcome back'
                    : activeTab === 'register'
                    ? 'Create your account'
                    : 'Reset your password'}
                </h2>
                <p className="text-xs sm:text-sm text-slate-500 dark:text-slate-400 leading-relaxed">
                  {activeTab === 'login'
                    ? 'Sign in to access your secure chats and conversations.'
                    : activeTab === 'register'
                    ? 'Set up your profile to start messaging privately.'
                    : 'Enter your account email to receive a recovery code.'}
                </p>
              </div>

              {/* Segmented Switcher (Login / Register) */}
              {activeTab !== 'forgot' && (
                <div className="grid grid-cols-2 p-1 bg-slate-100 dark:bg-slate-800/80 rounded-2xl">
                  <button
                    type="button"
                    onClick={() => {
                      setActiveTab('login');
                      setError(null);
                      setSuccessMsg(null);
                    }}
                    className={`py-2 text-xs sm:text-sm font-semibold rounded-xl transition-all ${
                      activeTab === 'login'
                        ? 'bg-white dark:bg-slate-700 text-violet-600 dark:text-violet-400 shadow-sm'
                        : 'text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-white'
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
                    className={`py-2 text-xs sm:text-sm font-semibold rounded-xl transition-all ${
                      activeTab === 'register'
                        ? 'bg-white dark:bg-slate-700 text-violet-600 dark:text-violet-400 shadow-sm'
                        : 'text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-white'
                    }`}
                  >
                    Create Account
                  </button>
                </div>
              )}

              {/* Alerts */}
              {error && (
                <div className="flex items-center gap-2.5 p-3 rounded-2xl bg-rose-500/10 border border-rose-500/20 text-rose-600 dark:text-rose-400 text-xs font-semibold animate-pop-in">
                  <AlertTriangle className="h-4 w-4 flex-shrink-0" />
                  <span className="leading-snug">{error}</span>
                </div>
              )}

              {successMsg && (
                <div className="flex items-center gap-2.5 p-3 rounded-2xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-600 dark:text-emerald-400 text-xs font-semibold animate-pop-in">
                  <CheckCircle2 className="h-4 w-4 flex-shrink-0" />
                  <span className="leading-snug">{successMsg}</span>
                </div>
              )}

              {/* 1. SIGN IN FORM */}
              {activeTab === 'login' && (
                <form onSubmit={handleLoginSubmit} className="space-y-4">
                  <div className="space-y-1.5">
                    <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">
                      Email or Username
                    </label>
                    <div className="relative">
                      <User className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" />
                      <input
                        type="text"
                        required
                        placeholder="yourname or email@example.com"
                        className="w-full pl-10 pr-4 py-3 bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 rounded-2xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-violet-500 focus:ring-2 focus:ring-violet-500/20 transition-all select-text"
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
                        className="text-xs font-medium text-violet-600 dark:text-violet-400 hover:underline"
                      >
                        Forgot password?
                      </button>
                    </div>
                    <div className="relative">
                      <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" />
                      <input
                        type={showPassword ? 'text' : 'password'}
                        required
                        placeholder="••••••••"
                        className="w-full pl-10 pr-10 py-3 bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 rounded-2xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-violet-500 focus:ring-2 focus:ring-violet-500/20 transition-all select-text"
                        value={loginPassword}
                        onChange={(e) => setLoginPassword(e.target.value)}
                      />
                      <button
                        type="button"
                        onClick={() => setShowPassword(!showPassword)}
                        className="absolute right-3.5 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 p-1"
                        aria-label="Toggle password visibility"
                      >
                        {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                      </button>
                    </div>
                  </div>

                  <button
                    type="submit"
                    disabled={loading}
                    className="w-full bg-violet-600 hover:bg-violet-500 active:scale-[0.99] text-white font-semibold py-3 rounded-2xl shadow-md shadow-violet-600/20 transition-all mt-2 flex items-center justify-center gap-2 disabled:opacity-60"
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

                  <div className="pt-2 text-center">
                    <p className="text-xs text-slate-500 dark:text-slate-400">
                      New to ConnectX?{' '}
                      <button
                        type="button"
                        onClick={() => {
                          setActiveTab('register');
                          setError(null);
                          setSuccessMsg(null);
                        }}
                        className="font-semibold text-violet-600 dark:text-violet-400 hover:underline"
                      >
                        Create an account
                      </button>
                    </p>
                  </div>
                </form>
              )}

              {/* 2. CREATE ACCOUNT FORM */}
              {activeTab === 'register' && (
                <form onSubmit={handleRegisterSubmit} className="space-y-3.5">
                  <div className="space-y-1">
                    <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">
                      Full Name
                    </label>
                    <input
                      type="text"
                      placeholder="Display Name"
                      className="w-full px-3.5 py-2.5 bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 rounded-2xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-violet-500 transition-all select-text"
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
                        <User className="absolute left-3.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400" />
                        <input
                          type="text"
                          required
                          placeholder="username"
                          className="w-full pl-9 pr-3 py-2.5 bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 rounded-2xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-violet-500 transition-all select-text"
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
                        <Mail className="absolute left-3.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400" />
                        <input
                          type="email"
                          required
                          placeholder="name@email.com"
                          className="w-full pl-9 pr-3 py-2.5 bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 rounded-2xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-violet-500 transition-all select-text"
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
                      <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-400" />
                      <input
                        type={showPassword ? 'text' : 'password'}
                        required
                        placeholder="At least 6 characters"
                        className="w-full pl-9 pr-10 py-2.5 bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 rounded-2xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-violet-500 transition-all select-text"
                        value={regPassword}
                        onChange={(e) => setRegPassword(e.target.value)}
                      />
                      <button
                        type="button"
                        onClick={() => setShowPassword(!showPassword)}
                        className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 p-1"
                        aria-label="Toggle password visibility"
                      >
                        {showPassword ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                      </button>
                    </div>
                  </div>

                  <button
                    type="submit"
                    disabled={loading}
                    className="w-full bg-violet-600 hover:bg-violet-500 active:scale-[0.99] text-white font-semibold py-3 rounded-2xl shadow-md shadow-violet-600/20 transition-all mt-3 flex items-center justify-center gap-2 disabled:opacity-60"
                  >
                    {loading ? (
                      <div className="flex items-center gap-2">
                        <Loader2 className="h-4 w-4 animate-spin text-white" />
                        <span>Creating Account...</span>
                      </div>
                    ) : (
                      <>
                        <span>Create Account</span>
                        <ArrowRight className="h-4 w-4" />
                      </>
                    )}
                  </button>

                  <div className="pt-2 text-center">
                    <p className="text-xs text-slate-500 dark:text-slate-400">
                      Already have an account?{' '}
                      <button
                        type="button"
                        onClick={() => {
                          setActiveTab('login');
                          setError(null);
                          setSuccessMsg(null);
                        }}
                        className="font-semibold text-violet-600 dark:text-violet-400 hover:underline"
                      >
                        Sign in
                      </button>
                    </p>
                  </div>
                </form>
              )}

              {/* 3. FORGOT / RESET PASSWORD FLOW */}
              {activeTab === 'forgot' && (
                <div className="space-y-4">
                  {forgotStep === 'EMAIL' && (
                    <form onSubmit={handleRequestForgotOtp} className="space-y-4">
                      <div className="space-y-1.5">
                        <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">
                          Account Email Address
                        </label>
                        <div className="relative">
                          <Mail className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" />
                          <input
                            type="email"
                            required
                            placeholder="your.email@example.com"
                            className="w-full pl-10 pr-4 py-3 bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 rounded-2xl text-sm text-slate-900 dark:text-white outline-none focus:border-violet-500 select-text"
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
                          className="px-4 py-3 rounded-2xl border border-slate-200 dark:border-slate-700 text-xs font-semibold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 flex items-center gap-1.5 transition-colors"
                        >
                          <ArrowLeft className="w-4 h-4" /> Sign In
                        </button>
                        <button
                          type="submit"
                          disabled={loading}
                          className="flex-1 py-3 rounded-2xl bg-violet-600 hover:bg-violet-500 text-white text-xs font-semibold flex items-center justify-center gap-2 shadow-md shadow-violet-600/20 disabled:opacity-50 transition-colors"
                        >
                          {loading ? <Loader2 className="w-4 h-4 animate-spin text-white" /> : null}
                          Send Verification Code
                        </button>
                      </div>
                    </form>
                  )}

                  {forgotStep === 'OTP' && (
                    <form onSubmit={handleVerifyForgotOtp} className="space-y-4">
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
                            className="w-full px-4 py-3 tracking-widest text-center text-lg font-mono bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 rounded-2xl text-slate-900 dark:text-white outline-none focus:border-violet-500 select-text"
                            value={forgotOtp}
                            onChange={(e) => setForgotOtp(e.target.value)}
                          />
                          <KeyRound className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" />
                        </div>
                      </div>

                      <div className="flex items-center gap-2 pt-1">
                        <button
                          type="button"
                          onClick={() => setForgotStep('EMAIL')}
                          className="px-4 py-3 rounded-2xl border border-slate-200 dark:border-slate-700 text-xs font-semibold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                        >
                          Back
                        </button>
                        <button
                          type="submit"
                          disabled={loading}
                          className="flex-1 py-3 rounded-2xl bg-violet-600 hover:bg-violet-500 text-white text-xs font-semibold flex items-center justify-center gap-2 shadow-md shadow-violet-600/20 disabled:opacity-50 transition-colors"
                        >
                          {loading ? <Loader2 className="w-4 h-4 animate-spin text-white" /> : null}
                          Verify Code
                        </button>
                      </div>
                    </form>
                  )}

                  {forgotStep === 'PASSWORD' && (
                    <form onSubmit={handleResetPasswordSubmit} className="space-y-4">
                      <div className="space-y-1.5">
                        <label className="block text-xs font-semibold text-slate-700 dark:text-slate-300">
                          New Password
                        </label>
                        <div className="relative">
                          <Lock className="absolute left-3.5 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" />
                          <input
                            type={showPassword ? 'text' : 'password'}
                            required
                            placeholder="At least 6 characters"
                            className="w-full pl-10 pr-10 py-3 bg-slate-50 dark:bg-slate-800/60 border border-slate-200 dark:border-slate-700 rounded-2xl text-sm text-slate-900 dark:text-white outline-none focus:border-violet-500 select-text"
                            value={newPassword}
                            onChange={(e) => setNewPassword(e.target.value)}
                          />
                          <button
                            type="button"
                            onClick={() => setShowPassword(!showPassword)}
                            className="absolute right-3.5 top-1/2 -translate-y-1/2 text-slate-400"
                            aria-label="Toggle password visibility"
                          >
                            {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                          </button>
                        </div>
                      </div>

                      <button
                        type="submit"
                        disabled={loading}
                        className="w-full py-3 rounded-2xl bg-violet-600 hover:bg-violet-500 text-white text-xs font-semibold flex items-center justify-center gap-2 shadow-md shadow-violet-600/20 disabled:opacity-50 transition-colors"
                      >
                        {loading ? <Loader2 className="w-4 h-4 animate-spin text-white" /> : null}
                        Reset Password &amp; Sign In
                      </button>
                    </form>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      </main>

      {/* Clean Page Footer */}
      <footer className="w-full max-w-7xl mx-auto px-4 py-4 text-center text-xs text-slate-400 dark:text-slate-500 z-10">
        ConnectX &bull; End-to-End Encrypted Private Messaging
      </footer>
    </div>
  );
};

