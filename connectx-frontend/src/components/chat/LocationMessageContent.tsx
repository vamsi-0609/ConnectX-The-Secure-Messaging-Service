import React from 'react';
import { MapPin, Navigation } from 'lucide-react';
import {
  getGoogleMapsLink,
  getGoogleStaticMapUrl,
  getLocationDisplayTitle,
} from '../../utils/googleMaps';

interface LocationMessageContentProps {
  latitude?: number;
  longitude?: number;
  locationLabel?: string;
  isSelf: boolean;
}

export const LocationMessageContent: React.FC<LocationMessageContentProps> = ({
  latitude,
  longitude,
  locationLabel,
  isSelf,
}) => {
  if (latitude == null || longitude == null) {
    return (
      <p className={`text-xs ${isSelf ? 'text-indigo-100' : 'text-slate-300'}`}>
        Location unavailable
      </p>
    );
  }

  const staticMapUrl = getGoogleStaticMapUrl(latitude, longitude);
  const mapsLink = getGoogleMapsLink(latitude, longitude);
  const title = getLocationDisplayTitle(locationLabel);

  return (
    <a
      href={mapsLink}
      target="_blank"
      rel="noopener noreferrer"
      className="group block w-[min(100%,288px)] overflow-hidden rounded-2xl border border-white/10 bg-slate-950/20 shadow-md transition-all hover:shadow-lg hover:border-white/20"
      aria-label={`Open ${title} in Google Maps`}
    >
      <div className="relative h-44 w-full overflow-hidden">
        {staticMapUrl ? (
          <img
            src={staticMapUrl}
            alt={title}
            className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-[1.02]"
            loading="lazy"
          />
        ) : (
          <div className="absolute inset-0 bg-gradient-to-br from-slate-600 via-slate-700 to-slate-900">
            <div
              className="absolute inset-0 opacity-[0.18]"
              style={{
                backgroundImage:
                  'linear-gradient(rgba(255,255,255,0.35) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,0.35) 1px, transparent 1px)',
                backgroundSize: '28px 28px',
              }}
            />
            <div className="absolute inset-0 bg-[radial-gradient(circle_at_center,transparent_0%,rgba(15,23,42,0.35)_100%)]" />
          </div>
        )}

        <div className="pointer-events-none absolute inset-0 bg-gradient-to-t from-black/75 via-black/15 to-black/5" />

        <div className="pointer-events-none absolute left-1/2 top-[46%] -translate-x-1/2 -translate-y-full">
          <div className="relative flex flex-col items-center">
            <span className="mb-1 rounded-full bg-rose-500 p-2 shadow-lg shadow-rose-900/40 ring-2 ring-white/90">
              <MapPin className="h-4 w-4 text-white fill-white" />
            </span>
            <span className="h-2 w-2 rounded-full bg-rose-500/70 blur-[2px]" />
          </div>
        </div>

        <div className="absolute inset-x-0 bottom-0 p-3">
          <p className="truncate text-sm font-semibold text-white">{title}</p>
          <p className="mt-0.5 text-[11px] text-white/75">Static location</p>
        </div>
      </div>

      <div
        className={`flex items-center justify-between gap-2 px-3 py-2.5 text-xs ${
          isSelf ? 'bg-indigo-950/20 text-indigo-50' : 'bg-slate-900/50 text-slate-100'
        }`}
      >
        <span className="font-medium">Open in Google Maps</span>
        <Navigation className="h-3.5 w-3.5 opacity-80 transition-transform group-hover:translate-x-0.5" />
      </div>
    </a>
  );
};
