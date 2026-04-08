import http from './http'

export function getSettings() {
  return http.get('/settings')
}

export function updateSettings(data) {
  return http.put('/settings', data)
}
