import http from './http'

export function createProject({ name, gitUrl, defaultBranch, description }) {
  return http.post('/projects', { name, gitUrl, defaultBranch, description })
}

export function listProjects() {
  return http.get('/projects')
}

export function getProject(projectId) {
  return http.get(`/projects/${projectId}`)
}

export function updateProject(projectId, { name, gitUrl, defaultBranch, description }) {
  return http.put(`/projects/${projectId}`, { name, gitUrl, defaultBranch, description })
}

export function deleteProject(projectId) {
  return http.delete(`/projects/${projectId}`)
}

export function retryClone(projectId) {
  return http.post(`/projects/${projectId}/retry-clone`)
}

export function getConversations(projectId) {
  return http.get(`/projects/${projectId}/conversations`)
}

export function sendMessage(projectId, message) {
  return http.post(`/projects/${projectId}/chat`, null, { params: { message } })
}
