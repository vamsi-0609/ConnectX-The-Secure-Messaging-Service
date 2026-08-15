import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Camera, X, RotateCcw, Send, Loader2, SwitchCamera } from 'lucide-react';

interface CameraCaptureModalProps {
  open: boolean;
  onClose: () => void;
  onCaptureSend: (file: File) => Promise<void>;
  busy?: boolean;
}

type CameraStep = 'live' | 'preview';

export const CameraCaptureModal: React.FC<CameraCaptureModalProps> = ({
  open,
  onClose,
  onCaptureSend,
  busy = false,
}) => {
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const streamRef = useRef<MediaStream | null>(null);

  const [step, setStep] = useState<CameraStep>('live');
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [capturedFile, setCapturedFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [facingMode, setFacingMode] = useState<'user' | 'environment'>('environment');

  const stopCamera = useCallback(() => {
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    }
    if (videoRef.current) {
      videoRef.current.srcObject = null;
    }
  }, []);

  const clearPreview = useCallback(() => {
    if (previewUrl) {
      URL.revokeObjectURL(previewUrl);
    }
    setPreviewUrl(null);
    setCapturedFile(null);
    setStep('live');
  }, [previewUrl]);

  const startCamera = useCallback(async () => {
    stopCamera();
    setError(null);
    setStarting(true);

    try {
      if (!navigator.mediaDevices?.getUserMedia) {
        throw new Error('Camera is not supported in this browser.');
      }

      const stream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: { ideal: facingMode },
          width: { ideal: 1920 },
          height: { ideal: 1080 },
        },
        audio: false,
      });

      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        await videoRef.current.play();
      }
    } catch (err: unknown) {
      const message =
        err instanceof Error
          ? err.message
          : 'Unable to access the camera. Check permissions and try again.';
      setError(message);
    } finally {
      setStarting(false);
    }
  }, [facingMode, stopCamera]);

  useEffect(() => {
    if (!open) {
      stopCamera();
      clearPreview();
      setError(null);
      setStep('live');
      return;
    }

    startCamera();
    return () => {
      stopCamera();
    };
  }, [open, facingMode]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    return () => {
      stopCamera();
      if (previewUrl) {
        URL.revokeObjectURL(previewUrl);
      }
    };
  }, [previewUrl, stopCamera]);

  const handleCapture = () => {
    const video = videoRef.current;
    const canvas = canvasRef.current;
    if (!video || !canvas || !video.videoWidth || !video.videoHeight) {
      setError('Camera is not ready yet. Please wait a moment.');
      return;
    }

    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    const context = canvas.getContext('2d');
    if (!context) {
      setError('Unable to capture photo.');
      return;
    }

    context.drawImage(video, 0, 0, canvas.width, canvas.height);
    canvas.toBlob(
      (blob) => {
        if (!blob) {
          setError('Failed to capture photo.');
          return;
        }

        stopCamera();
        const file = new File([blob], `camera-${Date.now()}.jpg`, { type: 'image/jpeg' });
        const objectUrl = URL.createObjectURL(blob);
        setCapturedFile(file);
        setPreviewUrl(objectUrl);
        setStep('preview');
        setError(null);
      },
      'image/jpeg',
      0.92
    );
  };

  const handleRetake = () => {
    clearPreview();
    startCamera();
  };

  const handleSend = async () => {
    if (!capturedFile || busy) return;
    try {
      await onCaptureSend(capturedFile);
      onClose();
    } catch {
      // Parent handles error display.
    }
  };

  const toggleFacingMode = () => {
    setFacingMode((prev) => (prev === 'environment' ? 'user' : 'environment'));
  };

  if (!open) {
    return null;
  }

  return (
    <div className="fixed inset-0 z-[70] flex items-center justify-center bg-black/70 backdrop-blur-sm p-3 sm:p-6 select-none">
      <div className="w-full max-w-lg bg-slate-950 border border-slate-800 rounded-2xl shadow-2xl overflow-hidden">
        <div className="flex items-center justify-between px-4 py-3 border-b border-slate-800">
          <div className="flex items-center gap-2 text-white">
            <Camera className="w-5 h-5 text-indigo-400" />
            <h3 className="font-semibold text-sm sm:text-base">Camera</h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={busy}
            className="p-2 text-slate-400 hover:text-white rounded-lg hover:bg-slate-800 transition-colors disabled:opacity-50"
            aria-label="Close camera"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="relative bg-black aspect-[4/3] sm:aspect-video">
          {step === 'live' ? (
            <>
              <video
                ref={videoRef}
                autoPlay
                playsInline
                muted
                className="w-full h-full object-cover"
              />
              {(starting || error) && (
                <div className="absolute inset-0 flex items-center justify-center bg-black/60 px-6 text-center">
                  {starting ? (
                    <div className="flex flex-col items-center gap-2 text-slate-200 text-sm">
                      <Loader2 className="w-6 h-6 animate-spin text-indigo-400" />
                      Starting camera...
                    </div>
                  ) : (
                    <p className="text-sm text-rose-300">{error}</p>
                  )}
                </div>
              )}
            </>
          ) : (
            previewUrl && (
              <img src={previewUrl} alt="Captured preview" className="w-full h-full object-contain bg-black" />
            )
          )}
          <canvas ref={canvasRef} className="hidden" />
        </div>

        <div className="px-4 py-4 border-t border-slate-800">
          {error && step === 'live' && !starting && (
            <button
              type="button"
              onClick={startCamera}
              className="w-full mb-3 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-sm font-medium"
            >
              Retry camera
            </button>
          )}

          {step === 'live' ? (
            <div className="flex items-center justify-between gap-3">
              <button
                type="button"
                onClick={toggleFacingMode}
                disabled={busy || starting}
                className="p-3 rounded-full bg-slate-800 hover:bg-slate-700 text-slate-200 disabled:opacity-50"
                aria-label="Switch camera"
              >
                <SwitchCamera className="w-5 h-5" />
              </button>

              <button
                type="button"
                onClick={handleCapture}
                disabled={busy || starting || !!error}
                className="w-16 h-16 rounded-full bg-white border-4 border-indigo-500 hover:scale-105 transition-transform disabled:opacity-50 flex items-center justify-center"
                aria-label="Capture photo"
              >
                <span className="w-12 h-12 rounded-full bg-indigo-500" />
              </button>

              <div className="w-11" aria-hidden="true" />
            </div>
          ) : (
            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={handleRetake}
                disabled={busy}
                className="flex-1 py-3 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-sm font-semibold inline-flex items-center justify-center gap-2 disabled:opacity-50"
              >
                <RotateCcw className="w-4 h-4" />
                Retake
              </button>
              <button
                type="button"
                onClick={handleSend}
                disabled={busy || !capturedFile}
                className="flex-1 py-3 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white text-sm font-semibold inline-flex items-center justify-center gap-2 disabled:opacity-50"
              >
                {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
                Send Photo
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};
