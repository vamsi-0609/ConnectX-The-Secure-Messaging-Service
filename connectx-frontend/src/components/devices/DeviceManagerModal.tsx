import React, { useEffect, useState } from 'react';
import { Laptop, X, Trash2, Key, Check, Copy, RefreshCw, ShieldAlert, RotateCcw } from 'lucide-react';
import { deviceApi } from '../../api/deviceApi';
import { keyManager } from '../../crypto/keyManager';
import { ensureLocalCryptoDevice } from '../../crypto/deviceSession';
import { Device, User } from '../../types';

interface DeviceManagerModalProps {
  onClose: () => void;
  currentUser?: User | null;
}

export const DeviceManagerModal: React.FC<DeviceManagerModalProps> = ({ onClose, currentUser }) => {
  const [devices, setDevices] = useState<Device[]>([]);
  const [loading, setLoading] = useState(true);
  const [currentDeviceId, setCurrentDeviceId] = useState<number | null>(null);
  const [copiedId, setCopiedId] = useState<number | null>(null);
  const [resetting, setResetting] = useState(false);

  const loadDevices = async () => {
    setLoading(true);
    try {
      if (currentUser) {
        const localDev = await keyManager.getLocalDevice(currentUser.id);
        if (localDev) setCurrentDeviceId(localDev.deviceId);
      }
      const list = await deviceApi.getMyDevices();
      setDevices(list);
    } catch (err) {
      console.error('Failed to fetch user devices:', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDevices();
  }, []);

  const handleCopyKey = (id: number, key: string) => {
    navigator.clipboard.writeText(key);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  const handleDeactivateDevice = async (id: number) => {
    if (!confirm('Are you sure you want to revoke this endpoint? It will no longer receive encrypted messages.')) return;
    try {
      await deviceApi.deactivateDevice(id);
      loadDevices();
    } catch (err: any) {
      alert('Failed to revoke device: ' + err.message);
    }
  };

  const handleDeactivateOtherDevices = async () => {
    if (!currentDeviceId) return;
    if (!confirm('Deactivate all other active devices except this device?')) return;
    const others = devices.filter((d) => d.active && d.id !== currentDeviceId);
    for (const dev of others) {
      await deviceApi.deactivateDevice(dev.id).catch(() => {});
    }
    loadDevices();
  };

  const handleResetKeys = async () => {
    if (!currentUser) return;
    if (!confirm('This will wipe local E2EE keys on this browser and generate a fresh keypair & device registration. Continue?')) return;
    setResetting(true);
    try {
      await keyManager.clearKeys();
      await ensureLocalCryptoDevice(currentUser);
      await loadDevices();
      alert('E2EE Keys successfully reset! Fresh device registered.');
    } catch (err: any) {
      alert('Failed to reset keys: ' + err.message);
    } finally {
      setResetting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 backdrop-blur-sm p-4 animate-pop-in">
      <div className="w-full max-w-lg bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-3xl shadow-2xl p-6 space-y-5 text-slate-900 dark:text-white">
        {/* Modal Header */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-2xl bg-pink-600/10 dark:bg-pink-600/20 text-pink-600 dark:text-pink-400">
              <Laptop className="w-6 h-6" />
            </div>
            <div>
              <h2 className="text-lg font-bold">Cryptographic Device Vault</h2>
              <p className="text-xs text-slate-500 dark:text-slate-400">Active E2EE endpoints registered for your ConnectX account</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 text-slate-400 hover:text-slate-700 dark:hover:text-white hover:bg-slate-100 dark:hover:bg-slate-800 rounded-xl transition-all"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Action Controls */}
        <div className="flex items-center justify-between gap-2 pt-1 border-b border-slate-200 dark:border-slate-800 pb-3">
          <button
            onClick={handleResetKeys}
            disabled={resetting}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-pink-600 dark:text-pink-400 bg-pink-500/10 hover:bg-pink-500/20 rounded-xl transition-all"
          >
            <RotateCcw className={`w-3.5 h-3.5 ${resetting ? 'animate-spin' : ''}`} />
            <span>Reset Key Vault</span>
          </button>

          {devices.filter((d) => d.active && d.id !== currentDeviceId).length > 0 && (
            <button
              onClick={handleDeactivateOtherDevices}
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-rose-500 bg-rose-500/10 hover:bg-rose-500/20 rounded-xl transition-all"
            >
              <ShieldAlert className="w-3.5 h-3.5" />
              <span>Clean Stale Devices</span>
            </button>
          )}
        </div>

        {/* Devices Feed */}
        <div className="max-h-80 overflow-y-auto space-y-3 pt-1">
          {loading ? (
            <div className="flex items-center justify-center py-10 text-xs text-slate-400 gap-2">
              <RefreshCw className="w-4 h-4 animate-spin" />
              <span>Fetching cryptographic keys...</span>
            </div>
          ) : devices.length === 0 ? (
            <div className="text-center py-10 text-xs text-slate-400">No active devices registered.</div>
          ) : (
            devices.map((device) => {
              const isCurrentDevice = device.id === currentDeviceId;
              return (
                <div
                  key={device.id}
                  className={`p-4 rounded-2xl space-y-3 shadow-sm border transition-all ${
                    isCurrentDevice
                      ? 'bg-indigo-50/70 dark:bg-indigo-950/30 border-indigo-300 dark:border-indigo-700/60'
                      : 'bg-slate-50 dark:bg-slate-800/60 border-slate-200 dark:border-slate-700/80'
                  }`}
                >
                  <div className="flex items-start justify-between">
                    <div className="flex items-center gap-2.5">
                      <Laptop className="w-4 h-4 text-indigo-600 dark:text-indigo-400" />
                      <div>
                        <h4 className="text-sm font-bold text-slate-900 dark:text-white flex items-center gap-2">
                          {device.deviceName}
                          {isCurrentDevice ? (
                            <span className="px-2 py-0.5 rounded-full bg-indigo-500/20 text-indigo-600 dark:text-indigo-300 text-[10px] font-extrabold border border-indigo-400/30">
                              THIS DEVICE
                            </span>
                          ) : (
                            device.active && (
                              <span className="px-2 py-0.5 rounded-full bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 text-[10px] font-bold">
                                ACTIVE E2EE
                              </span>
                            )
                          )}
                        </h4>
                        <div className="flex items-center gap-2 text-[11px] text-pink-600 dark:text-pink-400 font-mono mt-0.5">
                          <Key className="w-3 h-3" />
                          <span>{device.keyAlgorithm} • Device #{device.id}</span>
                        </div>
                      </div>
                    </div>

                    {device.active && (
                      <button
                        onClick={() => handleDeactivateDevice(device.id)}
                        className="p-2 text-rose-500 hover:text-rose-600 hover:bg-rose-500/10 rounded-xl transition-all"
                        title="Revoke Endpoint Key"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    )}
                  </div>

                  {/* Public Key Display Box */}
                  <div className="bg-slate-100 dark:bg-slate-950 p-3 rounded-xl border border-slate-200 dark:border-slate-800 font-mono text-[11px] space-y-1">
                    <div className="flex items-center justify-between text-slate-500 dark:text-slate-400 text-[10px] uppercase font-bold">
                      <span>Public Key Payload</span>
                      <button
                        onClick={() => handleCopyKey(device.id, device.publicKey)}
                        className="flex items-center gap-1 text-indigo-600 dark:text-indigo-400 hover:underline"
                      >
                        {copiedId === device.id ? (
                          <>
                            <Check className="w-3 h-3 text-emerald-500" />
                            <span className="text-emerald-500">Copied</span>
                          </>
                        ) : (
                          <>
                            <Copy className="w-3 h-3" />
                            <span>Copy</span>
                          </>
                        )}
                      </button>
                    </div>
                    <div className="break-all text-slate-700 dark:text-slate-300 line-clamp-2 select-all">
                      {device.publicKey}
                    </div>
                  </div>
                </div>
              );
            })
          )}
        </div>
      </div>
    </div>
  );
};

