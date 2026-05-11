/**
 * Central URLs for the React app. Override with CRA env vars when not using localhost.
 * REACT_APP_API_URL — e.g. http://192.168.1.5:8080/api
 * REACT_APP_WS_URL  — optional full SockJS endpoint, e.g. http://192.168.1.5:8080/ws
 */

const trimSlash = (s) => s.replace(/\/+$/, '');

const rawApi = trimSlash(process.env.REACT_APP_API_URL || 'http://localhost:8080/api');

export const API_BASE_URL = rawApi.endsWith('/api') ? rawApi : `${rawApi}/api`;

export const SERVER_ORIGIN = API_BASE_URL.replace(/\/api$/, '');

export const WS_SOCKJS_URL = trimSlash(process.env.REACT_APP_WS_URL || `${SERVER_ORIGIN}/ws`);

export function resolveServerUrl(path) {
  if (!path) return '';
  if (path.startsWith('http')) return path;
  const p = path.startsWith('/') ? path : `/${path}`;
  return `${SERVER_ORIGIN}${p}`;
}
