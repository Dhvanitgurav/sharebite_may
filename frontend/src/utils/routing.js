const routeCache = new Map();

const buildRouteKey = (from, to) => {
  if (!from || !to) return null;
  return `${from[0].toFixed(5)},${from[1].toFixed(5)}->${to[0].toFixed(5)},${to[1].toFixed(5)}`;
};

export const formatDistance = (meters) => {
  if (!Number.isFinite(meters)) return '--';
  if (meters < 1000) {
    return `${Math.round(meters)} m`;
  }
  return `${(meters / 1000).toFixed(1)} km`;
};

export const formatDuration = (seconds) => {
  if (!Number.isFinite(seconds)) return '--';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  const remaining = minutes % 60;
  return `${hours}h ${remaining}m`;
};

export const getRoute = async (from, to) => {
  if (!from || !to) return null;
  const key = buildRouteKey(from, to);
  if (key && routeCache.has(key)) {
    return routeCache.get(key);
  }

  const fromLng = from[1];
  const fromLat = from[0];
  const toLng = to[1];
  const toLat = to[0];

  const endpoint = `https://router.project-osrm.org/route/v1/driving/${fromLng},${fromLat};${toLng},${toLat}?overview=full&geometries=geojson`;
  const response = await fetch(endpoint, {
    headers: { Accept: 'application/json' },
  });
  if (!response.ok) return null;

  const payload = await response.json();
  const route = payload?.routes?.[0];
  if (!route?.geometry?.coordinates?.length) return null;

  const routePoints = route.geometry.coordinates.map(([lng, lat]) => [lat, lng]);
  const resolved = {
    points: routePoints,
    distanceMeters: route.distance,
    durationSeconds: route.duration,
  };
  if (key) {
    routeCache.set(key, resolved);
  }
  return resolved;
};
