import http from './http'

export function createProject(name, description) {
  return http.post('/projects', null, { params: { name, description } })
}

export function listProjects() {
  return http.get('/projects')
}

export function getProject(projectId) {
  return http.get(`/projects/${projectId}`)
}

export function deleteProject(projectId) {
  return http.delete(`/projects/${projectId}`)
}

export function getConversations(projectId) {
  return http.get(`/projects/${projectId}/conversations`)
}

export function sendMessage(projectId, message) {
  return http.post(`/projects/${projectId}/chat`, null, { params: { message } })
}
