import { config } from '../config/environment';

export function formatCoordinates(latitude: number, longitude: number): string {
  return `${latitude.toFixed(5)}, ${longitude.toFixed(5)}`;
}

export function getGoogleMapsLink(latitude: number, longitude: number): string {
  return `https://www.google.com/maps?q=${latitude},${longitude}`;
}

export function getGoogleStaticMapUrl(latitude: number, longitude: number, width = 640, height = 320): string | null {
  const apiKey = config.googleMapsApiKey;
  if (!apiKey) {
    return null;
  }

  const center = `${latitude},${longitude}`;
  const params = new URLSearchParams({
    center,
    zoom: '16',
    size: `${width}x${height}`,
    scale: '2',
    maptype: 'roadmap',
    key: apiKey,
  });

  return `https://maps.googleapis.com/maps/api/staticmap?${params.toString()}`;
}

export async function reverseGeocodeWithGoogle(latitude: number, longitude: number): Promise<string | null> {
  const apiKey = config.googleMapsApiKey;
  if (!apiKey) {
    return null;
  }

  const params = new URLSearchParams({
    latlng: `${latitude},${longitude}`,
    key: apiKey,
  });

  const response = await fetch(`https://maps.googleapis.com/maps/api/geocode/json?${params.toString()}`);
  if (!response.ok) {
    return null;
  }

  const data = (await response.json()) as {
    status?: string;
    results?: Array<{ formatted_address?: string }>;
  };

  if (data.status !== 'OK' || !data.results?.length) {
    return null;
  }

  return data.results[0].formatted_address ?? null;
}

export function getLocationDisplayTitle(locationLabel?: string): string {
  const trimmed = locationLabel?.trim();
  return trimmed || 'Shared location';
}

export function getLocationPreviewText(locationLabel?: string): string {
  return getLocationDisplayTitle(locationLabel);
}
