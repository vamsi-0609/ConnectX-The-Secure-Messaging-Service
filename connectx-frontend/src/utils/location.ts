import { reverseGeocodeWithGoogle } from './googleMaps';

export interface CurrentLocationResult {
  latitude: number;
  longitude: number;
  accuracy?: number;
  locationLabel?: string;
}

export function getCurrentLocation(): Promise<GeolocationPosition> {
  return new Promise((resolve, reject) => {
    if (!navigator.geolocation) {
      reject(new Error('Geolocation is not supported in this browser.'));
      return;
    }

    navigator.geolocation.getCurrentPosition(resolve, reject, {
      enableHighAccuracy: true,
      timeout: 15000,
      maximumAge: 0,
    });
  });
}

export async function resolveCurrentLocation(): Promise<CurrentLocationResult> {
  const position = await getCurrentLocation();
  const latitude = position.coords.latitude;
  const longitude = position.coords.longitude;

  let locationLabel: string | undefined;
  try {
    locationLabel = (await reverseGeocodeWithGoogle(latitude, longitude)) ?? undefined;
  } catch {
    locationLabel = undefined;
  }

  return {
    latitude,
    longitude,
    accuracy: position.coords.accuracy,
    locationLabel,
  };
}

export function geolocationErrorMessage(error: unknown): string {
  if (error instanceof GeolocationPositionError) {
    switch (error.code) {
      case error.PERMISSION_DENIED:
        return 'Location permission denied. Allow location access and try again.';
      case error.POSITION_UNAVAILABLE:
        return 'Location information is unavailable.';
      case error.TIMEOUT:
        return 'Location request timed out.';
      default:
        return 'Unable to get your location.';
    }
  }

  if (error instanceof Error) {
    return error.message;
  }

  return 'Unable to get your location.';
}
