const geocodeCache = new Map();

const toNumberOrNull = (value) => {
  if (value === null || value === undefined || value === '') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
};

export const hasCoordinates = (latitude, longitude) => {
  return Number.isFinite(toNumberOrNull(latitude)) && Number.isFinite(toNumberOrNull(longitude));
};

export const toLatLng = (latitude, longitude) => {
  const lat = toNumberOrNull(latitude);
  const lng = toNumberOrNull(longitude);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
    return null;
  }
  return [lat, lng];
};

export const geocodeAddress = async (address) => {
  const cleaned = (address || '').trim();
  if (!cleaned) return null;

  const key = cleaned.toLowerCase();
  if (geocodeCache.has(key)) {
    return geocodeCache.get(key);
  }

  const query = encodeURIComponent(cleaned);
  const response = await fetch(
    `https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=${query}`,
    {
      headers: {
        Accept: 'application/json',
      },
    }
  );

  if (!response.ok) {
    return null;
  }

  const payload = await response.json();
  const first = payload?.[0];
  if (!first) {
    return null;
  }

  const latLng = toLatLng(first.lat, first.lon);
  if (latLng) {
    geocodeCache.set(key, latLng);
  }
  return latLng;
};

export const resolveRequestDestination = async (request) => {
  if (!request) return null;

  const deliveryLatLng = toLatLng(request.deliveryLatitude, request.deliveryLongitude);
  if (deliveryLatLng) return deliveryLatLng;

  const requesterLatLng = toLatLng(request.requester?.latitude, request.requester?.longitude);
  if (requesterLatLng) return requesterLatLng;

  const deliveryFromAddress = await geocodeAddress(request.deliveryAddress);
  if (deliveryFromAddress) return deliveryFromAddress;

  const requesterAddressLocation = await geocodeAddress(request.requester?.address);
  if (requesterAddressLocation) return requesterAddressLocation;

  const donationLatLng = toLatLng(request.donation?.latitude, request.donation?.longitude);
  if (donationLatLng) return donationLatLng;

  return geocodeAddress(request.pickupAddress || request.donation?.address);
};

export const resolvePickupLocation = async (request) => {
  if (!request) return null;

  const donationLatLng = toLatLng(request.donation?.latitude, request.donation?.longitude);
  if (donationLatLng) return donationLatLng;

  return geocodeAddress(request.pickupAddress || request.donation?.address);
};
