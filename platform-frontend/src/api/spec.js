import http from './http'

export function listSpecs() {
  return http.get('/specs')
}

export function createSpec({ name, specType, content }) {
  return http.post('/specs', { name, specType, content })
}

export function updateSpec(specId, { name, specType, content }) {
  return http.put(`/specs/${specId}`, { name, specType, content })
}

export function deleteSpec(specId) {
  return http.delete(`/specs/${specId}`)
}

export function listSpecLinks(specId) {
  return http.get(`/specs/${specId}/links`)
}

export function linkSpecToProject(specId, projectId) {
  return http.post(`/specs/${specId}/link`, { projectId })
}

export function unlinkSpecFromProject(specId, projectId) {
  return http.delete(`/specs/${specId}/link/${projectId}`)
}
