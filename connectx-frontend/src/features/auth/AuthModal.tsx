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
  LockKeyhole,
  KeyRound,
  ArrowLeft,
  Loader2,
} from 'lucide-react';
import { authApi } from '../../api/authApi';
import { ensureLocalCryptoDevice } from '../../crypto/deviceSession';
import { applyTheme, isDarkTheme } from '../../utils/theme';
import { AuthResponse } from '../../types';
import { ConnectXLogo } from '../../components/common/ConnectXLogo';

interface AuthModalProps {
  onSuccess: (authData: AuthResponse) => void;
}

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
    } catch (err: any) {
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
      setSuccessMsg('Account created successfully! Please sign in with your password.');
      setLoginEmailOrUsername(regEmail.trim() || regUsername.trim());
      setLoginPassword('');
      setActiveTab('login');

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
    } catch (err: any) {
      setLoading(false);
      setError(err.message || 'Failed to send verification code.');
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
      setSuccessMsg('Verification successful! Set your new password below.');
    } catch (err: any) {
      setLoading(false);
      setError(err.message || 'Invalid verification code.');
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
    } catch (err: any) {
      setLoading(false);
      setError(err.message || 'Failed to reset password.');
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
    <div className="min-h-dvh bg-gradient-to-br from-indigo-50 via-purple-50 to-white dark:from-slate-950 dark:via-slate-900 dark:to-slate-950 flex flex-col relative overflow-y-auto transition-colors duration-300">
      {/* Top Header */}
      <header className="px-8 py-5 flex justify-between items-center z-10">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center">
            <ConnectXLogo size="lg" variant="gradient" static />
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
              <div className="mx-auto w-14 h-14 rounded-2xl bg-gradient-to-tr from-indigo-600/15 to-violet-600/15 dark:from-indigo-600/25 dark:to-violet-600/25 border border-indigo-500/20 flex items-center justify-center mb-3">
                {activeTab === 'forgot' ? <KeyRound className="h-7 w-7 text-indigo-500" /> : <ConnectXLogo size="lg" variant="gradient" static />}
              </div>
              <h1 className="text-2xl font-bold text-slate-900 dark:text-white tracking-tight">
                {activeTab === 'login'
                  ? 'Sign In to ConnectX'
                  : activeTab === 'register'
                  ? 'Create Account'
                  : 'Reset Password'}
              </h1>
              <p className="text-sm text-slate-500 dark:text-slate-400">
                {activeTab === 'login'
                  ? 'Enter your credentials to access your secure messaging account'
                  : activeTab === 'register'
                  ? 'Join ConnectX to send secure end-to-end encrypted messages'
                  : 'Follow the steps to recover your account with an email verification code'}
              </p>
            </div>

            {/* Segmented Tabs */}
            {activeTab !== 'forgot' && (
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
            )}

            {/* Error Alert Banner */}
            {error && (
              <div className="flex items-center gap-3 p-3.5 rounded-2xl bg-rose-500/10 dark:bg-rose-950/40 border border-rose-500/30 text-rose-600 dark:text-rose-300 text-xs font-semibold animate-pop-in shadow-sm">
                <div className="p-1.5 rounded-lg bg-rose-500/20 text-rose-500 flex-shrink-0">
                  <AlertTriangle className="h-4 w-4" />
                </div>
                <span className="leading-snug">{error}</span>
              </div>
            )}

            {/* Success Alert Banner */}
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
                      onClick={() => {
                        setActiveTab('forgot');
                        setForgotStep('EMAIL');
                        setError(null);
                        setSuccessMsg(null);
                      }}
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
                      <Loader2 className="h-4 w-4 animate-spin text-white" />
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
            ) : activeTab === 'register' ? (
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
                      <Loader2 className="h-4 w-4 animate-spin text-white" />
                      <span>Creating Account...</span>
                    </div>
                  ) : (
                    <>
                      <ArrowRight className="h-4 w-4" />
                      <span>Create Account</span>
                    </>
                  )}
                </button>
              </form>
            ) : (
              /* FORGOT PASSWORD FORM */
              <div className="space-y-4">
                {forgotStep === 'EMAIL' && (
                  <form onSubmit={handleRequestForgotOtp} className="space-y-4">
                    <div className="space-y-2">
                      <label className="text-xs font-semibold uppercase tracking-wider text-slate-700 dark:text-slate-300">
                        Account Email Address
                      </label>
                      <div className="relative">
                        <Mail className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
                        <input
                          type="email"
                          required
                          placeholder="your.email@example.com"
                          className="w-full pl-10 pr-4 py-3 bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 rounded-xl text-sm text-slate-900 dark:text-white outline-none focus:border-indigo-500"
                          value={forgotEmail}
                          onChange={(e) => setForgotEmail(e.target.value)}
                        />
                      </div>
                    </div>

                    <div className="flex items-center gap-2 pt-2">
                      <button
                        type="button"
                        onClick={() => {
                          setActiveTab('login');
                          setError(null);
                          setSuccessMsg(null);
                        }}
                        className="px-4 py-3 rounded-xl border border-slate-200 dark:border-slate-700 text-xs font-semibold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 flex items-center gap-1.5"
                      >
                        <ArrowLeft className="w-4 h-4" /> Back to Sign In
                      </button>
                      <button
                        type="submit"
                        disabled={loading}
                        className="flex-1 py-3 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold flex items-center justify-center gap-2 shadow-lg shadow-indigo-600/25 disabled:opacity-50"
                      >
                        {loading ? <Loader2 className="w-4 h-4 animate-spin text-white" /> : null}
                        Send Verification Code
                      </button>
                    </div>
                  </form>
                )}

                {forgotStep === 'OTP' && (
                  <form onSubmit={handleVerifyForgotOtp} className="space-y-4">
                    <div className="space-y-2">
                      <label className="text-xs font-semibold uppercase tracking-wider text-slate-700 dark:text-slate-300">
                        6-Digit Verification Code
                      </label>
                      <div className="relative">
                        <KeyRound className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
                        <input
                          type="text"
                          required
                          maxLength={6}
                          placeholder="123456"
                          className="w-full pl-10 pr-4 py-3 tracking-widest text-center text-lg font-mono bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 rounded-xl text-slate-900 dark:text-white outline-none focus:border-indigo-500"
                          value={forgotOtp}
                          onChange={(e) => setForgotOtp(e.target.value)}
                        />
                      </div>
                    </div>

                    <div className="flex items-center gap-2 pt-2">
                      <button
                        type="button"
                        onClick={() => setForgotStep('EMAIL')}
                        className="px-4 py-3 rounded-xl border border-slate-200 dark:border-slate-700 text-xs font-semibold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800"
                      >
                        Back
                      </button>
                      <button
                        type="submit"
                        disabled={loading}
                        className="flex-1 py-3 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold flex items-center justify-center gap-2 shadow-lg shadow-indigo-600/25 disabled:opacity-50"
                      >
                        {loading ? <Loader2 className="w-4 h-4 animate-spin text-white" /> : null}
                        Verify Code
                      </button>
                    </div>
                  </form>
                )}

                {forgotStep === 'PASSWORD' && (
                  <form onSubmit={handleResetPasswordSubmit} className="space-y-4">
                    <div className="space-y-2">
                      <label className="text-xs font-semibold uppercase tracking-wider text-slate-700 dark:text-slate-300">
                        New Password
                      </label>
                      <div className="relative">
                        <Lock className="absolute left-3.5 top-3.5 h-4 w-4 text-slate-400" />
                        <input
                          type={showPassword ? 'text' : 'password'}
                          required
                          placeholder="New Password"
                          className="w-full pl-10 pr-10 py-3 bg-slate-50 dark:bg-slate-800/80 border border-slate-200 dark:border-slate-700 rounded-xl text-sm text-slate-900 dark:text-white outline-none focus:border-indigo-500"
                          value={newPassword}
                          onChange={(e) => setNewPassword(e.target.value)}
                        />
                        <button
                          type="button"
                          onClick={() => setShowPassword(!showPassword)}
                          className="absolute right-3.5 top-3.5 text-slate-400"
                        >
                          {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                        </button>
                      </div>
                    </div>

                    <button
                      type="submit"
                      disabled={loading}
                      className="w-full py-3 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-semibold flex items-center justify-center gap-2 shadow-lg shadow-indigo-600/25 disabled:opacity-50"
                    >
                      {loading ? <Loader2 className="w-4 h-4 animate-spin text-white" /> : null}
                      Reset Password & Sign In
                    </button>
                  </form>
                )}
              </div>
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
      <footer className="mt-auto py-4 text-center text-xs text-slate-400 dark:text-slate-500 z-10 font-mono">
        ConnectX © 2026 — Secure Messaging
      </footer>
    </div>
  );
};
