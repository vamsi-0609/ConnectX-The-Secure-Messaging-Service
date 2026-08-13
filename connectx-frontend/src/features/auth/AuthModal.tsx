import React, { useState, useEffect } from 'react';
import {
  ShieldCheck,
  Shield,
  Mail,
  Lock,
  User,
  Eye,
  EyeOff,
  Sun,
  Moon,
  Sparkles,
  ArrowRight,
  AlertTriangle,
  CheckCircle2,
  LockKeyhole,
} from 'lucide-react';
import { authApi } from '../../api/authApi';
import { ensureLocalCryptoDevice } from '../../crypto/deviceSession';
import { applyTheme, isDarkTheme } from '../../utils/theme';
import { AuthResponse } from '../../types';

interface AuthModalProps {
  onSuccess: (authData: AuthResponse) => void;
}

export const AuthModal: React.FC<AuthModalProps> = ({ onSuccess }) => {
  const [activeTab, setActiveTab] = useState<'login' | 'register'>('login');
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

  useEffect(() => {
    applyTheme(isDarkMode);
  }, [isDarkMode]);

  // LOGIN SUBMIT: Verifies existing credentials from database
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
    } catch (err: any) {
      console.error('Login error:', err);
      setError('Invalid username/email or password.');
      setLoading(false);
    }
  };

  // REGISTER SUBMIT: Creates new user in database, redirects to Sign In
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
      // Registration successful: switch tab to Login page, pre-fill login field, display clean success message
      setSuccessMsg('Account created successfully! Please sign in with your password.');
      setLoginEmailOrUsername(regEmail.trim() || regUsername.trim());
      setLoginPassword('');
      setActiveTab('login');

      // Clear registration input state
      setRegUsername('');
      setRegEmail('');
      setRegDisplayName('');
      setRegPassword('');
    } catch (err: any) {
      console.error('Register error:', err);
      setLoading(false);
      const msg = err.message || '';

      if (msg.toLowerCase().includes('username is already taken') || msg.includes('USERNAME_EXISTS')) {
        setError('Username is already taken.');
      } else if (msg.toLowerCase().includes('email is already registered') || msg.includes('EMAIL_EXISTS')) {
        setError('Email address is already registered.');
      } else {
        setError(msg || 'Registration failed. Please check your details.');
      }
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
    <div className="min-h-screen bg-gradient-to-br from-indigo-50 via-purple-50 to-white dark:from-slate-950 dark:via-slate-900 dark:to-slate-950 flex flex-col justify-between relative overflow-hidden transition-colors duration-300">
      {/* Top Header */}
      <header className="px-8 py-5 flex justify-between items-center z-10">
        <div className="flex items-center gap-3">
          <div className="p-2.5 bg-indigo-600 rounded-2xl text-white shadow-lg shadow-indigo-600/30">
            <Shield className="h-6 w-6" />
          </div>
          <span className="font-extrabold text-2xl text-slate-900 dark:text-white tracking-tight">
            Connect<span className="text-indigo-600 dark:text-indigo-400">X</span>
          </span>
        </div>

        {/* Light / Dark Mode Toggle */}
        <button
          onClick={() => setIsDarkMode(!isDarkMode)}
          className="flex items-center gap-2 px-3.5 py-2 rounded-xl bg-white/80 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200 shadow-sm hover:shadow transition-all text-xs font-semibold"
        >
          {isDarkMode ? (
            <>
              <Sun className="h-4 w-4 text-amber-400" />
              <span>Light</span>
            </>
          ) : (
            <>
              <Moon className="h-4 w-4 text-indigo-600" />
              <span>Dark</span>
            </>
          )}
        </button>
      </header>

      {/* Main Authentication Card */}
      <main className="flex-1 flex items-center justify-center px-4 py-8 z-10">
        <div className="w-full max-w-md">
          <div className="shadow-2xl border border-slate-100 dark:border-slate-800 bg-white/95 dark:bg-slate-900/95 backdrop-blur-xl rounded-3xl p-8 space-y-6">
            {/* Card Header */}
            <div className="text-center space-y-2">
              <div className="mx-auto w-14 h-14 rounded-2xl bg-indigo-100 dark:bg-indigo-900/40 flex items-center justify-center text-indigo-600 dark:text-indigo-400 mb-3 shadow-inner">
                <ShieldCheck className="h-7 w-7" />
              </div>
              <h1 className="text-2xl font-bold text-slate-900 dark:text-white tracking-tight">
                {activeTab === 'login' ? 'Sign In to ConnectX' : 'Create Account'}
              </h1>
              <p className="text-sm text-slate-500 dark:text-slate-400">
                {activeTab === 'login'
                  ? 'Enter your credentials to access your secure messaging account'
                  : 'Join ConnectX to send secure end-to-end encrypted messages'}
              </p>
            </div>

            {/* Segmented Tabs */}
            <div className="grid grid-cols-2 p-1 bg-slate-100 dark:bg-slate-800/80 rounded-2xl">
              <button
                type="button"
                onClick={() => {
                  setActiveTab('login');
                  setError(null);
                  setSuccessMsg(null);
                }}
                className={`py-2 text-sm font-semibold rounded-xl transition-all ${
                  activeTab === 'login'
                    ? 'bg-white dark:bg-slate-700 text-indigo-600 dark:text-indigo-400 shadow-sm'
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
                className={`py-2 text-sm font-semibold rounded-xl transition-all ${
                  activeTab === 'register'
                    ? 'bg-white dark:bg-slate-700 text-indigo-600 dark:text-indigo-400 shadow-sm'
                    : 'text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-white'
                }`}
              >
                Create Account
              </button>
            </div>

            {/* Sleek Redesigned Error Alert Banner */}
            {error && (
              <div className="flex items-center gap-3 p-3.5 rounded-2xl bg-rose-500/10 dark:bg-rose-950/40 border border-rose-500/30 text-rose-600 dark:text-rose-300 text-xs font-semibold animate-pop-in shadow-sm">
                <div className="p-1.5 rounded-lg bg-rose-500/20 text-rose-500 flex-shrink-0">
                  <AlertTriangle className="h-4 w-4" />
                </div>
                <span className="leading-snug">{error}</span>
              </div>
            )}

            {/* Sleek Success Alert Banner */}
            {successMsg && (
              <div className="flex items-center gap-3 p-3.5 rounded-2xl bg-emerald-500/10 dark:bg-emerald-950/40 border border-emerald-500/30 text-emerald-600 dark:text-emerald-300 text-xs font-semibold animate-pop-in shadow-sm">
                <div className="p-1.5 rounded-lg bg-emerald-500/20 text-emerald-500 flex-shrink-0">
                  <CheckCircle2 className="h-4 w-4" />
                </div>
                <span className="leading-snug">{successMsg}</span>
              </div>
            )}

            {/* Form Content */}
            {activeTab === 'login' ? (
              /* SIGN IN FORM */
              <form onSubmit={handleLoginSubmit} className="space-y-4">
                <div className="space-y-2">
                  <label className="text-xs font-semibold uppercase tracking-wider text-slate-700 dark:text-slate-300">
                    Email Address or Username
                  </label>
                  <div className="relative">
                    <User className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
                    <input
                      type="text"
                      required
                      placeholder="Username or Email"
                      className="w-full pl-10 pr-4 py-3 bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 rounded-xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-indigo-500 dark:focus:border-indigo-400 focus:ring-2 focus:ring-indigo-500/20 transition-all"
                      value={loginEmailOrUsername}
                      onChange={(e) => setLoginEmailOrUsername(e.target.value)}
                    />
                  </div>
                </div>

                <div className="space-y-2">
                  <div className="flex justify-between items-center">
                    <label className="text-xs font-semibold uppercase tracking-wider text-slate-700 dark:text-slate-300">
                      Password
                    </label>
                    <button
                      type="button"
                      onClick={() => alert('Password reset link can be sent to your registered email.')}
                      className="text-xs font-medium text-indigo-600 dark:text-indigo-400 hover:underline"
                    >
                      Forgot Password?
                    </button>
                  </div>
                  <div className="relative">
                    <Lock className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
                    <input
                      type={showPassword ? 'text' : 'password'}
                      required
                      placeholder="Password"
                      className="w-full pl-10 pr-10 py-3 bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 rounded-xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-indigo-500 dark:focus:border-indigo-400 focus:ring-2 focus:ring-indigo-500/20 transition-all"
                      value={loginPassword}
                      onChange={(e) => setLoginPassword(e.target.value)}
                    />
                    <button
                      type="button"
                      onClick={() => setShowPassword(!showPassword)}
                      className="absolute right-3.5 top-3.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
                    >
                      {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                    </button>
                  </div>
                </div>

                <button
                  type="submit"
                  disabled={loading}
                  className="w-full bg-indigo-600 hover:bg-indigo-700 text-white font-semibold py-3 rounded-xl shadow-lg shadow-indigo-600/25 transition-all mt-2 flex items-center justify-center gap-2"
                >
                  {loading ? (
                    <div className="flex items-center gap-2">
                      <div className="h-4 w-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                      <span>Verifying Credentials...</span>
                    </div>
                  ) : (
                    <>
                      <span>Sign In</span>
                      <ArrowRight className="h-4 w-4" />
                    </>
                  )}
                </button>
              </form>
            ) : (
              /* CREATE ACCOUNT FORM */
              <form onSubmit={handleRegisterSubmit} className="space-y-3.5">
                <div className="space-y-1.5">
                  <label className="text-xs font-semibold uppercase tracking-wider text-slate-700 dark:text-slate-300">
                    Username
                  </label>
                  <div className="relative">
                    <User className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
                    <input
                      type="text"
                      required
                      placeholder="Username"
                      className="w-full pl-10 pr-4 py-2.5 bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 rounded-xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-indigo-500 transition-all"
                      value={regUsername}
                      onChange={(e) => setRegUsername(e.target.value)}
                    />
                  </div>
                </div>

                <div className="space-y-1.5">
                  <label className="text-xs font-semibold uppercase tracking-wider text-slate-700 dark:text-slate-300">
                    Email Address
                  </label>
                  <div className="relative">
                    <Mail className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
                    <input
                      type="email"
                      required
                      placeholder="Email Address"
                      className="w-full pl-10 pr-4 py-2.5 bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 rounded-xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-indigo-500 transition-all"
                      value={regEmail}
                      onChange={(e) => setRegEmail(e.target.value)}
                    />
                  </div>
                </div>

                <div className="space-y-1.5">
                  <label className="text-xs font-semibold uppercase tracking-wider text-slate-700 dark:text-slate-300">
                    Display Name (Optional)
                  </label>
                  <input
                    type="text"
                    placeholder="Display Name"
                    className="w-full px-4 py-2.5 bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 rounded-xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-indigo-500 transition-all"
                    value={regDisplayName}
                    onChange={(e) => setRegDisplayName(e.target.value)}
                  />
                </div>

                <div className="space-y-1.5">
                  <label className="text-xs font-semibold uppercase tracking-wider text-slate-700 dark:text-slate-300">
                    Password
                  </label>
                  <div className="relative">
                    <Lock className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
                    <input
                      type={showPassword ? 'text' : 'password'}
                      required
                      placeholder="Password"
                      className="w-full pl-10 pr-10 py-2.5 bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 rounded-xl text-sm text-slate-900 dark:text-white placeholder-slate-400 outline-none focus:border-indigo-500 transition-all"
                      value={regPassword}
                      onChange={(e) => setRegPassword(e.target.value)}
                    />
                    <button
                      type="button"
                      onClick={() => setShowPassword(!showPassword)}
                      className="absolute right-3.5 top-3.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
                    >
                      {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                    </button>
                  </div>
                </div>

                <button
                  type="submit"
                  disabled={loading}
                  className="w-full bg-indigo-600 hover:bg-indigo-700 text-white font-semibold py-3 rounded-xl shadow-lg shadow-indigo-600/25 transition-all mt-3 flex items-center justify-center gap-2"
                >
                  {loading ? (
                    <div className="flex items-center gap-2">
                      <div className="h-4 w-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                      <span>Creating Account...</span>
                    </div>
                  ) : (
                    <>
                      <Sparkles className="h-4 w-4" />
                      <span>Create Account</span>
                    </>
                  )}
                </button>
              </form>
            )}

            {/* Card Footer Info Banner */}
            <div className="pt-4 border-t border-slate-100 dark:border-slate-800 text-center text-xs space-y-1">
              <div className="flex items-center justify-center gap-1.5 text-indigo-600 dark:text-indigo-400 font-medium">
                <LockKeyhole className="h-3.5 w-3.5" />
                <span>End-to-End Encrypted Session</span>
              </div>
            </div>
          </div>
        </div>
      </main>

      {/* Page Footer */}
      <footer className="py-4 text-center text-xs text-slate-400 dark:text-slate-500 z-10 font-mono">
        ConnectX © 2026 — Secure Messaging
      </footer>
    </div>
  );
};
